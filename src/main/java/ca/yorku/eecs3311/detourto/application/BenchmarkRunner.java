package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.AgentContext;
import ca.yorku.eecs3311.detourto.agent.AgentRequest;
import ca.yorku.eecs3311.detourto.agent.AgentResult;
import ca.yorku.eecs3311.detourto.agent.AgentStepListener;
import ca.yorku.eecs3311.detourto.agent.AlertInterpreterAgent;
import ca.yorku.eecs3311.detourto.agent.FindStopTool;
import ca.yorku.eecs3311.detourto.agent.GroundingValidator;
import ca.yorku.eecs3311.detourto.agent.LLMClient;
import ca.yorku.eecs3311.detourto.agent.PlanJourneyTool;
import ca.yorku.eecs3311.detourto.agent.ResponseParser;
import ca.yorku.eecs3311.detourto.agent.RouteStopsTool;
import ca.yorku.eecs3311.detourto.agent.ToolRegistry;
import ca.yorku.eecs3311.detourto.agent.TripRequestAgent;
import ca.yorku.eecs3311.detourto.domain.BenchmarkReport;
import ca.yorku.eecs3311.detourto.domain.Disruption;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.ReplayScenario;
import ca.yorku.eecs3311.detourto.domain.RouteScoringStrategy;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.ReplayRealtimeFeed;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feature F12: replays recorded scenarios through the full pipeline (agent, alert interpretation,
 * planner) and measures the result. Each scenario gets fresh agents and its own alert service, so
 * scenarios do not affect each other. A failing scenario is reported and the rest continue.
 */
public final class BenchmarkRunner {

    private final JourneyPlanner planner;
    private final PlaceResolver places;
    private final LLMClient llm;

    /** {@code llm} may be null: scenarios then use from/to and structured alerts only. */
    public BenchmarkRunner(JourneyPlanner planner, PlaceResolver places, LLMClient llm) {
        this.planner = planner;
        this.places = places;
        this.llm = llm;
    }

    public List<BenchmarkReport> runAll(List<ReplayScenario> scenarios) {
        List<BenchmarkReport> out = new ArrayList<>();
        for (ReplayScenario s : scenarios) {
            out.add(run(s));
        }
        return out;
    }

    public BenchmarkReport run(ReplayScenario s) {
        long start = System.nanoTime();
        TransitNetwork net = planner.network();
        int[] counts = new int[2]; // [0] llm calls, [1] rejected answers
        AgentStepListener counter = (agent, kind, detail) -> {
            if (kind.equals("llm_call")) {
                counts[0]++;
            } else if (kind.equals("rejected")) {
                counts[1]++;
            }
        };
        try {
            GroundingValidator validator = new GroundingValidator(net);
            AlertInterpreterAgent interpreter = null;
            if (llm != null) {
                interpreter = new AlertInterpreterAgent(llm, new ToolRegistry().register(new RouteStopsTool(net))
                        .register(new FindStopTool(places)), new ResponseParser(), validator, net);
                interpreter.setStepListener(counter);
            }
            AlertService alerts = new AlertService(new ReplayRealtimeFeed(s.alerts()), interpreter, net);

            TripQuery q = query(s, validator, counter);
            List<Disruption> applied = alerts.currentDisruptions(s.at()).stream()
                    .filter(Disruption::appliesByDefault).toList();
            RouteScoringStrategy strategy = RouteScoringStrategy.of(q.strategyKind());
            List<Itinerary> baseline = planner.plan(q.copy(), net.fullView(), strategy);
            List<Itinerary> disrupted = planner.plan(q.copy(), net.withDisruptions(applied), strategy);

            Set<String> closed = stations(applied.stream().flatMap(d -> d.closedStopIds().stream()).collect(Collectors.toSet()));
            boolean matched = closed.equals(stations(s.expectedClosedStopIds()));
            return new BenchmarkReport(s.name(), !disrupted.isEmpty(), delayMinutes(q, baseline, disrupted), matched,
                    counts[1], counts[0], elapsed(start), llm != null, null);
        } catch (RuntimeException e) {
            return new BenchmarkReport(s.name(), false, 0, false, counts[1], counts[0], elapsed(start), llm != null,
                    e.getMessage());
        }
    }

    private TripQuery query(ReplayScenario s, GroundingValidator validator, AgentStepListener counter) {
        if (llm != null && s.request() != null) {
            TripRequestAgent agent = new TripRequestAgent(llm, new ToolRegistry().register(new FindStopTool(places))
                    .register(new PlanJourneyTool(planner)), new ResponseParser(), validator, planner.network());
            agent.setStepListener(counter);
            AgentResult r = agent.run(new AgentRequest("trip_request", s.request(), AgentContext.at(s.at())));
            if (!r.success()) {
                throw new IllegalStateException("agent failed: " + String.join("; ", r.errors()));
            }
            if (!(r.output() instanceof TripQuery q)) {
                throw new IllegalStateException("agent asked for clarification instead of planning");
            }
            return q;
        }
        if (s.from() == null || s.to() == null) {
            throw new IllegalStateException("scenario needs an LLM for its request, or from/to");
        }
        Stop o = first(s.from());
        Stop d = first(s.to());
        TripQuery q = s.arriveBy() ? TripQuery.arriveBy(o, d, s.at()) : TripQuery.departAt(o, d, s.at());
        q.setStrategyKind(s.strategy());
        return q;
    }

    private Stop first(String text) {
        List<Stop> found = places.resolve(text);
        if (found.isEmpty()) {
            throw new IllegalStateException("no stop matches '" + text + "'");
        }
        return found.get(0);
    }

    /** Arrival delay (depart-at) or how much earlier one must leave (arrive-by), in minutes. */
    static int delayMinutes(TripQuery q, List<Itinerary> baseline, List<Itinerary> disrupted) {
        if (baseline.isEmpty() || disrupted.isEmpty()) {
            return 0;
        }
        if (q.isArriveBy()) {
            LocalDateTime base = baseline.stream().map(Itinerary::departure).max(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime dis = disrupted.stream().map(Itinerary::departure).max(Comparator.naturalOrder()).orElseThrow();
            return (int) Duration.between(dis, base).toMinutes();
        }
        LocalDateTime base = baseline.stream().map(Itinerary::arrival).min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime dis = disrupted.stream().map(Itinerary::arrival).min(Comparator.naturalOrder()).orElseThrow();
        return (int) Duration.between(base, dis).toMinutes();
    }

    /** Platforms are compared as their station, so "SG_L1" and "STN_SG" count as the same place. */
    private Set<String> stations(Set<String> stopIds) {
        return stopIds.stream()
                .map(id -> planner.network().findStop(id).map(s -> s.parentStation() != null ? s.parentStation() : s.id())
                        .orElse(id))
                .collect(Collectors.toSet());
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

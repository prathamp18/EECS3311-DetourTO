package ca.yorku.eecs3311.detourto.application;

import ca.yorku.eecs3311.detourto.agent.ClarificationRequest;
import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.NetworkView;
import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.RouteScoringStrategy;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import ca.yorku.eecs3311.detourto.infrastructure.AppConfig;
import ca.yorku.eecs3311.detourto.infrastructure.GtfsStaticLoader;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Facade pattern: the single entry point the CLI and (later) the GUI use. Clients never touch the
 * planner, resolver or strategies directly, so those can change without changing the clients.
 *
 * <p>Week 1 covers planning from a form or command line. Agents, alerts, commands (undo/redo),
 * monitoring and profiles are added in later weeks behind the same facade.
 */
public final class TripController {

    /** Clarification lists only stops scoring within this much of the best match. */
    static final double CANDIDATE_MARGIN = 0.15;

    private final JourneyPlanner planner;
    private final PlaceResolver places;
    private final Map<StrategyKind, RouteScoringStrategy> strategies = new EnumMap<>(StrategyKind.class);
    private TripQuery currentQuery;
    private List<Itinerary> lastOptions = List.of();

    public TripController(JourneyPlanner planner, PlaceResolver places) {
        this.planner = planner;
        this.places = places;
        for (StrategyKind k : StrategyKind.values()) {
            strategies.put(k, RouteScoringStrategy.of(k));
        }
    }

    /** Loads the GTFS feed named in the config and wires the services together. */
    public static TripController fromConfig(AppConfig config) throws IOException {
        TransitNetwork network = new GtfsStaticLoader().load(config.gtfsPath());
        return new TripController(new JourneyPlanner(network), new PlaceResolver(network));
    }

    public TransitNetwork network() {
        return planner.network();
    }

    /**
     * Resolves place text to one stop. Returns the clarification as the error when the text is
     * ambiguous or matches nothing.
     */
    public Resolution resolvePlace(String text) {
        List<PlaceResolver.Match> matches = places.match(text);
        if (matches.isEmpty()) {
            return Resolution.failed(new ClarificationRequest(
                    "I couldn't find a stop called \"" + text + "\". Try a station or street name.", List.of()));
        }
        if (places.isAmbiguous(matches)) {
            double top = matches.get(0).score();
            List<Stop> options = matches.stream()
                    .filter(m -> m.score() >= top - CANDIDATE_MARGIN)
                    .map(PlaceResolver.Match::stop).toList();
            return Resolution.failed(new ClarificationRequest("Which \"" + text + "\" did you mean: "
                    + options.stream().map(Stop::name).collect(Collectors.joining(", ")) + "?", options));
        }
        return Resolution.found(matches.get(0).stop());
    }

    /** Result of {@link #resolvePlace(String)}: a stop, or a question for the rider. */
    public record Resolution(Optional<Stop> stop, ClarificationRequest clarification) {
        static Resolution found(Stop s) {
            return new Resolution(Optional.of(s), null);
        }

        static Resolution failed(ClarificationRequest c) {
            return new Resolution(Optional.empty(), c);
        }
    }

    /** Plans a fully specified query (Trip Planner form, or CLI flags). */
    public PlanResult planFromForm(TripQuery q) {
        return planOn(q, planner.network().fullView());
    }

    /** Changes the strategy of the current query and plans again. */
    public PlanResult setStrategy(StrategyKind kind) {
        if (currentQuery == null) {
            throw new IllegalStateException("plan a trip before choosing a strategy");
        }
        currentQuery.setStrategyKind(kind);
        return planFromForm(currentQuery);
    }

    PlanResult planOn(TripQuery q, NetworkView view) {
        if (q.origin().equals(q.destination())) {
            return PlanResult.ok(q, List.of(), "Origin and destination are the same stop.");
        }
        RouteScoringStrategy strategy = strategies.get(q.strategyKind());
        List<Itinerary> options = planner.plan(q, view, strategy);
        currentQuery = q;
        lastOptions = options;
        String message = options.isEmpty()
                ? noRouteMessage(q)
                : options.size() + " option" + (options.size() == 1 ? "" : "s") + ", ranked by "
                        + strategy.label();
        return PlanResult.ok(q, options, message);
    }

    private String noRouteMessage(TripQuery q) {
        if (q.strategyKind() == StrategyKind.ACCESSIBLE) {
            return "No step-free route found. One of the stations on the way may lack elevators.";
        }
        return "No route found at that time. Try a later time or allow more routes.";
    }

    public Optional<TripQuery> currentQuery() {
        return Optional.ofNullable(currentQuery);
    }

    public List<Itinerary> lastOptions() {
        return lastOptions;
    }

    public RouteScoringStrategy strategy(StrategyKind kind) {
        return strategies.get(kind);
    }
}

package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.JourneyPlanner;
import ca.yorku.eecs3311.detourto.domain.RideLeg;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.StrategyKind;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code plan_journey(...)}: runs the real planner so the model can check that a trip is
 * possible (e.g. "is there a step-free route?") before answering.
 */
public final class PlanJourneyTool implements Tool {

    public static final String NAME = "plan_journey";

    private final JourneyPlanner planner;

    public PlanJourneyTool(JourneyPlanner planner) {
        this.planner = planner;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "Plan a trip between two stop ids (from find_stop) and return up to 5 options with "
                        + "departure, arrival, minutes, transfers and routes.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "origin_stop_id", Map.of("type", "string"),
                                "destination_stop_id", Map.of("type", "string"),
                                "time", Map.of("type", "string", "description", "ISO local time, e.g. 2026-10-28T09:30"),
                                "time_type", Map.of("type", "string", "enum", List.of("depart_at", "arrive_by")),
                                "strategy", Map.of("type", "string",
                                        "enum", List.of("fastest", "fewest-transfers", "least-walking", "accessible"))),
                        "required", List.of("origin_stop_id", "destination_stop_id", "time")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext ctx) {
        Optional<Stop> from = planner.network().findStop(call.stringArg("origin_stop_id", ""));
        Optional<Stop> to = planner.network().findStop(call.stringArg("destination_stop_id", ""));
        if (from.isEmpty() || to.isEmpty()) {
            return ToolResult.error("unknown stop id; call find_stop first");
        }
        LocalDateTime time;
        try {
            time = LocalDateTime.parse(call.stringArg("time", ctx.now().toString()));
        } catch (DateTimeParseException e) {
            return ToolResult.error("time must look like 2026-10-28T09:30");
        }
        TripQuery q = "arrive_by".equals(call.stringArg("time_type", "depart_at"))
                ? TripQuery.arriveBy(from.get(), to.get(), time)
                : TripQuery.departAt(from.get(), to.get(), time);
        try {
            q.setStrategyKind(StrategyKind.parse(call.stringArg("strategy", "fastest")));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("unknown strategy");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Itinerary it : planner.plan(q)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.id());
            row.put("departure", it.departure().toLocalTime().toString());
            row.put("arrival", it.arrival().toLocalTime().toString());
            row.put("minutes", it.durationMinutes());
            row.put("transfers", it.transfers());
            row.put("walk_meters", it.walkMeters());
            row.put("routes", it.rideLegs().stream().map(RideLeg::route).map(r -> r.label()).toList());
            rows.add(row);
        }
        return ToolResult.ok(Map.of("options", rows, "count", rows.size()));
    }
}

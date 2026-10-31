package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Route;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code get_route_stops(route_id)}: the stops a route serves, in order (for reading alerts). */
public final class RouteStopsTool implements Tool {

    public static final String NAME = "get_route_stops";

    private final TransitNetwork network;

    public RouteStopsTool(TransitNetwork network) {
        this.network = network;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "List the stops served by a route in travel order, with their stop_id and station_id. "
                        + "Use it to find the ids of stops an alert talks about.",
                Map.of("type", "object",
                        "properties", Map.of("route_id", Map.of("type", "string",
                                "description", "Route id such as 1, 2, 510 or 29")),
                        "required", List.of("route_id")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext ctx) {
        String routeId = call.stringArg("route_id", "").strip();
        Optional<Route> route = network.findRoute(routeId);
        if (route.isEmpty()) {
            return ToolResult.error("unknown route '" + routeId + "'");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Stop s : network.stopsOfRoute(routeId)) {
            ctx.recordStop(s.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stop_id", s.id());
            row.put("name", s.name());
            if (s.parentStation() != null) {
                ctx.recordStop(s.parentStation());
                row.put("station_id", s.parentStation());
            }
            rows.add(row);
        }
        return ToolResult.ok(Map.of("route", route.get().label(), "stops", rows));
    }
}

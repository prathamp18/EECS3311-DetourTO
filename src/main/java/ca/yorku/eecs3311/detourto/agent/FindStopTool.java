package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.PlaceResolver;
import ca.yorku.eecs3311.detourto.domain.Stop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code find_stop(query)}: real stops matching a place name, via {@link PlaceResolver}. */
public final class FindStopTool implements Tool {

    public static final String NAME = "find_stop";

    private final PlaceResolver resolver;

    public FindStopTool(PlaceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(NAME,
                "Find TTC stops or stations matching a place name, street intersection, stop id or "
                        + "'lat,lon'. Returns up to 5 candidates, best first, with a match score 0..1. "
                        + "Always use this to get stop ids; never guess an id.",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string",
                                "description", "What the rider called the place, e.g. 'York U'")),
                        "required", List.of("query")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext ctx) {
        String query = call.stringArg("query", "").strip();
        if (query.isEmpty()) {
            return ToolResult.error("query is required");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlaceResolver.Match m : resolver.match(query)) {
            Stop s = m.stop();
            ctx.recordStop(s.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stop_id", s.id());
            row.put("name", s.name());
            row.put("score", Math.round(m.score() * 100) / 100.0);
            row.put("station", s.station());
            row.put("step_free", s.wheelchairBoarding());
            out.add(row);
        }
        if (out.isEmpty()) {
            return ToolResult.error("no stop matches '" + query + "'");
        }
        return ToolResult.ok(Map.of("candidates", out));
    }
}

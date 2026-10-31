package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * A recorded situation to replay (feature F12): the time, what the rider asked, the alerts that
 * were active, and the stops that were really closed (the expected answer).
 *
 * @param request plain-English request for the agent (used when an LLM is configured)
 * @param from    origin text, used without an LLM (and as a check of the agent's answer)
 * @param to      destination text
 */
public record ReplayScenario(String name, LocalDateTime at, String request, String from, String to,
                             StrategyKind strategy, boolean arriveBy, List<ServiceAlert> alerts,
                             Set<String> expectedClosedStopIds) {

    public ReplayScenario {
        alerts = List.copyOf(alerts);
        expectedClosedStopIds = Set.copyOf(expectedClosedStopIds);
        strategy = strategy == null ? StrategyKind.FASTEST : strategy;
    }
}

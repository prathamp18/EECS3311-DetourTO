package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Itinerary;
import ca.yorku.eecs3311.detourto.domain.TripQuery;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What an agent run knows: the current time, the current query and options, the conversation,
 * and every stop id that a tool returned during this run. The grounding validator only accepts
 * stop ids from that set, so the model cannot invent a stop.
 */
public final class AgentContext {

    private final LocalDateTime now;
    private final TripQuery query;
    private final List<Itinerary> options;
    private final ConversationHistory conversation;
    private final Set<String> toolStopIds = new LinkedHashSet<>();

    public AgentContext(LocalDateTime now, TripQuery query, List<Itinerary> options,
                        ConversationHistory conversation) {
        this.now = now;
        this.query = query;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.conversation = conversation == null ? new ConversationHistory() : conversation;
    }

    public static AgentContext at(LocalDateTime now) {
        return new AgentContext(now, null, List.of(), new ConversationHistory());
    }

    public LocalDateTime now() {
        return now;
    }

    public Optional<TripQuery> query() {
        return Optional.ofNullable(query);
    }

    public List<Itinerary> options() {
        return options;
    }

    public ConversationHistory conversation() {
        return conversation;
    }

    /** Called by tools for every stop they return. */
    public void recordStop(String stopId) {
        toolStopIds.add(stopId);
    }

    public boolean stopWasReturnedByTool(String stopId) {
        return toolStopIds.contains(stopId);
    }

    public Set<String> toolStopIds() {
        return Set.copyOf(toolStopIds);
    }
}

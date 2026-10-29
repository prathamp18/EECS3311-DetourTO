package ca.yorku.eecs3311.detourto.agent;

import java.util.ArrayList;
import java.util.List;

/** The rider's recent turns, so follow-ups ("leave 15 minutes later") have context. */
public final class ConversationHistory {

    public static final int DEFAULT_MAX_TURNS = 20;

    private final List<Message> messages = new ArrayList<>();
    private final int maxTurns;

    public ConversationHistory() {
        this(DEFAULT_MAX_TURNS);
    }

    public ConversationHistory(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public synchronized void append(Message m) {
        messages.add(m);
        while (messages.size() > maxTurns) {
            messages.remove(0);
        }
    }

    /** The last {@code n} messages, oldest first. */
    public synchronized List<Message> recent(int n) {
        return List.copyOf(messages.subList(Math.max(0, messages.size() - n), messages.size()));
    }

    public synchronized int size() {
        return messages.size();
    }
}

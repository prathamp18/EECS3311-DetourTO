package ca.yorku.eecs3311.detourto.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Observer pattern (subject): components publish events without knowing who listens. The trace
 * logger, the CLI's verbose output and (later) the GUI panels subscribe to the types they need.
 * A listener that throws does not stop the others.
 */
public final class EventBus {

    private final Map<EventType, List<EventListener>> listeners = new EnumMap<>(EventType.class);

    public synchronized void subscribe(EventType type, EventListener l) {
        listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(l);
    }

    public void subscribeAll(EventListener l) {
        for (EventType t : EventType.values()) {
            subscribe(t, l);
        }
    }

    public synchronized void unsubscribe(EventType type, EventListener l) {
        List<EventListener> list = listeners.get(type);
        if (list != null) {
            list.remove(l);
        }
    }

    public void publish(TransitEvent e) {
        List<EventListener> list;
        synchronized (this) {
            list = listeners.get(e.type());
        }
        if (list == null) {
            return;
        }
        for (EventListener l : list) {
            try {
                l.onEvent(e);
            } catch (RuntimeException ex) {
                System.err.println("event listener failed on " + e.type() + ": " + ex.getMessage());
            }
        }
    }

    public synchronized int listenerCount(EventType type) {
        List<EventListener> list = listeners.get(type);
        return list == null ? 0 : list.size();
    }
}

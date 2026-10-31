package ca.yorku.eecs3311.detourto.application;

/** Observer: receives events it subscribed to. */
@FunctionalInterface
public interface EventListener {

    void onEvent(TransitEvent e);
}

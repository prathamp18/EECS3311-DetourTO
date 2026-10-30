package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.util.List;

/**
 * Decorator pattern: wraps any {@link RealtimeFeed} and adds behaviour (caching, failure
 * handling) without changing the feed itself. Decorators can be stacked in any order.
 */
public abstract class RealtimeFeedDecorator implements RealtimeFeed {

    protected final RealtimeFeed inner;

    protected RealtimeFeedDecorator(RealtimeFeed inner) {
        this.inner = inner;
    }

    @Override
    public List<ServiceAlert> alerts() {
        return inner.alerts();
    }

    @Override
    public RealtimeSnapshot snapshot() {
        return inner.snapshot();
    }
}

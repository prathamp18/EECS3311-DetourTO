package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RealtimeSnapshot;
import ca.yorku.eecs3311.detourto.domain.ServiceAlert;
import java.util.List;

/**
 * Live TTC data. Implementations may throw {@link FeedException}; wrap them in
 * {@link ResilientRealtimeFeed} when callers need an answer even when the feed is down.
 */
public interface RealtimeFeed {

    List<ServiceAlert> alerts();

    RealtimeSnapshot snapshot();
}

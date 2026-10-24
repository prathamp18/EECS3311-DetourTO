package ca.yorku.eecs3311.detourto.agent;

import ca.yorku.eecs3311.detourto.domain.Stop;
import java.util.List;

/** A question back to the rider when a place name matches several stops. */
public record ClarificationRequest(String question, List<Stop> candidates) {

    public ClarificationRequest {
        candidates = List.copyOf(candidates);
    }
}

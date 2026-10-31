package ca.yorku.eecs3311.detourto.agent;

import java.util.List;
import java.util.Map;

/**
 * A plain-language comparison of the options (feature F06). {@code llmGenerated} is false for
 * the deterministic comparison used when the LLM is unavailable or its answer failed validation.
 */
public record Explanation(String summary, Map<String, String> optionNotes, List<String> citedItineraryIds,
                          List<Integer> citedMinutes, boolean llmGenerated) {

    public Explanation {
        optionNotes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(optionNotes));
        citedItineraryIds = List.copyOf(citedItineraryIds);
        citedMinutes = List.copyOf(citedMinutes);
    }
}

package ca.yorku.eecs3311.detourto.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a rider types ("York U", "st george", "Spadina & College", "43.66,-79.40", a stop
 * id) into ranked stops. Platforms are folded into their station so "Union" gives one answer.
 *
 * <p>Saved places from the rider profile ("home", "school") are added in week 3.
 */
public final class PlaceResolver {

    public static final int MAX_RESULTS = 5;
    /** Two candidates closer than this are reported as ambiguous. */
    public static final double AMBIGUITY_MARGIN = 0.05;
    private static final int NEAREST_RADIUS_M = 600;

    private static final Pattern LAT_LON =
            Pattern.compile("\\s*(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)\\s*");
    private static final Set<String> NOISE = Set.of("station", "stn", "the", "at", "and", "platform",
            "ttc", "stop", "line");
    private static final Map<String, String> SYNONYMS = Map.of(
            "saint", "st", "street", "st", "avenue", "ave", "av", "ave", "road", "rd",
            "west", "w", "east", "e", "university", "u", "univ", "u");

    /** A matched stop and how well it matched (0..1). */
    public record Match(Stop stop, double score) {
    }

    private final TransitNetwork network;

    public PlaceResolver(TransitNetwork network) {
        this.network = network;
    }

    /** Best matches first; empty when nothing looks similar. */
    public List<Stop> resolve(String text) {
        return match(text).stream().map(Match::stop).toList();
    }

    /** Best matches first with their scores. */
    public List<Match> match(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Optional<Stop> exact = network.findStop(text.strip());
        if (exact.isPresent()) {
            return List.of(new Match(fold(exact.get()), 1.0));
        }
        Matcher m = LAT_LON.matcher(text);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1));
            double lon = Double.parseDouble(m.group(2));
            return network.stopsNear(lat, lon, NEAREST_RADIUS_M).stream()
                    .map(this::fold).distinct().limit(MAX_RESULTS)
                    .map(s -> new Match(s, 1.0 - s.distanceMetersTo(lat, lon) / NEAREST_RADIUS_M))
                    .toList();
        }
        List<String> query = tokens(text);
        if (query.isEmpty()) {
            return List.of();
        }
        List<Match> scored = new ArrayList<>();
        for (Stop s : network.stops()) {
            if (s.parentStation() != null && network.findStop(s.parentStation()).isPresent()) {
                continue; // the station itself is scored instead
            }
            double score = similarity(query, tokens(s.name()));
            if (score >= 0.5) {
                scored.add(new Match(s, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(x -> !x.stop().station())
                .thenComparing(x -> x.stop().name().length()));
        Set<String> seen = new HashSet<>();
        List<Match> out = new ArrayList<>();
        for (Match x : scored) {
            if (seen.add(x.stop().id()) && out.size() < MAX_RESULTS) {
                out.add(x);
            }
        }
        return out;
    }

    /** True when the two best matches are about equally good, so the rider should choose. */
    public boolean isAmbiguous(List<Match> matches) {
        return matches.size() >= 2
                && matches.get(0).score() - matches.get(1).score() < AMBIGUITY_MARGIN;
    }

    /** A platform is reported as its station. */
    private Stop fold(Stop s) {
        if (s.parentStation() != null) {
            return network.findStop(s.parentStation()).orElse(s);
        }
        return s;
    }

    /**
     * 70% for how much of the query was found in the name, 30% for how much of the name the query
     * covers (so "Finch" prefers "Finch Station" over "Finch West Station"). A query word matches a
     * name word that starts with it ("u" matches "university").
     */
    static double similarity(List<String> query, List<String> name) {
        if (query.isEmpty() || name.isEmpty()) {
            return 0;
        }
        Set<Integer> usedName = new HashSet<>();
        int matchedQuery = 0;
        for (String q : query) {
            for (int i = 0; i < name.size(); i++) {
                if (!usedName.contains(i) && name.get(i).startsWith(q)) {
                    usedName.add(i);
                    matchedQuery++;
                    break;
                }
            }
        }
        double queryCoverage = (double) matchedQuery / query.size();
        double nameCoverage = (double) usedName.size() / name.size();
        return 0.7 * queryCoverage + 0.3 * nameCoverage;
    }

    static List<String> tokens(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replace("'", "").replaceAll("[^a-z0-9]+", " ").strip();
        if (plain.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : Arrays.asList(plain.split(" "))) {
            String w = SYNONYMS.getOrDefault(t, t);
            if (!NOISE.contains(w)) {
                out.add(w);
            }
        }
        return new ArrayList<>(out);
    }
}

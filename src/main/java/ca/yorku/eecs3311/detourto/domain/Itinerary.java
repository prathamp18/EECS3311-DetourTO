package ca.yorku.eecs3311.detourto.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One way to make the trip: an ordered list of legs. The id ("opt-1", "opt-2", ...) is what the
 * agents may cite, and what the grounding validator checks against.
 */
public final class Itinerary {

    private final String id;
    private final List<Leg> legs;

    public Itinerary(String id, List<Leg> legs) {
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("an itinerary needs at least one leg");
        }
        this.id = id;
        this.legs = List.copyOf(legs);
    }

    /** Same legs with a different id (ids are assigned after ranking). */
    public Itinerary withId(String newId) {
        return new Itinerary(newId, legs);
    }

    public String id() {
        return id;
    }

    public List<Leg> legs() {
        return legs;
    }

    public List<RideLeg> rideLegs() {
        return legs.stream().filter(RideLeg.class::isInstance).map(RideLeg.class::cast).toList();
    }

    public Stop origin() {
        return legs.get(0).from();
    }

    public Stop destination() {
        return legs.get(legs.size() - 1).to();
    }

    public LocalDateTime departure() {
        return legs.get(0).start();
    }

    public LocalDateTime arrival() {
        return legs.get(legs.size() - 1).end();
    }

    public int durationMinutes() {
        return (int) Math.round(Duration.between(departure(), arrival()).getSeconds() / 60.0);
    }

    /** Number of vehicle changes: rides minus one (0 for a direct or walk-only trip). */
    public int transfers() {
        return Math.max(0, rideLegs().size() - 1);
    }

    public int walkMeters() {
        return legs.stream().filter(WalkLeg.class::isInstance)
                .mapToInt(l -> ((WalkLeg) l).meters()).sum();
    }

    public int walkSeconds() {
        return (int) legs.stream().filter(WalkLeg.class::isInstance).mapToLong(Leg::seconds).sum();
    }

    /** Route ids in riding order, without duplicates. */
    public Set<String> routeIds() {
        return rideLegs().stream().map(l -> l.route().id())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Identifies the same journey across searches (trip ids and stops), ignoring the id. */
    String signature() {
        return legs.stream().map(l -> l instanceof RideLeg r
                        ? r.trip().id() + "@" + r.boardPos() + "-" + r.alightPos()
                        : "W" + l.from().id() + ">" + l.to().id())
                .collect(Collectors.joining("|"));
    }

    @Override
    public String toString() {
        return id + " " + departure().toLocalTime() + "-" + arrival().toLocalTime() + " ("
                + durationMinutes() + " min, " + transfers() + " transfers): "
                + legs.stream().map(Object::toString).collect(Collectors.joining("; "));
    }
}

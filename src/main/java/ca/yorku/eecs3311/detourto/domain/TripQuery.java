package ca.yorku.eecs3311.detourto.domain;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What the rider asked for. Exactly one of departAt / arriveBy is set.
 *
 * <p>The class is mutable on purpose: follow-up commands (feature F09, Command pattern) change one
 * field and undo puts the old value back. {@link #copy()} gives an independent snapshot.
 */
public final class TripQuery {

    public static final int DEFAULT_MAX_WALK_METERS = 800;

    private Stop origin;
    private Stop destination;
    private LocalDateTime departAt;
    private LocalDateTime arriveBy;
    private StrategyKind strategyKind = StrategyKind.FASTEST;
    private final Set<String> avoidRouteIds = new LinkedHashSet<>();
    private final Set<TransitMode> avoidModes = java.util.EnumSet.noneOf(TransitMode.class);
    private int maxWalkMeters = DEFAULT_MAX_WALK_METERS;

    private TripQuery(Stop origin, Stop destination) {
        this.origin = Objects.requireNonNull(origin, "origin");
        this.destination = Objects.requireNonNull(destination, "destination");
    }

    public static TripQuery departAt(Stop origin, Stop destination, LocalDateTime time) {
        TripQuery q = new TripQuery(origin, destination);
        q.setDepartAt(time);
        return q;
    }

    public static TripQuery arriveBy(Stop origin, Stop destination, LocalDateTime time) {
        TripQuery q = new TripQuery(origin, destination);
        q.setArriveBy(time);
        return q;
    }

    public TripQuery copy() {
        TripQuery q = new TripQuery(origin, destination);
        q.departAt = departAt;
        q.arriveBy = arriveBy;
        q.strategyKind = strategyKind;
        q.avoidRouteIds.addAll(avoidRouteIds);
        q.avoidModes.addAll(avoidModes);
        q.maxWalkMeters = maxWalkMeters;
        return q;
    }

    public Stop origin() {
        return origin;
    }

    public void setOrigin(Stop origin) {
        this.origin = Objects.requireNonNull(origin);
    }

    public Stop destination() {
        return destination;
    }

    public void setDestination(Stop destination) {
        this.destination = Objects.requireNonNull(destination);
    }

    public LocalDateTime departAt() {
        return departAt;
    }

    public LocalDateTime arriveBy() {
        return arriveBy;
    }

    public boolean isArriveBy() {
        return arriveBy != null;
    }

    /** The time the rider gave, whichever kind it is. */
    public LocalDateTime time() {
        return isArriveBy() ? arriveBy : departAt;
    }

    public void setDepartAt(LocalDateTime time) {
        this.departAt = Objects.requireNonNull(time);
        this.arriveBy = null;
    }

    public void setArriveBy(LocalDateTime time) {
        this.arriveBy = Objects.requireNonNull(time);
        this.departAt = null;
    }

    public StrategyKind strategyKind() {
        return strategyKind;
    }

    public void setStrategyKind(StrategyKind kind) {
        this.strategyKind = Objects.requireNonNull(kind);
    }

    public Set<String> avoidRouteIds() {
        return Set.copyOf(avoidRouteIds);
    }

    public void avoidRoute(String routeId) {
        avoidRouteIds.add(routeId);
    }

    public void allowRoute(String routeId) {
        avoidRouteIds.remove(routeId);
    }

    /** Modes the rider does not want, e.g. "no streetcars". Walking cannot be avoided. */
    public Set<TransitMode> avoidModes() {
        return avoidModes.isEmpty() ? Set.of() : java.util.EnumSet.copyOf(avoidModes);
    }

    public void avoidMode(TransitMode mode) {
        if (mode == TransitMode.WALK) {
            throw new IllegalArgumentException("walking cannot be avoided");
        }
        avoidModes.add(mode);
    }

    public void allowMode(TransitMode mode) {
        avoidModes.remove(mode);
    }

    public int maxWalkMeters() {
        return maxWalkMeters;
    }

    public void setMaxWalkMeters(int meters) {
        if (meters < 0) {
            throw new IllegalArgumentException("maxWalkMeters must be >= 0");
        }
        this.maxWalkMeters = meters;
    }

    @Override
    public String toString() {
        return "TripQuery[" + origin.id() + " -> " + destination.id()
                + (isArriveBy() ? " arrive by " + arriveBy : " depart " + departAt)
                + ", " + strategyKind.cliName() + (avoidRouteIds.isEmpty() ? "" : ", avoid " + avoidRouteIds)
                + (avoidModes.isEmpty() ? "" : ", no " + avoidModes)
                + "]";
    }
}

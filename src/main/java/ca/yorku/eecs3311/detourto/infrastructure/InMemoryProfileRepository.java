package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;

/** Profile kept only for this run (tests, and when no database is configured). */
public final class InMemoryProfileRepository implements ProfileRepository {

    private RiderProfile profile = new RiderProfile();

    @Override
    public synchronized RiderProfile load() {
        return copy(profile);
    }

    @Override
    public synchronized void save(RiderProfile p) {
        profile = copy(p);
    }

    static RiderProfile copy(RiderProfile p) {
        RiderProfile c = new RiderProfile();
        p.savedPlacesInOrder().forEach((k, v) -> c.apply(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE, k + "=" + v)));
        p.avoidedRouteIds().forEach(r -> c.apply(new PreferenceUpdate(PreferenceUpdate.AVOID_ROUTE, r)));
        c.apply(new PreferenceUpdate(PreferenceUpdate.DEFAULT_STRATEGY, p.defaultStrategy().cliName()));
        c.apply(new PreferenceUpdate(PreferenceUpdate.STEP_FREE, String.valueOf(p.needsStepFree())));
        return c;
    }
}

package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.RiderProfile;

/** Loads and saves the rider profile (F09). */
public interface ProfileRepository {

    RiderProfile load();

    void save(RiderProfile p);
}

package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.Commute;
import java.util.List;

/** Saved commutes (F11). */
public interface CommuteRepository {

    List<Commute> findAll();

    void save(Commute c);

    /** @return true when a commute with that name existed */
    boolean delete(String name);
}

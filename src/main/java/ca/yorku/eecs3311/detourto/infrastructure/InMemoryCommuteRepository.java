package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.Commute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Commutes kept only for this run (tests). */
public final class InMemoryCommuteRepository implements CommuteRepository {

    private final Map<String, Commute> commutes = new LinkedHashMap<>();

    @Override
    public synchronized List<Commute> findAll() {
        return new ArrayList<>(commutes.values());
    }

    @Override
    public synchronized void save(Commute c) {
        commutes.put(c.name().toLowerCase(), c);
    }

    @Override
    public synchronized boolean delete(String name) {
        return commutes.remove(name.toLowerCase()) != null;
    }
}

package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.PreferenceUpdate;
import ca.yorku.eecs3311.detourto.domain.RiderProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** Rider profile stored in the local SQLite file, so it survives restarts. */
public final class SqliteProfileRepository implements ProfileRepository {

    private final Connection db;

    public SqliteProfileRepository(Connection db) {
        this.db = db;
    }

    @Override
    public synchronized RiderProfile load() {
        RiderProfile p = new RiderProfile();
        try (Statement st = db.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT label, stop_id FROM saved_place ORDER BY position, label")) {
                while (rs.next()) {
                    p.apply(new PreferenceUpdate(PreferenceUpdate.SAVE_PLACE, rs.getString(1) + "=" + rs.getString(2)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT route_id FROM avoided_route ORDER BY route_id")) {
                while (rs.next()) {
                    p.apply(new PreferenceUpdate(PreferenceUpdate.AVOID_ROUTE, rs.getString(1)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT key, value FROM setting")) {
                while (rs.next()) {
                    p.apply(new PreferenceUpdate(rs.getString(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not load the rider profile: " + e.getMessage(), e);
        }
        return p;
    }

    @Override
    public synchronized void save(RiderProfile p) {
        try {
            db.setAutoCommit(false);
            try (Statement st = db.createStatement()) {
                st.execute("DELETE FROM saved_place");
                st.execute("DELETE FROM avoided_route");
                st.execute("DELETE FROM setting");
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO saved_place VALUES (?, ?, ?)")) {
                int i = 0;
                for (Map.Entry<String, String> e : p.savedPlacesInOrder().entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.setInt(3, i++);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO avoided_route VALUES (?)")) {
                for (String r : p.avoidedRouteIds()) {
                    ps.setString(1, r);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO setting VALUES (?, ?)")) {
                ps.setString(1, PreferenceUpdate.DEFAULT_STRATEGY);
                ps.setString(2, p.defaultStrategy().cliName());
                ps.executeUpdate();
                ps.setString(1, PreferenceUpdate.STEP_FREE);
                ps.setString(2, String.valueOf(p.needsStepFree()));
                ps.executeUpdate();
            }
            db.commit();
        } catch (SQLException e) {
            try {
                db.rollback();
            } catch (SQLException ignored) {
                // keep the original error
            }
            throw new IllegalStateException("could not save the rider profile: " + e.getMessage(), e);
        } finally {
            try {
                db.setAutoCommit(true);
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }
}

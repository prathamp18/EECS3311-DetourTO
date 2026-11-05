package ca.yorku.eecs3311.detourto.infrastructure;

import ca.yorku.eecs3311.detourto.domain.Commute;
import ca.yorku.eecs3311.detourto.domain.Stop;
import ca.yorku.eecs3311.detourto.domain.TransitNetwork;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Commutes in the local SQLite file. Commutes whose stops are not in the network are skipped. */
public final class SqliteCommuteRepository implements CommuteRepository {

    private final Connection db;
    private final TransitNetwork network;

    public SqliteCommuteRepository(Connection db, TransitNetwork network) {
        this.db = db;
        this.network = network;
    }

    @Override
    public synchronized List<Commute> findAll() {
        List<Commute> out = new ArrayList<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, origin_id, destination_id, arrive_by, days, warn_minutes"
                     + " FROM commute ORDER BY name")) {
            while (rs.next()) {
                Optional<Stop> o = network.findStop(rs.getString(2));
                Optional<Stop> d = network.findStop(rs.getString(3));
                if (o.isPresent() && d.isPresent()) {
                    out.add(new Commute(rs.getString(1), o.get(), d.get(), LocalTime.parse(rs.getString(4)),
                            Commute.parseDays(rs.getString(5)), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not load commutes: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public synchronized void save(Commute c) {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO commute VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, c.name());
            ps.setString(2, c.origin().id());
            ps.setString(3, c.destination().id());
            ps.setString(4, c.arriveBy().toString());
            ps.setString(5, c.daysText());
            ps.setInt(6, c.warnMinutesBefore());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not save commute: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean delete(String name) {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM commute WHERE lower(name) = lower(?)")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete commute: " + e.getMessage(), e);
        }
    }
}

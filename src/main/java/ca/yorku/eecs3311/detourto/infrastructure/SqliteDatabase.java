package ca.yorku.eecs3311.detourto.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Opens the local SQLite file (default data/local/detourto.db) and creates the tables. */
public final class SqliteDatabase {

    private SqliteDatabase() {
    }

    public static Connection open(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS saved_place (label TEXT PRIMARY KEY, stop_id TEXT NOT NULL, position INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS avoided_route (route_id TEXT PRIMARY KEY)");
                st.execute("CREATE TABLE IF NOT EXISTS setting (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS commute (name TEXT PRIMARY KEY, origin_id TEXT NOT NULL,"
                        + " destination_id TEXT NOT NULL, arrive_by TEXT NOT NULL, days TEXT NOT NULL,"
                        + " warn_minutes INTEGER NOT NULL)");
            }
            return c;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("could not open database " + file + ": " + e.getMessage(), e);
        }
    }
}

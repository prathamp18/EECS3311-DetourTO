package ca.yorku.eecs3311.detourto.infrastructure;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small streaming CSV reader for GTFS files (RFC 4180 quoting, optional UTF-8 BOM).
 *
 * <p>The TTC stop_times.txt file is several hundred MB, so rows are read one at a time instead of
 * loading the whole file.
 */
final class CsvReader implements Closeable {

    private final BufferedReader in;
    private final Map<String, Integer> header = new HashMap<>();
    private List<String> current;
    private long lineNumber;

    CsvReader(Reader reader) throws IOException {
        this.in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        List<String> names = readRecord();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).strip();
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '﻿') {
                name = name.substring(1);
            }
            header.put(name, i);
        }
    }

    boolean hasColumn(String name) {
        return header.containsKey(name);
    }

    /** Advances to the next row; false at end of file. Blank lines are skipped. */
    boolean next() throws IOException {
        do {
            current = readRecord();
        } while (current != null && current.size() == 1 && current.get(0).isEmpty());
        return current != null;
    }

    /** Value of a column in the current row, or "" when the column or value is missing. */
    String get(String column) {
        Integer i = header.get(column);
        if (i == null || i >= current.size()) {
            return "";
        }
        return current.get(i).strip();
    }

    long lineNumber() {
        return lineNumber;
    }

    private List<String> readRecord() throws IOException {
        String line = in.readLine();
        if (line == null) {
            return null;
        }
        lineNumber++;
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        while (true) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        sb.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
            if (!quoted) {
                break;
            }
            // a quoted field continues on the next line
            String more = in.readLine();
            if (more == null) {
                break;
            }
            lineNumber++;
            sb.append('\n');
            line = more;
        }
        fields.add(sb.toString());
        return fields;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}

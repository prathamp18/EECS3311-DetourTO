package ca.yorku.eecs3311.detourto.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** A recurring trip the rider makes (feature F11), e.g. home to school, arrive by 9:00 on weekdays. */
public record Commute(String name, Stop origin, Stop destination, LocalTime arriveBy, Set<DayOfWeek> days,
                      int warnMinutesBefore) {

    public static final int DEFAULT_WARN_MINUTES = 20;

    public Commute {
        days = days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
        if (warnMinutesBefore < 0 || warnMinutesBefore > 180) {
            throw new IllegalArgumentException("warn minutes must be between 0 and 180");
        }
    }

    public boolean runsOn(LocalDate date) {
        return days.contains(date.getDayOfWeek());
    }

    /** "mon-fri", "weekdays", "weekends", "daily", or a list like "mon,wed,fri". */
    public static Set<DayOfWeek> parseDays(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        switch (t) {
            case "weekdays", "mon-fri" -> {
                return EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
            }
            case "weekends", "sat-sun" -> {
                return EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
            case "daily", "every day", "all" -> {
                return EnumSet.allOf(DayOfWeek.class);
            }
            default -> {
                Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
                for (String part : t.split("[,\\s]+")) {
                    out.add(day(part));
                }
                return out;
            }
        }
    }

    public String daysText() {
        if (days.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) {
            return "weekdays";
        }
        if (days.equals(EnumSet.allOf(DayOfWeek.class))) {
            return "daily";
        }
        return String.join(",", days.stream().map(d -> d.name().substring(0, 3).toLowerCase(Locale.ROOT)).toList());
    }

    private static DayOfWeek day(String s) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().toLowerCase(Locale.ROOT).startsWith(s) && s.length() >= 2) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown day '" + s + "' (use e.g. mon,wed,fri or weekdays)");
    }
}

package ca.yorku.eecs3311.detourto.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which GTFS service_ids run on a given date (calendar.txt plus calendar_dates.txt exceptions).
 */
public final class ServiceCalendar {

    private record Rule(Set<DayOfWeek> days, LocalDate start, LocalDate end) {
    }

    private final Map<String, Rule> rules = new HashMap<>();
    private final Map<LocalDate, Set<String>> added = new HashMap<>();
    private final Map<LocalDate, Set<String>> removed = new HashMap<>();

    public void addRule(String serviceId, Set<DayOfWeek> days, LocalDate start, LocalDate end) {
        rules.put(serviceId, new Rule(Set.copyOf(days), start, end));
    }

    /** exceptionType 1 = service added on that date, 2 = service removed (GTFS calendar_dates). */
    public void addException(String serviceId, LocalDate date, int exceptionType) {
        Map<LocalDate, Set<String>> target = exceptionType == 1 ? added : removed;
        target.computeIfAbsent(date, d -> new HashSet<>()).add(serviceId);
    }

    public boolean isActive(String serviceId, LocalDate date) {
        if (removed.getOrDefault(date, Set.of()).contains(serviceId)) {
            return false;
        }
        if (added.getOrDefault(date, Set.of()).contains(serviceId)) {
            return true;
        }
        Rule r = rules.get(serviceId);
        return r != null && !date.isBefore(r.start()) && !date.isAfter(r.end())
                && r.days().contains(date.getDayOfWeek());
    }

    /** All service ids running on the date. */
    public Set<String> activeServices(LocalDate date) {
        Set<String> out = new HashSet<>();
        for (String id : rules.keySet()) {
            if (isActive(id, date)) {
                out.add(id);
            }
        }
        out.addAll(added.getOrDefault(date, Set.of()));
        out.removeAll(removed.getOrDefault(date, Set.of()));
        return out;
    }

    public int ruleCount() {
        return rules.size();
    }
}

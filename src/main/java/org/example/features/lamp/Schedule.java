package org.example.features.lamp;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Turns the lamp on at {@code on} and off at {@code off} on the given weekdays. */
public record Schedule(boolean enabled, List<String> days, String on, String off) {

    /** Checks the values and returns a normalised copy; throws IllegalArgumentException if invalid. */
    public Schedule validated() {
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one weekday");
        }
        Set<DayOfWeek> parsedDays = EnumSet.noneOf(DayOfWeek.class);
        for (String d : days) {
            try {
                parsedDays.add(DayOfWeek.valueOf(String.valueOf(d).toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid weekday: " + d);
            }
        }
        LocalTime onTime = parseTime(on);
        LocalTime offTime = parseTime(off);
        if (onTime.equals(offTime)) {
            throw new IllegalArgumentException("On and off times must differ");
        }
        return new Schedule(enabled, parsedDays.stream().map(Enum::name).toList(),
                onTime.toString(), offTime.toString());
    }

    public Set<DayOfWeek> daySet() {
        Set<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        days.forEach(d -> set.add(DayOfWeek.valueOf(d)));
        return set;
    }

    public LocalTime onTime() {
        return LocalTime.parse(on);
    }

    public LocalTime offTime() {
        return LocalTime.parse(off);
    }

    /** True if the off time falls on the day after the on time (e.g. 22:00 -> 06:00). */
    public boolean overnight() {
        return offTime().isBefore(onTime());
    }

    /** True if {@code now} is inside an on-to-off window that started today or (if overnight) yesterday. */
    public boolean activeAt(LocalDateTime now) {
        for (int back = 0; back <= 1; back++) {
            LocalDate startDate = now.toLocalDate().minusDays(back);
            if (!daySet().contains(startDate.getDayOfWeek())) {
                continue;
            }
            LocalDateTime start = startDate.atTime(onTime());
            LocalDateTime end = startDate.plusDays(overnight() ? 1 : 0).atTime(offTime());
            if (!now.isBefore(start) && now.isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    private static LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid time (use HH:mm): " + s);
        }
    }
}

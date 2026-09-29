package org.example.features.lamp;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Checks the schedules every few seconds and switches the lamp when a schedule time is reached. */
public class Scheduler {
    private final ScheduleStore store;
    private final PowerManager lamp;
    private final ZoneId zone;
    private LocalDateTime lastProcessed;

    public Scheduler(ScheduleStore store, PowerManager lamp, ZoneId zone) {
        this.store = store;
        this.lamp = lamp;
        this.zone = zone;
    }

    /** True if the current time falls inside an enabled schedule window. */
    public static boolean scheduledOn(ScheduleStore store, ZoneId zone) {
        LocalDateTime now = LocalDateTime.now(zone);
        return store.get().stream().anyMatch(s -> s.enabled() && s.activeAt(now));
    }

    public void start() {
        lastProcessed = now();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::safeTick, 5, 5, TimeUnit.SECONDS);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            System.err.println("Scheduler error: " + e);
        }
    }

    // Processes every minute since the last tick so a slow tick never skips a schedule time.
    private void tick() throws Exception {
        LocalDateTime current = now();
        while (lastProcessed.isBefore(current)) {
            lastProcessed = lastProcessed.plusMinutes(1);
            fire(lastProcessed);
        }
    }

    private void fire(LocalDateTime minute) throws Exception {
        Boolean desired = null;
        for (Schedule s : store.get()) {
            if (!s.enabled()) {
                continue;
            }
            if (minute.toLocalTime().equals(s.onTime()) && s.daySet().contains(minute.getDayOfWeek())) {
                desired = true;
            } else if (minute.toLocalTime().equals(s.offTime())) {
                var startDay = s.overnight() ? minute.minusDays(1).getDayOfWeek() : minute.getDayOfWeek();
                if (s.daySet().contains(startDay)) {
                    desired = false;
                }
            }
        }
        if (desired != null && lamp.isLampDisconnected()) {
            return;
        }
        if (desired != null) {
            System.out.println("Schedule " + minute + ": lamp " + (desired ? "ON" : "OFF"));
            lamp.setLamp(desired);
        }
    }
}

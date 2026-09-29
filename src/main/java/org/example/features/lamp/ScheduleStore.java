package org.example.features.lamp;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Keeps the schedules in memory and persists them as JSON. */
public class ScheduleStore {
    private static final int MAX_SCHEDULES = 50;
    private static final Type LIST_TYPE = new TypeToken<List<Schedule>>() {}.getType();

    private final Gson gson = new Gson();
    private final Path file;
    private volatile List<Schedule> schedules;

    public ScheduleStore(Path file) throws IOException {
        this.file = file;
        this.schedules = load();
    }

    public List<Schedule> get() {
        return schedules;
    }

    public String toJson() {
        return gson.toJson(schedules);
    }

    /** Parses, validates and saves a new list of schedules; throws IllegalArgumentException on bad input. */
    public synchronized void replaceFromJson(String json) throws IOException {
        List<Schedule> parsed;
        try {
            parsed = gson.fromJson(json, LIST_TYPE);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        if (parsed == null || parsed.size() > MAX_SCHEDULES || parsed.contains(null)) {
            throw new IllegalArgumentException("Invalid schedule list");
        }
        List<Schedule> valid = parsed.stream().map(Schedule::validated).toList();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(valid), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        schedules = valid;
    }

    private List<Schedule> load() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<Schedule> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
            return loaded == null ? List.of() : loaded.stream().map(Schedule::validated).toList();
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid schedule file " + file + ": " + e.getMessage(), e);
        }
    }
}

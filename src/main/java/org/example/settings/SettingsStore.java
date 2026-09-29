package org.example.settings;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Keeps the settings in memory and persists them as JSON. */
public class SettingsStore {
    private final Gson gson = new Gson();
    private final Path file;
    private volatile Settings settings;

    public SettingsStore(Path file) throws IOException {
        this.file = file;
        this.settings = load();
    }

    public Settings get() {
        return settings;
    }

    public String toJson() {
        return gson.toJson(settings);
    }

    /** Parses, saves and applies new settings; throws IllegalArgumentException on bad input. */
    public synchronized void replaceFromJson(String json) throws IOException {
        Settings parsed;
        try {
            parsed = gson.fromJson(json, Settings.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid settings");
        }
        if (parsed.driveDisconnected() && settings != null) {
            parsed = new Settings(settings.drivePriority(), parsed.lampDisconnected(), true);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(parsed), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        settings = parsed;
    }

    private Settings load() throws IOException {
        if (!Files.exists(file)) {
            return new Settings(false, false, false);
        }
        try {
            Settings loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            return loaded == null ? new Settings(false, false, false) : loaded;
        } catch (JsonParseException e) {
            throw new IOException("Invalid settings file " + file + ": " + e.getMessage(), e);
        }
    }
}

package org.example.settings;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.example.api.Http;
import org.example.common.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The zotify options that can be changed from the web UI, each a drop-down of fixed choices.
 * Chosen values are kept in a JSON file and passed to zotify as command-line flags, which take
 * priority over zotify's own config.json. The empty choice means "leave it to zotify's config".
 */
public class ZotifySettings {
    private record Choice(String value, String label, Map<String, String> flags) {
    }

    private record Option(String id, String group, String label, String help, List<Choice> choices) {
    }

    public record ChoiceView(String value, String label) {
    }

    public record OptionView(String id, String group, String label, String help, String value,
                             List<ChoiceView> choices) {
    }

    private static final String DEFAULT_LABEL = "Default (zotify config)";
    private static final List<Option> OPTIONS = List.of(
            option("format", "Audio", "File type",
                    "Audio format of the saved files. \"Original\" keeps Spotify's Ogg Vorbis without re-encoding. "
                            + "AAC (fdk) needs an ffmpeg build with libfdk_aac, which Raspberry Pi OS does not ship.",
                    choice("copy", "Original (no conversion)", "--codec", "copy"),
                    choice("mp3", "MP3", "--codec", "mp3"),
                    choice("aac", "AAC (.m4a)", "--codec", "aac"),
                    choice("fdk_aac", "AAC via fdk (.m4a)", "--codec", "fdk_aac"),
                    choice("ogg", "Ogg", "--codec", "ogg"),
                    choice("opus", "Opus", "--codec", "opus"),
                    choice("vorbis", "Vorbis", "--codec", "vorbis")),
            option("quality", "Audio", "Source quality",
                    "Quality requested from Spotify. Very high (320 kbps) needs a Premium account; "
                            + "free accounts are limited to 160 kbps.",
                    choice("auto", "Auto (best available)", "--download-quality", "auto"),
                    choice("normal", "Normal (96 kbps)", "--download-quality", "normal"),
                    choice("high", "High (160 kbps)", "--download-quality", "high"),
                    choice("very_high", "Very high (320 kbps)", "--download-quality", "very_high")),
            option("bitrate", "Audio", "Bit rate",
                    "Bit rate used when converting to another file type. Not needed for \"Original\".",
                    choice("auto", "Auto (from quality)", "--bitrate", "auto"),
                    choice("96k", "96 kbps", "--bitrate", "96k"),
                    choice("128k", "128 kbps", "--bitrate", "128k"),
                    choice("160k", "160 kbps", "--bitrate", "160k"),
                    choice("192k", "192 kbps", "--bitrate", "192k"),
                    choice("256k", "256 kbps", "--bitrate", "256k"),
                    choice("320k", "320 kbps", "--bitrate", "320k")),

            option("naming", "Files", "Folders and file names",
                    "How downloads are organised inside the Music folder.",
                    choice("artist-album-title", "Artist / Album / Title",
                            "--output-single", "{artist}/{album}/{song_name}",
                            "--output-album", "{album_artist}/{album}/{song_name}",
                            "--output-ext-playlist", "{playlist}/{song_name}"),
                    choice("artist-album-number-title", "Artist / Album / 01 - Title",
                            "--output-single", "{artist}/{album}/{song_name}",
                            "--output-album", "{album_artist}/{album}/{album_num} - {song_name}",
                            "--output-ext-playlist", "{playlist}/{playlist_num} - {song_name}"),
                    choice("flat", "Single folder: Artist - Title",
                            "--output-single", "{artist} - {song_name}",
                            "--output-album", "{artist} - {song_name}",
                            "--output-ext-playlist", "{artist} - {song_name}")),
            yesNo("split-discs", "Files", "Separate folder per disc",
                    "Put each disc of a multi-disc album in its own sub-folder.", "--split-album-discs"),
            yesNo("album-art-file", "Files", "Save album art as an image",
                    "Also save the cover as a separate image file.", "--album-art-to-file"),
            yesNo("lyrics-file", "Files", "Save lyrics as .lrc file",
                    "Server downloads only: downloads to this device never include .lrc files.", "--lyrics-to-file"),
            yesNo("lyrics-tags", "Files", "Embed lyrics in the audio file", "", "--lyrics-to-metadata"),

            yesNo("genres", "Metadata", "Save genres in tags", "", "--md-save-genres"),
            yesNo("disc-track-totals", "Metadata", "Save track and disc totals in tags", "", "--md-disc-track-totals"),
            option("language", "Metadata", "Metadata language",
                    "Language used for titles and tags.",
                    choice("en", "English", "--language", "en"),
                    choice("pt", "Portuguese", "--language", "pt"),
                    choice("es", "Spanish", "--language", "es"),
                    choice("fr", "French", "--language", "fr"),
                    choice("de", "German", "--language", "de"),
                    choice("it", "Italian", "--language", "it"),
                    choice("nl", "Dutch", "--language", "nl")),

            yesNo("skip-existing", "Downloading", "Skip songs already in the folder",
                    "Do not download a song again when it is already in the destination folder.", "--skip-existing"),
            yesNo("skip-previous", "Downloading", "Skip previously downloaded songs",
                    "Server downloads only: use zotify's history of past downloads to skip songs, "
                            + "even ones you have since deleted.", "--skip-prev-downloaded"),
            option("bulk-wait", "Downloading", "Wait between tracks",
                    "Pause between tracks in an album or playlist. Around 30 seconds avoids Spotify rate limits on big batches.",
                    choice("0", "None", "--bulk-wait-time", "0"),
                    choice("1", "1 second", "--bulk-wait-time", "1"),
                    choice("5", "5 seconds", "--bulk-wait-time", "5"),
                    choice("10", "10 seconds", "--bulk-wait-time", "10"),
                    choice("30", "30 seconds", "--bulk-wait-time", "30")),
            option("rate-limit", "Downloading", "Download speed",
                    "Slows downloads towards real-time listening speed to look less suspicious to Spotify.",
                    choice("0", "Full speed", "--download-rate-limiter", "0"),
                    choice("0.25", "Quarter of real time", "--download-rate-limiter", "0.25"),
                    choice("0.5", "Half of real time", "--download-rate-limiter", "0.5"),
                    choice("1", "Real time", "--download-rate-limiter", "1")),
            option("retries", "Downloading", "Retry failed requests",
                    "How many times a failed request to Spotify is retried.",
                    choice("0", "Never", "--retry-attempts", "0"),
                    choice("1", "Once", "--retry-attempts", "1"),
                    choice("3", "3 times", "--retry-attempts", "3"),
                    choice("5", "5 times", "--retry-attempts", "5")),
            yesNo("optimized", "Downloading", "Shortest tracks first",
                    "Sort a batch by duration to reduce Spotify rate limiting.", "--optimized-downloading"));

    private final Path file;
    private volatile Map<String, String> values;

    public ZotifySettings(Path file) throws IOException {
        this.file = file;
        this.values = load();
    }

    public List<OptionView> view() {
        Map<String, String> current = values;
        List<OptionView> views = new ArrayList<>();
        for (Option o : OPTIONS) {
            views.add(new OptionView(o.id(), o.group(), o.label(), o.help(), current.getOrDefault(o.id(), ""),
                    o.choices().stream().map(c -> new ChoiceView(c.value(), c.label())).toList()));
        }
        return views;
    }

    /** The zotify flags (flag to value) for the chosen settings. */
    public Map<String, String> flags() {
        Map<String, String> current = values;
        Map<String, String> flags = new LinkedHashMap<>();
        for (Option o : OPTIONS) {
            String value = current.get(o.id());
            if (value != null) {
                o.choices().stream().filter(c -> c.value().equals(value)).findFirst()
                        .ifPresent(c -> flags.putAll(c.flags()));
            }
        }
        return flags;
    }

    /** Validates and saves changed settings; ids that are not mentioned keep their value. */
    public synchronized void update(String json) throws ApiException, IOException {
        Map<String, String> changes;
        try {
            changes = Http.GSON.fromJson(json, new TypeToken<Map<String, String>>() { }.getType());
        } catch (JsonParseException e) {
            throw new ApiException(400, "Invalid request");
        }
        if (changes == null) {
            throw new ApiException(400, "Missing settings");
        }
        Map<String, String> next = new LinkedHashMap<>(values);
        for (var change : changes.entrySet()) {
            Option option = OPTIONS.stream().filter(o -> o.id().equals(change.getKey())).findFirst()
                    .orElseThrow(() -> new ApiException(400, "Unknown setting '" + change.getKey() + "'"));
            String value = change.getValue();
            if (value == null || !isValid(option, value)) {
                throw new ApiException(400, "Invalid value for " + option.label());
            }
            if (value.isEmpty()) {
                next.remove(option.id());
            } else {
                next.put(option.id(), value);
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Http.GSON.toJson(next), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        values = Map.copyOf(next);
    }

    private static boolean isValid(Option option, String value) {
        return value.isEmpty() || option.choices().stream().anyMatch(c -> c.value().equals(value));
    }

    private Map<String, String> load() throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        Map<String, String> loaded;
        try {
            loaded = Http.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
        } catch (JsonParseException e) {
            throw new IOException("Invalid zotify settings file " + file + ": " + e.getMessage(), e);
        }
        Map<String, String> valid = new LinkedHashMap<>();
        if (loaded != null) {
            for (Option o : OPTIONS) {
                String value = loaded.get(o.id());
                if (value != null && !value.isEmpty() && isValid(o, value)) {
                    valid.put(o.id(), value);
                }
            }
        }
        return Map.copyOf(valid);
    }

    /** flagsAndValues alternate: flag, value, flag, value... */
    private static Choice choice(String value, String label, String... flagsAndValues) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < flagsAndValues.length; i += 2) {
            flags.put(flagsAndValues[i], flagsAndValues[i + 1]);
        }
        return new Choice(value, label, Map.copyOf(flags));
    }

    private static Option option(String id, String group, String label, String help, Choice... choices) {
        List<Choice> all = new ArrayList<>();
        all.add(new Choice("", DEFAULT_LABEL, Map.of()));
        all.addAll(List.of(choices));
        return new Option(id, group, label, help, List.copyOf(all));
    }

    private static Option yesNo(String id, String group, String label, String help, String flag) {
        return option(id, group, label, help,
                choice("true", "Yes", flag, "True"),
                choice("false", "No", flag, "False"));
    }
}

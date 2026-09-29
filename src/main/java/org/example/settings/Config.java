package org.example.settings;

import java.util.List;

/**
 * hubs: uhubctl hub locations to switch. The Pi 5 ports are ganged across hubs 2 and 4 (sometimes 3 and 5).
 * driveDevice: block device of the external drive; empty means auto-detect the first USB (/dev/sd*) filesystem.
 * driveIdleMinutes: eject the drive after this long without file activity; 0 disables.
 * zotifyCmd: command that runs zotify (may contain arguments, e.g. "python3 -m zotify").
 * zotifyConfig: optional zotify config.json (or directory); empty uses zotify's default.
 * zotifyTempDir: scratch space for downloads sent to the browser; keep it on the SD card, not in RAM.
 * zotifyMusicDir / zotifyPodcastDir: folders on the drive that server downloads are saved into.
 * zotifySettingsFile: where the zotify options chosen in the web UI are stored.
 * streamingDir: folder on the drive whose videos the Media tab lists and streams.
 */
public record Config(List<String> hubs, String uhubctl, int httpPort,
                     String scheduleFile, String settingsFile, String timezone,
                     String driveDevice, String driveMount, String mountCmd, String umountCmd,
                     String lsblkCmd, String mountsFile, int driveTimeoutSeconds, int driveIdleMinutes,
                     String zotifyCmd, String zotifyConfig, String zotifyTempDir,
                     String zotifyMusicDir, String zotifyPodcastDir, int zotifyTimeoutMinutes,
                     String zotifySettingsFile, String streamingDir) {

    public static Config fromEnv() {
        return new Config(
                List.of(env("DANDELION_HUBS", "2,4").split("\\s*,\\s*")),
                env("UHUBCTL", "uhubctl"),
                Integer.parseInt(env("HTTP_PORT", "8080")),
                env("DANDELION_SCHEDULE_FILE", "schedules.json"),
                env("SETTINGS_FILE", "settings.json"),
                env("DANDELION_TZ", java.time.ZoneId.systemDefault().getId()),
                env("DRIVE_DEVICE", ""),
                env("DRIVE_MOUNT", "/mnt/usbdrive"),
                env("MOUNT_CMD", "mount"),
                env("UMOUNT_CMD", "umount"),
                env("LSBLK_CMD", "lsblk"),
                env("MOUNTS_FILE", "/proc/mounts"),
                Integer.parseInt(env("DRIVE_TIMEOUT_SECONDS", "45")),
                Integer.parseInt(env("DRIVE_IDLE_MINUTES", "30")),
                env("ZOTIFY_CMD", "zotify"),
                env("ZOTIFY_CONFIG", ""),
                env("ZOTIFY_TEMP_DIR", "/var/tmp/Dandelion1-zotify"),
                env("ZOTIFY_MUSIC_DIR", "Music"),
                env("ZOTIFY_PODCAST_DIR", "Podcasts"),
                Integer.parseInt(env("ZOTIFY_TIMEOUT_MINUTES", "60")),
                env("ZOTIFY_SETTINGS_FILE", "zotify-settings.json"),
                env("STREAMING_DIR", "Streaming"));
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}

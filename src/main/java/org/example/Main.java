package org.example;

import org.example.api.DriveApi;
import org.example.api.LampServer;
import org.example.api.MusicApi;
import org.example.common.LampException;
import org.example.features.drive.DriveMounter;
import org.example.features.drive.DriveService;
import org.example.features.lamp.PowerManager;
import org.example.features.lamp.ScheduleStore;
import org.example.features.lamp.Scheduler;
import org.example.features.lamp.UsbPower;
import org.example.features.music.ZotifyService;
import org.example.settings.Config;
import org.example.settings.SettingsStore;
import org.example.settings.ZotifySettings;

import java.nio.file.Path;
import java.time.ZoneId;

public class Main {
    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        ZoneId zone = ZoneId.of(config.timezone());
        ScheduleStore schedules = new ScheduleStore(Path.of(config.scheduleFile()));
        SettingsStore settings = new SettingsStore(Path.of(config.settingsFile()));
        PowerManager power = new PowerManager(new UsbPower(config), settings);
        DriveService drive = new DriveService(config, new DriveMounter(config), power);

        // USB power starts on at boot; bring it to the wanted state (off unless the lamp is scheduled or the drive is mounted).
        boolean driveMounted = drive.resumeIfMounted();
        try {
            power.init(Scheduler.scheduledOn(schedules, zone), driveMounted);
        } catch (LampException e) {
            System.err.println("Could not set initial USB power: " + e.getMessage());
        }
        drive.startIdleWatcher();
        ZotifySettings zotifySettings = new ZotifySettings(Path.of(config.zotifySettingsFile()));
        ZotifyService zotify = new ZotifyService(config, drive, zotifySettings);
        zotify.start();

        new Scheduler(schedules, power, zone).start();
        new LampServer(config, power, schedules, settings, new DriveApi(drive), new MusicApi(zotify, zotifySettings)).start();
        System.out.println("Lamp server listening on port " + config.httpPort()
                + " (USB hubs " + String.join(", ", config.hubs()) + ")");
        System.out.println("Schedule timezone: " + config.timezone());
    }
}

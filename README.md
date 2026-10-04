# Dandelion

Web UI running on the Raspberry Pi: lamp schedule, external drive file manager, and a **Zotify** tab that downloads
Spotify links with [zotify](https://github.com/Googolplexed0/zotify).

## Running Dandelion on a Raspberry Pi (step by step)

Run everything on the Pi over SSH. The steps assume Raspberry Pi OS (64-bit) and the user `pi`; replace it with your own
username if it differs.

### 1. Install the system packages

```bash
sudo apt update
sudo apt install -y git maven ffmpeg pipx uhubctl python3 curl zip unzip
pipx ensurepath   # then log out and back in
```

### 2. Install Java 25

`pom.xml` targets Java 25 (`maven.compiler.source`), which is newer than what Raspberry Pi OS ships in apt.
The easiest way is [SDKMAN](https://sdkman.io). The `sdk` command only exists after SDKMAN is installed
(`sdk: command not found` means this step was skipped):

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk version                  # confirm it works
sdk list java | grep -i tem  # find the exact Temurin 25 identifier
sdk install java 25.0.1-tem  # use the identifier from the list above
java -version
```

`25.0.1-tem` is only an example; use whichever `25.x-tem` version the list shows.

Alternatives:

- `sudo apt install -y openjdk-25-jdk`, only if your Pi OS release has it (`apt search openjdk-25`).
- Download the Linux aarch64 JDK 25 tarball from [adoptium.net](https://adoptium.net), extract it to `/opt/jdk-25`
  and use `/opt/jdk-25/bin/java`.

Remember which `java` you ended up with; step 7 needs its full path (`which java`).

### 3. Get the code and build the jar

```bash
git clone https://github.com/franciscopeixoto003/Dandelion.git ~/Dandelion
cd ~/Dandelion
mvn package          # produces target/Dandelion.jar
```

If you copied the folder over instead of cloning, still run `mvn package` on the Pi. Do not reuse a `target/` folder
built on another machine.

### 4. Install zotify and log in once

Only needed for the Zotify tab. Run it as the same user that will run the server. Follow
[Installing zotify on the Raspberry Pi](#installing-zotify-on-the-raspberry-pi) below, in short:

```bash
pipx install git+https://github.com/Googolplexed0/zotify.git
```

Then, from your computer, open a tunnel for the login redirect:

```bash
ssh -L 4381:127.0.0.1:4381 pi@<pi-address>
```

In that session run `zotify https://open.spotify.com/track/<any-id>`, open the login URL it prints in your computer's
browser and approve it. Run the same command again to confirm it no longer asks you to log in.

### 5. Check the hardware (lamp and drive)

- Run `sudo uhubctl` and confirm it lists the hubs. The default is `DANDELION_HUBS=2,4`. On the Pi 5 the ports can be
  ganged across hubs 3 and 5 instead; if that is what `uhubctl` shows, set `DANDELION_HUBS=3,5`.
- Plug in the external drive. Set `DRIVE_DEVICE` if auto-detect picks the wrong device.
- Create the mount point (default `/mnt/usbdrive`): `sudo mkdir -p /mnt/usbdrive`.
- The server must be able to run `uhubctl`, `mount` and `umount`. Either run it as root or allow those commands
  without a password (sudoers).

### 6. Do a test run

```bash
cd ~/Dandelion
java -jar target/Dandelion.jar
```

Open `http://<pi-address>:8080` from another device. Do not use `dev/run-simulated.sh` on the Pi; it uses fake hardware.

### 7. Run it as a service so it starts on boot

Create `/etc/systemd/system/dandelion.service`:

```ini
[Unit]
Description=Dandelion
After=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/Dandelion
ExecStart=/usr/bin/java -jar /home/pi/Dandelion/target/Dandelion.jar
Environment=ZOTIFY_CMD=/home/pi/.local/bin/zotify
Environment=ZOTIFY_TEMP_DIR=/var/tmp/Dandelion-zotify
# Environment=DANDELION_HUBS=3,5
# Environment=HTTP_PORT=8080
Restart=always

[Install]
WantedBy=multi-user.target
```

Set `ExecStart` to the full path of your Java 25 binary if it is not `/usr/bin/java`. With SDKMAN that is
`/home/pi/.sdkman/candidates/java/current/bin/java` (use your own home path if the user is not `pi`). Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now dandelion
journalctl -u dandelion -f    # view logs
```

The service user needs permission to run `uhubctl`, `mount` and `umount`, otherwise USB power and the drive will not
work. Either set `User=root` (then zotify must be installed and logged in as root too), or set up sudoers and point
`UHUBCTL`, `MOUNT_CMD` and `UMOUNT_CMD` at sudo wrappers. See the environment variable table in
[step 4 of the zotify section](#4-configure-the-dandelion-server) for all options, and
[Troubleshooting](#troubleshooting) for common errors.

## Zotify tab

Paste a Spotify link (track, album, playlist or episode) and pick where it goes:

| Button | What happens |
| --- | --- |
| **Download to Drive** | The Pi powers on and mounts the external drive if needed, and zotify saves into `<drive>/Music` (podcasts into `<drive>/Podcasts`). |
| **Download to this device** | zotify downloads to a scratch folder on the Pi, then your browser receives the file (a single track as-is, anything larger as a `.zip`). The scratch copy is deleted after 30 minutes and on restart. |

Only one download runs at a time. While a server download runs, the drive cannot be ejected (manually or by the idle timer).

zotify is **not** bundled: it has to be installed on the Pi (below). It is not needed on the development machine;
`dev/run-simulated.sh` uses `dev/fake-zotify.sh` instead.

## Settings tab

The **Settings** tab has two sub-tabs. **Drive** holds the drive priority switch. **Zotify** has a drop-down for each option: file type, source quality, bit rate, folder/file naming,
disc folders, album art, lyrics, tags, language, skipping rules, wait time between tracks, download speed and retries.
Changes save as soon as you pick a value and apply to the next download.

- **Default** (the first entry of every list) passes nothing to zotify, so its own `config.json` decides.
- Chosen values are stored in `zotify-settings.json` (see `ZOTIFY_SETTINGS_FILE`) and handed to zotify as command-line
  flags, which zotify gives priority over `config.json`. The Pi's zotify config file is never edited.
- Downloads to this device ignore *Save lyrics as .lrc file* and *Skip previously downloaded songs*, so a track always
  arrives as a single file.
- Bit rate only matters when converting (any file type other than *Original*). Very high quality needs Premium.

## Installing zotify on the Raspberry Pi

Run everything below **on the Pi** (over SSH), as the **same user that runs the Dandelion server**. zotify stores its
login under that user's home, so a different user (or `root`) will not find it. Check with `systemctl cat <your-service>`.

### 1. Requirements

zotify needs Python 3.10+ (Raspberry Pi OS Bookworm ships 3.11), FFmpeg and git.

```bash
sudo apt update
sudo apt install -y python3 pipx ffmpeg git
pipx ensurepath
```

Log out and back in so `pipx` puts `~/.local/bin` on your `PATH`.

### 2. Install

```bash
pipx install git+https://github.com/Googolplexed0/zotify.git
zotify --version
```

Update later with `pipx install -f git+https://github.com/Googolplexed0/zotify.git`.

### 3. Log in once

zotify logs in through a browser link and then saves credentials (`~/.local/share/zotify/credentials.json`).
The Pi has no browser, so forward zotify's login port to your computer.

On **your computer**:

```bash
ssh -L 4381:127.0.0.1:4381 <user>@<pi-address>
```

In that SSH session on the Pi, run any download (it will ask for login because nothing is saved yet):

```bash
zotify https://open.spotify.com/track/<any-track-id>
```

Open the `Click on the following link to login` URL in your computer's browser and approve. The browser is redirected
to `127.0.0.1:4381`, which the tunnel carries to the Pi. When zotify finishes, run it again once to confirm it now
starts without asking to log in.

If the server later reports *"Zotify is not logged in"*, repeat this step. To log out, delete the `config.json` and
`credentials.json` files zotify created. A Premium account gives better quality; free accounts are limited to 160 kbps.
Zotify's own README recommends a secondary account.

### 4. Configure the Dandelion server

The server reads these environment variables (all optional):

| Variable | Default | Meaning |
| --- | --- | --- |
| `DANDELION_SCHEDULE_FILE` | `assets/schedules.json` | Where lamp schedules are stored (relative to the server's working directory). |
| `SETTINGS_FILE` | `assets/settings.json` | Where lamp and drive settings are stored (relative to the server's working directory). |
| `ZOTIFY_CMD` | `zotify` | How to run zotify. When started by systemd `PATH` usually lacks `~/.local/bin`, so use the full path, e.g. `/home/pi/.local/bin/zotify`. |
| `ZOTIFY_CONFIG` | *(zotify default)* | Path to a zotify `config.json` (or its folder), passed as `-c`. |
| `ZOTIFY_TEMP_DIR` | `/var/tmp/Dandelion-zotify` | Scratch space for "download to this device". Keep it on the SD card: `/tmp` can be RAM-backed and albums are big. |
| `ZOTIFY_MUSIC_DIR` | `Music` | Folder on the drive for music. |
| `ZOTIFY_PODCAST_DIR` | `Podcasts` | Folder on the drive for podcasts. |
| `ZOTIFY_TIMEOUT_MINUTES` | `60` | A download running longer than this is stopped. |
| `ZOTIFY_SETTINGS_FILE` | `zotify-settings.json` | Where the choices made in Settings > Zotify are stored (relative to the server's working directory). |

Set them in the service unit, for example:

```ini
[Service]
User=pi
Environment=ZOTIFY_CMD=/home/pi/.local/bin/zotify
Environment=ZOTIFY_TEMP_DIR=/var/tmp/Dandelion-zotify
```

`ffmpeg` must also be on the service's `PATH` (`/usr/bin/ffmpeg` from apt is). Then restart the service:

```bash
sudo systemctl daemon-reload
sudo systemctl restart <your-service>
```

The temp directory is created automatically and must be writable by the service user.

### 5. Quick check

```bash
ZOTIFY_CMD=$(which zotify)
"$ZOTIFY_CMD" --root-path /tmp/zotify-test https://open.spotify.com/track/<track-id>
ls -R /tmp/zotify-test && rm -r /tmp/zotify-test
```

Then open the web UI, go to **Zotify**, paste a link and try both buttons.

## Troubleshooting

| Message | Cause / fix |
| --- | --- |
| `Cannot run 'zotify'. Is zotify installed?` | Wrong `PATH` for the service: set `ZOTIFY_CMD` to the full path (`which zotify`). |
| `Zotify is not logged in on the Pi` | Log in again (step 3) as the service user. |
| `Zotify failed (exit N): ...` | The last line zotify printed is shown. Run the same command by hand for full output. |
| `Finished, but nothing new was saved` | The track already exists on the drive, or Spotify does not offer it. |
| `The drive is not open` / mount errors | Same as the Drive tab: check the drive and `DRIVE_DEVICE` / `DRIVE_MOUNT`. |
| Repeated `Failed fetching audio key!` in zotify | Spotify rate limiting on big batches; see the zotify README (`BULK_WAIT_TIME`, `DOWNLOAD_RATE_LIMITER`). |

Settings that are not in Settings > Zotify can still be changed in zotify's own `config.json`. The server always overrides
the output folders, plus whatever is chosen in Settings > Zotify.

## Security notes

- The UI has no login. Keep it on your home network; do not expose it to the internet.
- Only `https://open.spotify.com/{track,album,playlist,episode}/<id>` links are accepted and zotify is started without a shell,
  so the pasted text can never become a command.
- Downloading may violate Spotify's terms of service. Use it for content you are entitled to and at your own risk.

## Code layout

```
src/main/java/org/example/
  Main.java              starts everything
  api/                   HTTP layer: LampServer (routes + static files), DriveApi, MusicApi, Http helpers
  settings/              Config (environment), Settings/SettingsStore (app settings), ZotifySettings (zotify drop-downs)
  common/                LampException, ApiException, Shell (run a command)
  features/
    lamp/                PowerManager, UsbPower, Schedule, ScheduleStore, Scheduler
    drive/               DriveService, DriveMounter
    music/               ZotifyService
src/main/resources/web/  the web UI (index.html, css/, js/)
dev/                     fake uhubctl/mount/zotify for running without a Pi
```

## Local development (no Pi needed)

```bash
mvn package
dev/run-simulated.sh   # http://localhost:8080 with fake USB power, fake drive and fake zotify
```

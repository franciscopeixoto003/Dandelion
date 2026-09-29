package org.example.features.drive;

import org.example.common.LampException;
import org.example.common.Shell;
import org.example.settings.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds, mounts and unmounts the external drive. */
public class DriveMounter {
    private static final Set<String> NON_DATA_FS = Set.of("swap", "LVM2_member", "crypto_LUKS", "linux_raid_member");

    private final Config config;
    private final Path mountDir;

    public DriveMounter(Config config) {
        this.config = config;
        this.mountDir = Path.of(config.driveMount());
    }

    /** The mount point of the drive if it is already mounted (e.g. the app restarted while it was open). */
    public Optional<Path> currentMount() throws LampException {
        Optional<Path> device = findDevice();
        if (device.isPresent()) {
            Optional<Path> mounted = mountPointOf(device.get());
            if (mounted.isPresent()) {
                return mounted;
            }
        }
        return isMounted(mountDir) ? Optional.of(mountDir) : Optional.empty();
    }

    /** Waits for the drive to show up after power-on, then mounts it (or reuses an existing mount). */
    public Path waitAndMount() throws LampException, InterruptedException {
        Instant deadline = Instant.now().plusSeconds(config.driveTimeoutSeconds());
        LampException lastError = null;
        while (true) {
            Optional<Path> device = findDevice();
            if (device.isPresent()) {
                Optional<Path> existing = mountPointOf(device.get());
                if (existing.isPresent()) {
                    return existing.get();
                }
                try {
                    return mount(device.get());
                } catch (LampException e) {
                    lastError = e;
                }
            }
            if (Instant.now().isAfter(deadline)) {
                throw lastError != null ? lastError : new LampException(
                        "No USB drive detected after " + config.driveTimeoutSeconds() + " seconds. Is it plugged in?");
            }
            Thread.sleep(1000);
        }
    }

    /** Flushes and unmounts; throws if the drive is still in use so power is never cut under a mounted drive. */
    public void unmount(Path mountPoint) throws LampException, InterruptedException {
        if (!isMounted(mountPoint)) {
            return;
        }
        Shell.run(List.of("sync"), 60);
        for (int attempt = 1; attempt <= 3; attempt++) {
            Shell.Result r = Shell.run(List.of(config.umountCmd(), mountPoint.toString()), 30);
            if (r.ok() || !isMounted(mountPoint)) {
                return;
            }
            System.err.println("Unmount attempt " + attempt + " failed: " + r.firstLine());
            Thread.sleep(2000);
        }
        throw new LampException("Could not eject the drive because it is still in use. USB power was left on.");
    }

    private Path mount(Path device) throws LampException, InterruptedException {
        try {
            Files.createDirectories(mountDir);
        } catch (IOException e) {
            throw new LampException("Cannot create mount folder " + mountDir, e);
        }
        Shell.Result r = Shell.run(List.of(config.mountCmd(), "-o", "nosuid,nodev,noexec,noatime",
                device.toString(), mountDir.toString()), 30);
        if (!r.ok()) {
            System.err.println("Mount failed: " + r.output().strip());
            String lower = r.output().toLowerCase();
            if (lower.contains("permission") || lower.contains("only root")) {
                throw new LampException("Permission denied. Run the app with sudo.");
            }
            throw new LampException("Could not mount the drive: " + r.firstLine());
        }
        return mountDir;
    }

    private Optional<Path> findDevice() throws LampException {
        if (!config.driveDevice().isBlank()) {
            try {
                Path device = Path.of(config.driveDevice());
                return Files.exists(device) ? Optional.of(device.toRealPath()) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        Shell.Result r;
        try {
            r = Shell.run(List.of(config.lsblkCmd(), "-rpno", "PATH,TYPE,FSTYPE"), 10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LampException("Interrupted while looking for the drive");
        }
        if (!r.ok()) {
            throw new LampException("Could not list drives with lsblk");
        }
        String firstDisk = null;
        for (String line : r.output().split("\n")) {
            String[] f = line.trim().split(" ", -1);
            if (f.length < 3 || !f[0].startsWith("/dev/sd") || f[2].isEmpty() || NON_DATA_FS.contains(f[2])) {
                continue;
            }
            if (f[1].equals("part")) {
                return Optional.of(Path.of(f[0]));
            }
            if (f[1].equals("disk") && firstDisk == null) {
                firstDisk = f[0];
            }
        }
        return Optional.ofNullable(firstDisk).map(Path::of);
    }

    private Optional<Path> mountPointOf(Path device) {
        for (String[] entry : mounts()) {
            if (entry[0].startsWith("/") && realOrSame(Path.of(entry[0])).equals(device)) {
                return Optional.of(Path.of(entry[1]));
            }
        }
        return Optional.empty();
    }

    private boolean isMounted(Path mountPoint) {
        return mounts().stream().anyMatch(e -> Path.of(e[1]).equals(mountPoint));
    }

    private List<String[]> mounts() {
        List<String[]> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Path.of(config.mountsFile()), StandardCharsets.UTF_8)) {
                String[] f = line.split(" ");
                if (f.length >= 2) {
                    result.add(new String[]{unescape(f[0]), unescape(f[1])});
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + config.mountsFile() + ": " + e);
        }
        return result;
    }

    // /proc/mounts escapes spaces and other special characters as octal, e.g. "\040".
    private static String unescape(String s) {
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 4 <= s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private static Path realOrSame(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }
}

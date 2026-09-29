package org.example.features.music;

import org.example.common.ApiException;
import org.example.common.LampException;
import org.example.features.drive.DriveService;
import org.example.settings.Config;
import org.example.settings.ZotifySettings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Runs zotify for one Spotify link at a time. Server jobs save straight onto the external drive;
 * device jobs download into a scratch folder so the browser can fetch the result afterwards.
 */
public class ZotifyService {
    public enum Target { SERVER, DEVICE }

    public enum State { RUNNING, DONE, FAILED }

    public record JobView(String id, Target target, String url, State state, String message,
                          String progress, String fileName, int fileCount) {
    }

    /** What the browser receives for a finished device job: one file, or several to be zipped. */
    public record DeviceFile(Path base, List<Path> files, String name, boolean zip) {
    }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final Target target;
        final String url;
        volatile State state = State.RUNNING;
        volatile String message = "Starting...";
        volatile String progress = "";
        volatile Path dir;
        volatile List<Path> files = List.of();
        volatile String fileName = "";
        volatile int fileCount;
        volatile Instant finished;

        Job(Target target, String url) {
            this.target = target;
            this.url = url;
        }

        JobView view() {
            return new JobView(id, target, url, state, message, progress, fileName, fileCount);
        }
    }

    private static final Pattern SPOTIFY_URL = Pattern.compile(
            "https://open\\.spotify\\.com/(?:intl-[a-z]{2,5}/)?(track|album|playlist|episode)/([A-Za-z0-9]{22})"
                    + "(?:\\?[^\\s#]*)?(?:#\\S*)?");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Duration DEVICE_FILE_TTL = Duration.ofMinutes(30);
    private static final Duration JOB_TTL = Duration.ofHours(1);
    private static final int MAX_LINE = 200;
    private static final int TAIL_LINES = 8;

    private final Config config;
    private final DriveService drive;
    private final ZotifySettings settings;
    private final Path tempBase;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private Job active;

    public ZotifyService(Config config, DriveService drive, ZotifySettings settings) {
        this.config = config;
        this.drive = drive;
        this.settings = settings;
        this.tempBase = Path.of(config.zotifyTempDir()).toAbsolutePath().normalize();
    }

    /** Clears scratch files left by a previous run and starts the periodic cleanup. */
    public void start() {
        if (Files.isDirectory(tempBase)) {
            // Only our own job folders: the configured directory might be shared with something else
            try (var leftovers = Files.list(tempBase)) {
                leftovers.filter(p -> UUID_PATTERN.matcher(p.getFileName().toString()).matches())
                        .forEach(ZotifyService::deleteTree);
            } catch (IOException e) {
                System.err.println("Could not clean " + tempBase + ": " + e.getMessage());
            }
        }
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zotify-cleanup");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    /** Returns the canonical link for a Spotify track/album/playlist/episode URL, or throws a 400. */
    static String normalizeUrl(String raw) throws ApiException {
        var m = SPOTIFY_URL.matcher(raw == null ? "" : raw.strip());
        if (!m.matches()) {
            throw new ApiException(400, "Paste a Spotify track, album, playlist or episode link "
                    + "(https://open.spotify.com/track/...)");
        }
        return "https://open.spotify.com/" + m.group(1) + "/" + m.group(2);
    }

    public JobView submit(String rawUrl, Target target) throws ApiException {
        String url = normalizeUrl(rawUrl);
        Job job = new Job(target, url);
        synchronized (this) {
            if (active != null) {
                throw new ApiException(409, "A download is already running. Wait for it to finish.");
            }
            active = job;
        }
        jobs.put(job.id, job);
        Thread.ofPlatform().daemon().name("zotify-" + job.id).start(() -> run(job));
        return job.view();
    }

    public JobView get(String id) throws ApiException {
        return find(id).view();
    }

    /** The running job, if any, so a reloaded page can pick it up again. */
    public synchronized JobView current() {
        return active == null ? null : active.view();
    }

    public DeviceFile deviceFile(String id) throws ApiException {
        Job job = find(id);
        if (job.target != Target.DEVICE) {
            throw new ApiException(400, "This download was saved to the server");
        }
        if (job.state == State.RUNNING) {
            throw new ApiException(409, "The download is not finished yet");
        }
        if (job.state == State.FAILED) {
            throw new ApiException(409, "The download failed");
        }
        List<Path> files = job.files;
        if (files.isEmpty() || !Files.isRegularFile(files.get(0))) {
            throw new ApiException(410, "The file is no longer available. Download it again.");
        }
        return new DeviceFile(job.dir, files, job.fileName, files.size() > 1);
    }

    private Job find(String id) throws ApiException {
        Job job = UUID_PATTERN.matcher(id).matches() ? jobs.get(id) : null;
        if (job == null) {
            throw new ApiException(404, "Unknown download");
        }
        return job;
    }

    private void run(Job job) {
        try {
            if (job.target == Target.SERVER) {
                runServer(job);
            } else {
                runDevice(job);
            }
        } catch (LampException e) {
            System.err.println("Zotify error: " + e.getMessage() + (e.getCause() != null ? " (" + e.getCause() + ")" : ""));
            finish(job, State.FAILED, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(job, State.FAILED, "Interrupted");
        } catch (Exception e) {
            System.err.println("Zotify error: " + e);
            finish(job, State.FAILED, "Download failed unexpectedly");
        }
    }

    private void runServer(Job job) throws Exception {
        job.message = "Powering on the drive...";
        drive.open();
        try (var lease = drive.lease()) {
            Path music = driveFolder(lease.root(), config.zotifyMusicDir());
            Path podcasts = driveFolder(lease.root(), config.zotifyPodcastDir());
            Files.createDirectories(music);
            Files.createDirectories(podcasts);

            job.message = "Downloading to the drive...";
            long started = System.currentTimeMillis();
            String lastLine = runZotify(job, music, podcasts, Map.of());

            // 3s of slack: FAT/exFAT drives round modification times to 2s
            long since = started - 3000;
            int saved = countNewFiles(music, since) + countNewFiles(podcasts, since);
            job.fileCount = saved;
            finish(job, State.DONE, saved == 0
                    ? "Finished, but nothing new was saved (already on the drive, or not available)."
                    + (lastLine.isEmpty() ? "" : " " + lastLine)
                    : "Saved " + saved + " file" + (saved == 1 ? "" : "s") + " to the drive.");
        }
    }

    private void runDevice(Job job) throws Exception {
        Path dir = tempBase.resolve(job.id);
        Files.createDirectories(dir);
        job.dir = dir;
        job.message = "Downloading...";
        // A fresh folder: a lone track must not come with a .lrc file, and the "already downloaded" history must not skip it
        String lastLine = runZotify(job, dir, dir.resolve("podcasts"),
                Map.of("--lyrics-to-file", "False", "--skip-prev-downloaded", "False"));

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return !d.equals(dir) && d.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !f.getFileName().toString().startsWith(".")) {
                    files.add(f);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (files.isEmpty()) {
            throw new LampException("Zotify finished but produced no file."
                    + (lastLine.isEmpty() ? "" : " " + lastLine));
        }
        files.sort(null);
        job.files = List.copyOf(files);
        job.fileCount = files.size();
        job.fileName = files.size() == 1 ? files.get(0).getFileName().toString() : zipName(dir, files, job.id);
        finish(job, State.DONE, "Ready");
    }

    private static String zipName(Path dir, List<Path> files, String id) {
        String first = dir.relativize(files.get(0)).getName(0).toString();
        boolean sameTop = files.stream().allMatch(f -> dir.relativize(f).getName(0).toString().equals(first));
        return (sameTop && dir.relativize(files.get(0)).getNameCount() > 1 ? first : "zotify-" + id.substring(0, 8)) + ".zip";
    }

    /** Runs zotify and returns its last line of output; throws if it fails, times out or is not logged in. */
    private String runZotify(Job job, Path root, Path podcastRoot, Map<String, String> overrides)
            throws LampException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(config.zotifyCmd().strip().split("\\s+")));
        if (!config.zotifyConfig().isBlank()) {
            cmd.addAll(List.of("-c", config.zotifyConfig()));
        }
        cmd.addAll(List.of("--root-path", root.toString(), "--root-podcast-path", podcastRoot.toString()));
        Map<String, String> flags = new LinkedHashMap<>(settings.flags());
        flags.putAll(overrides);
        flags.forEach((flag, value) -> cmd.addAll(List.of(flag, value)));
        cmd.add(job.url);

        var builder = new ProcessBuilder(cmd).redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.environment().put("PYTHONUNBUFFERED", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new LampException("Cannot run '" + cmd.get(0) + "'. Is zotify installed? (ZOTIFY_CMD)", e);
        }

        Deque<String> tail = new ArrayDeque<>();
        var needsLogin = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> pump(process, job, tail, needsLogin));
        try {
            if (!process.waitFor(config.zotifyTimeoutMinutes(), TimeUnit.MINUTES)) {
                kill(process);
                throw new LampException("Zotify took longer than " + config.zotifyTimeoutMinutes() + " minutes and was stopped");
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
        String last;
        synchronized (tail) {
            last = tail.peekLast() == null ? "" : tail.peekLast();
        }
        if (needsLogin.get()) {
            throw new LampException("Zotify is not logged in on the Pi. Log in once as described in the README.");
        }
        if (process.exitValue() != 0) {
            throw new LampException("Zotify failed (exit " + process.exitValue() + ")" + (last.isEmpty() ? "" : ": " + last));
        }
        return last;
    }

    private void pump(Process process, Job job, Deque<String> tail, AtomicBoolean needsLogin) {
        try (var in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n' || c == '\r') {
                    accept(line, job, tail, needsLogin, process);
                    line.setLength(0);
                } else if (line.length() < 4 * MAX_LINE) {
                    line.append((char) c);
                }
            }
            accept(line, job, tail, needsLogin, process);
        } catch (IOException ignored) {
            // process was killed; keep what we have
        }
    }

    private void accept(StringBuilder raw, Job job, Deque<String> tail, AtomicBoolean needsLogin, Process process) {
        String text = ANSI.matcher(raw).replaceAll("").strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_LINE) {
            text = text.substring(0, MAX_LINE);
        }
        job.progress = text;
        synchronized (tail) {
            if (!text.equals(tail.peekLast())) {
                tail.addLast(text);
                if (tail.size() > TAIL_LINES) {
                    tail.removeFirst();
                }
            }
        }
        // Zotify waits for an interactive browser login when it has no saved credentials; nobody can answer that here
        if (text.toLowerCase(Locale.ROOT).contains("link to login") && needsLogin.compareAndSet(false, true)) {
            kill(process);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static Path driveFolder(Path root, String folder) throws LampException {
        Path path = root.resolve(folder).normalize();
        if (folder.isBlank() || !path.startsWith(root) || path.equals(root)) {
            throw new LampException("Invalid zotify folder name '" + folder + "'");
        }
        return path;
    }

    private static int countNewFiles(Path dir, long sinceMillis) throws IOException {
        int[] count = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return !d.equals(dir) && d.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !f.getFileName().toString().startsWith(".")
                        && attrs.lastModifiedTime().toMillis() >= sinceMillis) {
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private void finish(Job job, State state, String message) {
        job.message = message;
        job.progress = "";
        job.finished = Instant.now();
        synchronized (this) {
            if (active == job) {
                active = null;
            }
        }
        if (state == State.FAILED && job.dir != null) {
            deleteTree(job.dir);
        }
        job.state = state;
    }

    private void cleanup() {
        Instant now = Instant.now();
        for (Job job : jobs.values()) {
            Instant finished = job.finished;
            if (finished == null) {
                continue;
            }
            Duration age = Duration.between(finished, now);
            if (job.dir != null && age.compareTo(DEVICE_FILE_TTL) > 0 && !job.files.isEmpty()) {
                job.files = List.of();
                deleteTree(job.dir);
            }
            if (age.compareTo(JOB_TTL) > 0) {
                jobs.remove(job.id);
                if (job.dir != null) {
                    deleteTree(job.dir);
                }
            }
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(f);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Could not clean " + dir + ": " + e.getMessage());
        }
    }
}

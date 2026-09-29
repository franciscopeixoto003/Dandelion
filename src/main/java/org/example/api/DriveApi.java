package org.example.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.common.ApiException;
import org.example.common.LampException;
import org.example.features.drive.DriveService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** HTTP API for opening/closing the drive and browsing, downloading and changing its files. */
public class DriveApi {
    private static final int MAX_NAME_LENGTH = 200;
    private static final Map<String, String> INLINE_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("bmp", "image/bmp"),
            Map.entry("mp4", "video/mp4"), Map.entry("m4v", "video/mp4"), Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"), Map.entry("mp3", "audio/mpeg"), Map.entry("m4a", "audio/mp4"),
            Map.entry("wav", "audio/wav"), Map.entry("ogg", "audio/ogg"), Map.entry("flac", "audio/flac"),
            Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain; charset=utf-8"));

    private interface Handler {
        void handle(HttpExchange ex, Map<String, String> query) throws Exception;
    }

    private record Entry(String name, boolean dir, long size, long modified) {
    }

    private final DriveService drive;

    public DriveApi(DriveService drive) {
        this.drive = drive;
    }

    public void register(HttpServer server) {
        route(server, "/api/drive/status", "GET", (ex, q) -> Http.json(ex, 200, drive.status()));
        route(server, "/api/drive/open", "POST", (ex, q) -> {
            drive.open();
            Http.json(ex, 200, drive.status());
        });
        route(server, "/api/drive/close", "POST", (ex, q) -> {
            drive.close();
            Http.json(ex, 200, drive.status());
        });
        route(server, "/api/files", null, (ex, q) -> {
            switch (ex.getRequestMethod()) {
                case "GET" -> list(ex, q);
                case "DELETE" -> delete(ex, q);
                default -> Http.error(ex, 405, "Method not allowed");
            }
        });
        route(server, "/api/files/download", "GET", this::download);
        route(server, "/api/files/upload", "POST", this::upload);
        route(server, "/api/files/mkdir", "POST", this::mkdir);
        route(server, "/api/files/rename", "POST", this::rename);
    }

    /** Runs the handler; state-changing requests need X-Requested-With so other websites cannot forge them. */
    private void route(HttpServer server, String path, String method, Handler handler) {
        server.createContext(path, ex -> {
            try {
                String requested = ex.getRequestMethod();
                if (method != null && !method.equals(requested)) {
                    Http.error(ex, 405, "Method not allowed");
                } else if (!"GET".equals(requested) && ex.getRequestHeaders().getFirst("X-Requested-With") == null) {
                    Http.error(ex, 403, "Missing X-Requested-With header");
                } else {
                    handler.handle(ex, Http.query(ex));
                }
            } catch (ApiException e) {
                fail(ex, e.status(), e.getMessage());
            } catch (LampException e) {
                System.err.println("Drive error: " + e.getMessage() + (e.getCause() != null ? " (" + e.getCause() + ")" : ""));
                fail(ex, 500, e.getMessage());
            } catch (NoSuchFileException e) {
                fail(ex, 404, "Not found");
            } catch (FileAlreadyExistsException e) {
                fail(ex, 409, "Already exists");
            } catch (InvalidPathException e) {
                fail(ex, 400, "Invalid path");
            } catch (IOException e) {
                System.err.println("File error: " + e);
                fail(ex, 500, "File operation failed");
            } catch (Exception e) {
                System.err.println("Error: " + e);
                fail(ex, 500, "request failed");
            } finally {
                ex.close();
            }
        });
    }

    private static void fail(HttpExchange ex, int code, String message) {
        try {
            Http.error(ex, code, message);
        } catch (IOException ignored) {
            // response already started or client gone
        }
    }

    private void list(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path dir = resolveExisting(lease.root(), q.get("path"));
            if (!Files.isDirectory(dir)) {
                throw new ApiException(400, "Not a folder");
            }
            List<Entry> entries = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    try {
                        var attrs = Files.readAttributes(p, BasicFileAttributes.class);
                        entries.add(new Entry(p.getFileName().toString(), attrs.isDirectory(),
                                attrs.isDirectory() ? 0 : attrs.size(), attrs.lastModifiedTime().toMillis()));
                    } catch (IOException skipped) {
                        // unreadable entry (e.g. broken link)
                    }
                }
            }
            entries.sort(Comparator.comparing((Entry e) -> !e.dir())
                    .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
            Http.json(ex, 200, Map.of("path", relative(lease.root(), dir), "entries", entries));
        }
    }

    private void download(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path file = resolveExisting(lease.root(), q.get("path"));
            if (!Files.isRegularFile(file)) {
                throw new ApiException(400, "Not a file");
            }
            long size = Files.size(file);
            long start = 0;
            long end = size - 1;
            int status = 200;
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null) {
                long[] parsed = parseRange(range, size);
                if (parsed == null) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                    throw new ApiException(416, "Range not satisfiable");
                }
                start = parsed[0];
                end = parsed[1];
                status = 206;
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }

            String name = file.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String inline = INLINE_TYPES.get(ext);
            boolean attachment = inline == null || "1".equals(q.get("download"));
            var h = ex.getResponseHeaders();
            h.set("Content-Type", inline != null ? inline : "application/octet-stream");
            h.set("Content-Disposition", (attachment ? "attachment" : "inline") + "; filename*=UTF-8''"
                    + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
            h.set("Accept-Ranges", "bytes");
            h.set("Cache-Control", "no-store");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Content-Security-Policy", "sandbox");

            long length = size == 0 ? 0 : end - start + 1;
            ex.sendResponseHeaders(status, length == 0 ? -1 : length);
            if (length == 0) {
                return;
            }
            try (InputStream in = Files.newInputStream(file); OutputStream out = ex.getResponseBody()) {
                in.skipNBytes(start);
                byte[] buf = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        }
    }

    /** Parses a single "bytes=a-b" range; returns null if it cannot be satisfied. */
    private static long[] parseRange(String header, long size) {
        if (!header.startsWith("bytes=") || header.contains(",") || size == 0) {
            return null;
        }
        String[] parts = header.substring(6).trim().split("-", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            long start;
            long end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            return start < 0 || start > end || start >= size ? null : new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void upload(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path dir = resolveExisting(lease.root(), q.get("path"));
            if (!Files.isDirectory(dir)) {
                throw new ApiException(400, "Not a folder");
            }
            Path target = dir.resolve(validName(q.get("name")));
            if (Files.exists(target)) {
                throw new ApiException(409, "A file with that name already exists");
            }
            Path tmp = dir.resolve(".upload-" + UUID.randomUUID() + ".part");
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    ex.getRequestBody().transferTo(out);
                }
                Files.move(tmp, target);
            } finally {
                Files.deleteIfExists(tmp);
            }
            Http.json(ex, 200, Map.of("ok", true));
        }
    }

    private void mkdir(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path dir = resolveExisting(lease.root(), q.get("path"));
            Files.createDirectory(dir.resolve(validName(q.get("name"))));
            Http.json(ex, 200, Map.of("ok", true));
        }
    }

    private void rename(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path source = resolveExisting(lease.root(), q.get("path"));
            requireNotRoot(lease.root(), source);
            Files.move(source, source.resolveSibling(validName(q.get("name"))));
            Http.json(ex, 200, Map.of("ok", true));
        }
    }

    private void delete(HttpExchange ex, Map<String, String> q) throws Exception {
        try (var lease = drive.lease()) {
            Path target = resolveExisting(lease.root(), q.get("path"));
            requireNotRoot(lease.root(), target);
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            Http.json(ex, 200, Map.of("ok", true));
        }
    }

    private static void requireNotRoot(Path root, Path target) throws IOException, ApiException {
        if (target.equals(root.toRealPath())) {
            throw new ApiException(400, "Cannot change the drive root");
        }
    }

    private static String validName(String name) throws ApiException {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new ApiException(400, "Invalid name");
        }
        return name;
    }

    /** Resolves a user path under the drive root; symlinks may not lead outside of it. */
    private static Path resolveExisting(Path root, String userPath) throws IOException, ApiException {
        Path realRoot = root.toRealPath();
        String rel = userPath == null ? "" : userPath.replaceFirst("^/+", "");
        Path target = realRoot.resolve(rel).normalize();
        if (!target.startsWith(realRoot)) {
            throw new ApiException(400, "Invalid path");
        }
        Path real = target.toRealPath();
        if (!real.startsWith(realRoot)) {
            throw new ApiException(403, "Access denied");
        }
        return real;
    }

    private static String relative(Path root, Path p) throws IOException {
        String rel = root.toRealPath().relativize(p).toString().replace('\\', '/');
        return "/" + rel;
    }
}

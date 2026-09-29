package org.example.api;

import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.common.ApiException;
import org.example.common.LampException;
import org.example.features.music.ZotifyService;
import org.example.settings.ZotifySettings;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** HTTP API for starting zotify downloads and fetching the result of "download to device" jobs. */
public class MusicApi {
    private static final String JOBS = "/api/music/jobs/";

    private record DownloadRequest(String url, String target) {
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private final ZotifyService zotify;
    private final ZotifySettings settings;

    public MusicApi(ZotifyService zotify, ZotifySettings settings) {
        this.zotify = zotify;
        this.settings = settings;
    }

    public void register(HttpServer server) {
        route(server, "/api/music/download", "POST", ex -> {
            DownloadRequest request = parse(Http.readBody(ex));
            Http.json(ex, 200, zotify.submit(request.url(), target(request.target())));
        });
        route(server, "/api/music/current", "GET", ex -> {
            var job = zotify.current();
            Http.json(ex, 200, job == null ? Map.of() : Map.of("job", job));
        });
        route(server, "/api/music/settings", null, ex -> {
            switch (ex.getRequestMethod()) {
                case "GET" -> Http.json(ex, 200, Map.of("options", settings.view()));
                case "PUT" -> {
                    settings.update(Http.readBody(ex));
                    Http.json(ex, 200, Map.of("options", settings.view()));
                }
                default -> Http.error(ex, 405, "Method not allowed");
            }
        });
        route(server, JOBS, "GET", ex -> {
            String rest = ex.getRequestURI().getPath().substring(JOBS.length());
            if (rest.endsWith("/file")) {
                sendFile(ex, rest.substring(0, rest.length() - "/file".length()));
            } else {
                Http.json(ex, 200, zotify.get(rest));
            }
        });
    }

    private static DownloadRequest parse(String body) throws ApiException {
        try {
            DownloadRequest request = Http.GSON.fromJson(body, DownloadRequest.class);
            if (request == null) {
                throw new ApiException(400, "Missing request");
            }
            return request;
        } catch (JsonParseException e) {
            throw new ApiException(400, "Invalid request");
        }
    }

    private static ZotifyService.Target target(String value) throws ApiException {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "server" -> ZotifyService.Target.SERVER;
            case "device" -> ZotifyService.Target.DEVICE;
            default -> throw new ApiException(400, "Choose 'server' or 'device'");
        };
    }

    private void sendFile(HttpExchange ex, String id) throws Exception {
        ZotifyService.DeviceFile file = zotify.deviceFile(id);
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", file.zip() ? "application/zip" : "application/octet-stream");
        headers.set("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(file.name(), StandardCharsets.UTF_8).replace("+", "%20"));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (!file.zip()) {
            var path = file.files().get(0);
            ex.sendResponseHeaders(200, Files.size(path));
            try (OutputStream out = ex.getResponseBody()) {
                Files.copy(path, out);
            }
            return;
        }
        ex.sendResponseHeaders(200, 0);
        try (ZipOutputStream zip = new ZipOutputStream(ex.getResponseBody())) {
            zip.setLevel(Deflater.NO_COMPRESSION); // audio does not compress
            for (var path : file.files()) {
                zip.putNextEntry(new ZipEntry(file.base().relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
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
                    handler.handle(ex);
                }
            } catch (ApiException e) {
                fail(ex, e.status(), e.getMessage());
            } catch (LampException e) {
                System.err.println("Music error: " + e.getMessage());
                fail(ex, 500, e.getMessage());
            } catch (IOException e) {
                System.err.println("Music file error: " + e);
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
}

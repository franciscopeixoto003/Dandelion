package org.example.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.common.LampException;
import org.example.features.lamp.PowerManager;
import org.example.features.lamp.ScheduleStore;
import org.example.settings.Config;
import org.example.settings.SettingsStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** HTTP front-end: serves the static web files and the lamp, schedule, drive and music APIs. */
public class LampServer {
    private static final String WEB_ROOT = "/web";
    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.[a-z]+");

    private interface Action {
        String run(HttpExchange ex) throws Exception;
    }

    private final Config config;
    private final PowerManager lamp;
    private final ScheduleStore schedules;
    private final SettingsStore settings;
    private final DriveApi driveApi;
    private final MusicApi musicApi;

    public LampServer(Config config, PowerManager lamp, ScheduleStore schedules,
                      SettingsStore settings, DriveApi driveApi, MusicApi musicApi) {
        this.config = config;
        this.lamp = lamp;
        this.schedules = schedules;
        this.settings = settings;
        this.driveApi = driveApi;
        this.musicApi = musicApi;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.httpPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", this::handleStatic);
        server.createContext("/api/status", ex -> handleApi(ex, "GET", e -> {
            boolean on = lamp.isLampOn();
            return lampJson(on, lamp.isUsbPowered());
        }));
        server.createContext("/api/on", ex -> handleApi(ex, "POST", e -> lampJson(lamp.setLamp(true), null)));
        server.createContext("/api/off", ex -> handleApi(ex, "POST", e -> lampJson(lamp.setLamp(false), null)));
        server.createContext("/api/settings", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                handleApi(ex, "GET", e -> settings.toJson());
            } else {
                handleApi(ex, "PUT", e -> {
                    settings.replaceFromJson(readBody(e));
                    lamp.settingsChanged();
                    return settings.toJson();
                });
            }
        });
        driveApi.register(server);
        musicApi.register(server);
        server.createContext("/api/schedules", ex -> {
            if ("GET".equals(ex.getRequestMethod())) {
                handleApi(ex, "GET", e -> scheduleJson());
            } else {
                handleApi(ex, "PUT", e -> {
                    schedules.replaceFromJson(readBody(e));
                    return scheduleJson();
                });
            }
        });
        server.start();
    }

    private String lampJson(boolean on, Boolean usbPower) {
        return "{\"on\":" + on + (usbPower == null ? "" : ",\"usbPower\":" + usbPower) + "}";
    }

    private String scheduleJson() {
        return "{\"timezone\":\"" + config.timezone() + "\",\"schedules\":" + schedules.toJson() + "}";
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if ("/favicon.ico".equals(path)) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if ("/".equals(path)) {
            path = "/index.html";
        }
        String type = contentType(path);
        byte[] content = type == null || !SAFE_PATH.matcher(path).matches() ? null : readResource(path);
        if (content == null) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        send(ex, 200, type, content);
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".txt")) return "text/plain; charset=utf-8";
        return null;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = LampServer.class.getResourceAsStream(WEB_ROOT + path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private void handleApi(HttpExchange ex, String method, Action action) throws IOException {
        try {
            if (!method.equals(ex.getRequestMethod())) {
                send(ex, 405, "application/json", "{\"error\":\"method not allowed\"}");
                return;
            }
            send(ex, 200, "application/json", action.run(ex));
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        } catch (LampException e) {
            System.err.println("Lamp error: " + e.getMessage() + (e.getCause() != null ? " (" + e.getCause() + ")" : ""));
            send(ex, 500, "application/json", "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        } catch (Exception e) {
            System.err.println("Error: " + e);
            send(ex, 500, "application/json", "{\"error\":\"request failed\"}");
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return Http.readBody(ex);
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        Http.send(ex, code, type, body);
    }

    private static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        Http.send(ex, code, type, bytes);
    }
}

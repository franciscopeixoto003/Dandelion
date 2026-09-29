package org.example.api;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers shared by the HTTP handlers. */
public final class Http {
    public static final Gson GSON = new Gson();
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private Http() {
    }

    public static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        send(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    public static void send(HttpExchange ex, int code, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void json(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json", GSON.toJson(body));
    }

    public static void error(HttpExchange ex, int code, String message) throws IOException {
        json(ex, code, Map.of("error", message));
    }

    public static String readBody(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Request too large");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> params = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }
}

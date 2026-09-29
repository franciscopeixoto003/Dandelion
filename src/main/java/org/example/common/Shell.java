package org.example.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a command and captures its combined output. */
public final class Shell {
    public record Result(int exit, String output) {
        public boolean ok() {
            return exit == 0;
        }

        public String firstLine() {
            return output.strip().lines().findFirst().orElse("");
        }
    }

    private Shell() {
    }

    public static Result run(List<String> cmd, int timeoutSeconds) throws LampException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new LampException("Cannot run '" + cmd.get(0) + "'", e);
        }
        var output = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[0]);
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                output.set(p.getInputStream().readAllBytes());
            } catch (IOException ignored) {
                // process was killed; keep what we have
            }
        });
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new LampException("'" + cmd.get(0) + "' timed out");
        }
        reader.join(2000);
        return new Result(p.exitValue(), new String(output.get(), StandardCharsets.UTF_8));
    }
}

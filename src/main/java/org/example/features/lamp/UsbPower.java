package org.example.features.lamp;

import org.example.common.LampException;
import org.example.settings.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Switches the Pi's USB power on/off using uhubctl. */
public class UsbPower {
    private final Config config;
    private final Object lock = new Object();

    public UsbPower(Config config) {
        this.config = config;
    }

    /**
     * Turns all ports of every configured hub on or off.
     * The Pi 5 ganges all its USB ports, so power only drops when every hub is off.
     */
    public boolean set(boolean on) throws LampException, InterruptedException {
        synchronized (lock) {
            for (String hub : config.hubs()) {
                run(List.of(config.uhubctl(), "-l", hub, "-a", on ? "on" : "off"));
            }
            boolean actual = readPower();
            if (actual != on) {
                throw new LampException("The command ran but USB power is still "
                        + (actual ? "on" : "off") + ". Check LAMP_HUBS.");
            }
            return actual;
        }
    }

    public boolean isOn() throws LampException, InterruptedException {
        synchronized (lock) {
            return readPower();
        }
    }

    /** USB power is on if any port of any configured hub is powered. */
    private boolean readPower() throws LampException, InterruptedException {
        boolean anyOn = false;
        boolean anyPort = false;
        for (String hub : config.hubs()) {
            String out = run(List.of(config.uhubctl(), "-l", hub));
            for (String line : out.split("\n")) {
                String l = line.trim();
                if (!l.matches("Port \\d+:.*")) {
                    continue;
                }
                anyPort = true;
                int bracket = l.indexOf('[');
                String status = bracket >= 0 ? l.substring(0, bracket) : l;
                if (Arrays.asList(status.split("\\s+")).contains("power")) {
                    anyOn = true;
                }
            }
        }
        if (!anyPort) {
            throw new LampException("No USB ports found for hubs " + String.join(", ", config.hubs())
                    + ". Check LAMP_HUBS.");
        }
        return anyOn;
    }

    private String run(List<String> cmd) throws LampException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new LampException("Cannot run '" + config.uhubctl());
        }
        try {
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new LampException("uhubctl timed out");
            }
            String text = new String(out, StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                String lower = text.toLowerCase();
                if (lower.contains("permission") || lower.contains("access")) {
                    throw new LampException("Permission denied. Run the app with sudo.");
                }
                throw new LampException("uhubctl failed (exit " + p.exitValue() + ")");
            }
            return text;
        } catch (IOException e) {
            throw new LampException("Could not read uhubctl output", e);
        }
    }
}

package org.example.features.drive;

import org.example.common.ApiException;
import org.example.common.LampException;
import org.example.features.lamp.PowerManager;
import org.example.settings.Config;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Opens the drive (power on, wait, mount) and closes it (unmount, power off).
 * Power is only cut after a successful unmount.
 */
public class DriveService {
    public enum State { CLOSED, OPENING, OPEN, CLOSING }

    /** Held while a file operation runs so the drive cannot be ejected underneath it. */
    public final class Lease implements AutoCloseable {
        private final Path root;

        private Lease(Path root) {
            this.root = root;
        }

        public Path root() {
            return root;
        }

        @Override
        public void close() {
            synchronized (stateLock) {
                inflight--;
                lastActivity = Instant.now();
            }
        }
    }

    public record Status(State state, Long totalBytes, Long freeBytes, int idleMinutes) {
    }

    private final Config config;
    private final DriveMounter mounter;
    private final PowerManager power;
    private final ReentrantLock opLock = new ReentrantLock();
    private final Object stateLock = new Object();
    private State state = State.CLOSED;
    private Path root;
    private int inflight;
    private Instant lastActivity = Instant.now();
    private boolean powerHeld;

    public DriveService(Config config, DriveMounter mounter, PowerManager power) {
        this.config = config;
        this.mounter = mounter;
        this.power = power;
    }

    /** Picks up a drive that is already mounted (app restarted while open); returns true if found. */
    public boolean resumeIfMounted() {
        try {
            var mounted = mounter.currentMount();
            if (mounted.isPresent()) {
                state = State.OPEN;
                root = mounted.get();
                powerHeld = true;
                lastActivity = Instant.now();
                return true;
            }
        } catch (LampException e) {
            System.err.println("Drive check failed: " + e.getMessage());
        }
        return false;
    }

    public void startIdleWatcher() {
        if (config.driveIdleMinutes() <= 0) {
            return;
        }
        var exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drive-idle");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::closeIfIdle, 30, 30, TimeUnit.SECONDS);
    }

    private void closeIfIdle() {
        try {
            synchronized (stateLock) {
                boolean idle = state == State.OPEN && inflight == 0
                        && Duration.between(lastActivity, Instant.now()).toMinutes() >= config.driveIdleMinutes();
                if (!idle) {
                    return;
                }
            }
            System.out.println("Drive idle for " + config.driveIdleMinutes() + " minutes, ejecting");
            close();
        } catch (Exception e) {
            System.err.println("Idle eject failed: " + e.getMessage());
        }
    }

    public void open() throws LampException, InterruptedException {
        opLock.lockInterruptibly();
        try {
            synchronized (stateLock) {
                if (state == State.OPEN) {
                    lastActivity = Instant.now();
                    return;
                }
                state = State.OPENING;
            }
            try {
                power.acquireDrive();
                powerHeld = true;
                Path mountPoint = mounter.waitAndMount();
                synchronized (stateLock) {
                    root = mountPoint;
                    state = State.OPEN;
                    lastActivity = Instant.now();
                }
            } catch (LampException | InterruptedException | RuntimeException e) {
                synchronized (stateLock) {
                    state = State.CLOSED;
                }
                releasePower(false);
                throw e;
            }
        } finally {
            opLock.unlock();
        }
    }

    public void close() throws LampException, InterruptedException {
        opLock.lockInterruptibly();
        try {
            Path mountPoint = null;
            synchronized (stateLock) {
                if (state == State.OPEN) {
                    if (inflight > 0) {
                        throw new ApiException(409, inflight + " file transfer(s) still running. "
                                + "Try again when they finish.");
                    }
                    state = State.CLOSING;
                    mountPoint = root;
                }
            }
            if (mountPoint != null) {
                try {
                    mounter.unmount(mountPoint);
                } catch (LampException | InterruptedException | RuntimeException e) {
                    synchronized (stateLock) {
                        state = State.OPEN;
                        lastActivity = Instant.now();
                    }
                    throw e;
                }
                synchronized (stateLock) {
                    state = State.CLOSED;
                    root = null;
                }
            }
            if (powerHeld) {
                power.releaseDrive(true);
                powerHeld = false;
            }
        } finally {
            opLock.unlock();
        }
    }

    public Lease lease() throws ApiException {
        synchronized (stateLock) {
            if (state != State.OPEN) {
                throw new ApiException(409, "The drive is not open");
            }
            inflight++;
            lastActivity = Instant.now();
            return new Lease(root);
        }
    }

    public Status status() {
        Path mountPoint;
        State current;
        synchronized (stateLock) {
            current = state;
            mountPoint = root;
        }
        Long total = null;
        Long free = null;
        if (current == State.OPEN && mountPoint != null) {
            try {
                FileStore store = Files.getFileStore(mountPoint);
                total = store.getTotalSpace();
                free = store.getUsableSpace();
            } catch (IOException ignored) {
                // space info is optional
            }
        }
        return new Status(current, total, free, config.driveIdleMinutes());
    }

    private void releasePower(boolean ejected) {
        if (!powerHeld) {
            return;
        }
        try {
            power.releaseDrive(ejected);
            powerHeld = false;
        } catch (LampException e) {
            System.err.println("Could not release USB power: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

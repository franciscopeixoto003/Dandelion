package org.example.features.lamp;

import org.example.common.ApiException;
import org.example.common.LampException;
import org.example.settings.SettingsStore;

/**
 * Decides when USB power is on. All ports share one power switch, so the lamp and the drive
 * both need power: it is on while either wants it, unless drive priority is enabled, in which
 * case ejecting the drive always cuts power and switches the lamp off.
 */
public class PowerManager {
    private final UsbPower usb;
    private final SettingsStore settings;
    private boolean lampWanted;
    private boolean driveWanted;

    public PowerManager(UsbPower usb, SettingsStore settings) {
        this.usb = usb;
        this.settings = settings;
    }

    public synchronized void init(boolean lamp, boolean drive) throws LampException, InterruptedException {
        lampWanted = lamp && !isLampDisconnected();
        driveWanted = drive;
        apply();
    }

    public boolean isLampDisconnected() {
        return settings.get().lampDisconnected();
    }

    /** Re-applies power after the settings changed: a disconnected lamp is always off. Settings still apply if USB power control fails. */
    public synchronized void settingsChanged() {
        if (isLampDisconnected()) {
            lampWanted = false;
        }
        try {
            apply();
        } catch (LampException e) {
            System.err.println("Could not update USB power after settings change: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean isLampOn() {
        return lampWanted;
    }

    public synchronized boolean isUsbPowered() throws LampException, InterruptedException {
        return usb.isOn();
    }

    public synchronized boolean setLamp(boolean on) throws LampException, InterruptedException {
        if (on && isLampDisconnected()) {
            throw new IllegalArgumentException("The lamp is disconnected");
        }
        boolean previous = lampWanted;
        lampWanted = on;
        try {
            apply();
        } catch (LampException | InterruptedException e) {
            lampWanted = previous;
            throw e;
        }
        return lampWanted;
    }

    public synchronized void acquireDrive() throws LampException, InterruptedException {
        if (settings.get().driveDisconnected()) {
            throw new ApiException(409, "The drive is disconnected");
        }
        boolean previous = driveWanted;
        driveWanted = true;
        try {
            apply();
        } catch (LampException | InterruptedException e) {
            driveWanted = previous;
            throw e;
        }
    }

    /** @param ejected true when the drive was ejected normally, so drive priority may switch the lamp off */
    public synchronized void releaseDrive(boolean ejected) throws LampException, InterruptedException {
        boolean previousDrive = driveWanted;
        boolean previousLamp = lampWanted;
        driveWanted = false;
        if (ejected && settings.get().drivePriority()) {
            lampWanted = false;
        }
        try {
            apply();
        } catch (LampException | InterruptedException e) {
            driveWanted = previousDrive;
            lampWanted = previousLamp;
            throw e;
        }
    }

    private void apply() throws LampException, InterruptedException {
        usb.set(lampWanted || driveWanted);
    }
}

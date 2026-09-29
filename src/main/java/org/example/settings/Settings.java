package org.example.settings;

/**
 * drivePriority: when true, ejecting the drive always cuts USB power (and switches the lamp off).
 * When false, power stays on while the lamp is on.
 * 
 * lampDisconnected: when true, the Lamp tab is disabled (ghost tab).
 * When false, the Lamp tab is enabled (default).
 * 
 * driveDisconnected: when true, the Drive tab is disabled (ghost tab) and Drive Priority toggle is locked.
 * When false, the Drive tab is enabled and Drive Priority toggle is available (default).
 * 
 * Features are only locked when BOTH lampDisconnected AND driveDisconnected are true.
 */
public record Settings(boolean drivePriority, boolean lampDisconnected, boolean driveDisconnected) {
}

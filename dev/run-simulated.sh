#!/bin/bash
# Runs the server locally with fake uhubctl/mount/umount, so no Pi or USB drive is needed.
# Simulated state lives in $SIM (default /tmp/pi-sim): usb = USB power, mounts = fake mount table.
set -e
DEV_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DEV_DIR")"
SIM="${SIM:-/tmp/pi-sim}"

mkdir -p "$SIM/drive/Documents"
[ -e "$SIM/drive/hello.txt" ] || echo "Hello from the simulated drive" > "$SIM/drive/hello.txt"
[ -e "$SIM/drive/Documents/notes.txt" ] || echo "notes" > "$SIM/drive/Documents/notes.txt"
touch "$SIM/device"
echo off > "$SIM/usb"
: > "$SIM/mounts"

[ -f "$ROOT/target/Dandelion1.jar" ] || (cd "$ROOT" && mvn -q package)

export HTTP_PORT="${HTTP_PORT:-8080}"
export UHUBCTL="$DEV_DIR/fake-uhubctl.sh" FAKE_UHUBCTL_STATE="$SIM/usb"
export DRIVE_DEVICE="$SIM/device" DRIVE_MOUNT="$SIM/drive"
export MOUNT_CMD="$DEV_DIR/fake-mount.sh" UMOUNT_CMD="$DEV_DIR/fake-umount.sh" MOUNTS_FILE="$SIM/mounts"
export DANDELION_SCHEDULE_FILE="$SIM/schedules.json" SETTINGS_FILE="$SIM/settings.json"
export DRIVE_TIMEOUT_SECONDS=5
export ZOTIFY_CMD="$DEV_DIR/fake-zotify.sh" ZOTIFY_TEMP_DIR="$SIM/zotify-tmp"

echo "Simulated Pi on http://localhost:$HTTP_PORT  (USB power state: watch 'cat $SIM/usb')"
exec java -jar "$ROOT/target/Dandelion1.jar"

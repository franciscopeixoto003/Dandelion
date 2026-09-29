#!/bin/bash
# Stand-in for mount for local testing: records "DEVICE MOUNTPOINT" in $MOUNTS_FILE.
MOUNTS="${MOUNTS_FILE:-/tmp/fake-mounts}"
args=("$@")
n=${#args[@]}
echo "${args[n-2]} ${args[n-1]} fake rw 0 0" >> "$MOUNTS"

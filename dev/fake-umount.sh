#!/bin/bash
# Stand-in for umount for local testing: removes the mount point from $MOUNTS_FILE.
MOUNTS="${MOUNTS_FILE:-/tmp/fake-mounts}"
grep -v " $1 " "$MOUNTS" > "$MOUNTS.new"
mv "$MOUNTS.new" "$MOUNTS"

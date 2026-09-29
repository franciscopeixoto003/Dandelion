#!/bin/bash
# Stand-in for uhubctl for local testing. State is kept in a temp file.
STATE="${FAKE_UHUBCTL_STATE:-/tmp/fake-uhubctl-state}"
PORT=1
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[i]}" in
    -p) PORT="${args[i+1]}" ;;
    -a) echo "${args[i+1]}" > "$STATE" ;;
  esac
done

if [ "$(cat "$STATE" 2>/dev/null)" = on ]; then
  echo "  Port $PORT: 0503 power highspeed enable connect [fake]"
else
  echo "  Port $PORT: 0000 off"
fi

#!/bin/bash
# Stand-in for zotify for local testing: "downloads" fake mp3 files under --root-path.
# The last argument is the Spotify link; album and playlist links produce 3 tracks.
root="" url=""
[ -z "$FAKE_ZOTIFY_LOG" ] || echo "$@" >> "$FAKE_ZOTIFY_LOG"
while [ $# -gt 0 ]; do
  case "$1" in
    --root-path) root="$2"; shift 2 ;;
    -*) shift 2 ;;
    *) url="$1"; shift ;;
  esac
done

[ -n "$root" ] || { echo "no --root-path" >&2; exit 2; }
case "$url" in
  */track/*|*/episode/*) tracks=1 ;;
  *) tracks=3 ;;
esac
id="${url##*/}"
dir="$root/Fake Artist/Fake Album $id"
mkdir -p "$dir"
for ((i = 1; i <= tracks; i++)); do
  printf '\rFake Song %d: 50%%|#####     |' "$i"
  sleep 1
  printf '\rFake Song %d: 100%%|##########|\n' "$i"
  head -c 200000 /dev/urandom > "$dir/0${i}_Fake_Song_$i.mp3"
done
echo "###   DOWNLOADED: $tracks   ###"

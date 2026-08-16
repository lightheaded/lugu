#!/usr/bin/env bash
# Provisions an Audiobookshelf server with a small invented library, from nothing.
#
# This exists so the instrumented tests stop skipping. `TestServerConfig` has always been
# able to read a server out of BuildConfig; what CI never had was a server. Every check in
# docs/qa/m0.md that begins "against the real server" is unreachable without one, which is
# why that checklist has never been run.
#
# Nothing here touches anybody's real library. The books are three sine waves and the
# names are the invented set AGENTS.md reserves for exactly this — no real title, author
# or server address is ever involved, and the credentials are generated per run and
# thrown away with the container.
#
# Usage:
#   scripts/seed-test-server.sh                       # localhost:13378, prints what it made
#   ABS_URL=http://10.0.2.2:13378 scripts/... --github-output
#
# Requires: docker (unless ABS_NO_DOCKER=1), curl, python3, ffmpeg.
set -euo pipefail

ABS_PORT="${ABS_PORT:-13378}"
ABS_URL="${ABS_URL:-http://localhost:$ABS_PORT}"
# What the *emulator* must dial to reach a server on the host. 10.0.2.2 is the host's
# loopback as seen from inside an AVD; localhost inside the emulator is the emulator.
ABS_EMULATOR_URL="${ABS_EMULATOR_URL:-http://10.0.2.2:$ABS_PORT}"
ABS_USER="${ABS_USER:-lugu-ci}"
# Generated, not chosen. This account exists for the lifetime of one container and is
# reachable only from the machine that made it, but a password in a repository is a
# password in a repository, so there is not one.
ABS_PASS="${ABS_PASS:-$(head -c 18 /dev/urandom | base64 | tr -d '/+=' )}"
ABS_IMAGE="${ABS_IMAGE:-ghcr.io/advplyr/audiobookshelf:latest}"
ABS_CONTAINER="${ABS_CONTAINER:-lugu-test-abs}"
ROOT="${ABS_ROOT:-${RUNNER_TEMP:-/tmp}/lugu-abs}"

# The title the playback tests are allowed to play. Kept as its own value because having a
# server is not the same as agreeing that a test may play something on it.
PLAY_QUERY="Lighthouse Wakes"

say() { printf '\033[36m==>\033[0m %s\n' "$*" >&2; }

# --------------------------------------------------------------------------------------
# The library, invented from scratch
# --------------------------------------------------------------------------------------
generate_media() {
  local ab="$ROOT/audiobooks" pc="$ROOT/podcasts"
  if [ -f "$ROOT/.media-done" ]; then say "media already generated"; return; fi
  mkdir -p "$ab/James T. R. Corven/Lighthouse Wakes" \
           "$ab/Jefferson Vale/The Breakwater" \
           "$pc/The Tidelands"

  # A chaptered single-file book. Three chapters, because "next chapter" cannot be tested
  # against a book with one, and a tone rather than silence so a decoder has real work.
  cat > "$ROOT/chapters.txt" <<'META'
;FFMETADATA1
title=Lighthouse Wakes
artist=James T. R. Corven
album=Lighthouse Wakes
[CHAPTER]
TIMEBASE=1/1000
START=0
END=30000
title=The Keeper
[CHAPTER]
TIMEBASE=1/1000
START=30000
END=60000
title=The Light
[CHAPTER]
TIMEBASE=1/1000
START=60000
END=90000
title=The Wreck
META
  ffmpeg -v error -f lavfi -i "sine=frequency=220:duration=90" -i "$ROOT/chapters.txt" \
    -map_metadata 1 -c:a aac -b:a 32k \
    "$ab/James T. R. Corven/Lighthouse Wakes/Lighthouse Wakes.m4b" -y

  # A two-file book, so that "crosses a file boundary without the position resetting" has
  # a boundary to cross. One file cannot fail that check.
  local i
  for i in 1 2; do
    ffmpeg -v error -f lavfi -i "sine=frequency=$((180 + i * 40)):duration=30" \
      -metadata title="Part $i" -metadata album="The Breakwater" \
      -metadata artist="Jefferson Vale" \
      -c:a libmp3lame -b:a 32k "$ab/Jefferson Vale/The Breakwater/0$i - Part $i.mp3" -y
  done

  # Episodes, so an episode can be shown to be tracked as itself rather than as the show.
  for i in 1 2 3; do
    ffmpeg -v error -f lavfi -i "sine=frequency=$((300 + i * 25)):duration=20" \
      -metadata title="Episode $i" -metadata album="The Tidelands" \
      -c:a libmp3lame -b:a 32k "$pc/The Tidelands/Episode $i.mp3" -y
  done
  touch "$ROOT/.media-done"
  say "generated $(find "$ab" "$pc" -type f | wc -l | tr -d ' ') files, $(du -sh "$ROOT" | cut -f1)"
}

start_server() {
  [ "${ABS_NO_DOCKER:-0}" = "1" ] && { say "using the server already at $ABS_URL"; return; }
  docker rm -f "$ABS_CONTAINER" >/dev/null 2>&1 || true
  mkdir -p "$ROOT/config" "$ROOT/metadata"
  docker run -d --name "$ABS_CONTAINER" -p "$ABS_PORT:80" \
    -v "$ROOT/config:/config" -v "$ROOT/metadata:/metadata" \
    -v "$ROOT/audiobooks:/audiobooks" -v "$ROOT/podcasts:/podcasts" \
    "$ABS_IMAGE" >/dev/null
  say "started $ABS_CONTAINER on $ABS_PORT"
}

api() { # api METHOD PATH [BODY]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sf -X "$method" "$ABS_URL$path" -H 'Content-Type: application/json' \
      ${TOKEN:+-H "Authorization: Bearer $TOKEN"} -d "$body"
  else
    curl -sf -X "$method" "$ABS_URL$path" ${TOKEN:+-H "Authorization: Bearer $TOKEN"}
  fi
}

wait_for_server() {
  local i
  for i in $(seq 1 60); do
    curl -sf "$ABS_URL/status" >/dev/null 2>&1 && { say "server answering"; return; }
    sleep 2
  done
  echo "Server never answered at $ABS_URL/status" >&2
  [ "${ABS_NO_DOCKER:-0}" = "1" ] || docker logs "$ABS_CONTAINER" 2>&1 | tail -30 >&2
  exit 1
}

# --------------------------------------------------------------------------------------
# Provisioning. `POST /init` is the only way to create the first user without a browser;
# it refuses once a root user exists, which is what makes this safe to re-run.
# --------------------------------------------------------------------------------------
TOKEN=""
initialise() {
  if python3 -c "import sys,json,urllib.request as u; sys.exit(0 if json.load(u.urlopen('$ABS_URL/status'))['isInit'] else 1)"; then
    say "already initialised"
  else
    api POST /init "{\"newRoot\":{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASS\"}}" >/dev/null
    say "created the root user"
  fi
  TOKEN=$(api POST /login "{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASS\"}" \
    | python3 -c 'import sys,json; u=json.load(sys.stdin)["user"]; print(u.get("accessToken") or u["token"])')
  [ -n "$TOKEN" ] || { echo "Logged in but got no token" >&2; exit 1; }
}

library_id() { # library_id NAME
  api GET /api/libraries | python3 -c "
import sys, json
for l in json.load(sys.stdin)['libraries']:
    if l['name'] == '$1':
        print(l['id']); break
"
}

make_library() { # make_library NAME MOUNT MEDIATYPE PROVIDER
  local existing; existing=$(library_id "$1")
  if [ -n "$existing" ]; then echo "$existing"; return; fi
  api POST /api/libraries \
    "{\"name\":\"$1\",\"folders\":[{\"fullPath\":\"$2\"}],\"mediaType\":\"$3\",\"provider\":\"$4\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'
}

# Creating a library does NOT scan it, and the scan endpoint returns before the scan has
# run. Both were found the hard way; polling the item count is the only honest signal.
scan_and_wait() { # scan_and_wait LIBRARY_ID EXPECTED
  api POST "/api/libraries/$1/scan" >/dev/null || true
  local i n
  for i in $(seq 1 45); do
    n=$(api GET "/api/libraries/$1/items" \
      | python3 -c 'import sys,json; print(json.load(sys.stdin)["total"])')
    [ "$n" -ge "$2" ] && { say "library $1 holds $n items"; return; }
    sleep 2
  done
  echo "Scan of $1 found $n items, expected at least $2" >&2
  exit 1
}

# --------------------------------------------------------------------------------------

main() {
  mkdir -p "$ROOT"
  generate_media
  start_server
  wait_for_server
  initialise

  books=$(make_library Audiobooks /audiobooks book audiobookshelf)
  casts=$(make_library Podcasts /podcasts podcast itunes)
  scan_and_wait "$books" 2
  scan_and_wait "$casts" 1

  if [ "${1:-}" = "--github-output" ]; then
    # Consumed by the workflow. The password is masked before it is ever echoed: it is
    # worthless outside this container, but a value that appears unmasked once teaches
    # everyone that masking is optional.
    echo "::add-mask::$ABS_PASS"
    {
      echo "server-url=$ABS_EMULATOR_URL"
      echo "user=$ABS_USER"
      echo "pass=$ABS_PASS"
      echo "play-query=$PLAY_QUERY"
    } >> "${GITHUB_OUTPUT:?--github-output needs GITHUB_OUTPUT}"
    say "wrote the connection details to GITHUB_OUTPUT"
  else
    cat <<SUMMARY

Ready. Put these in local.properties to point the instrumented tests at it:

  lugu.dev.serverUrl=$ABS_URL
  lugu.dev.user=$ABS_USER
  lugu.dev.pass=$ABS_PASS
  lugu.test.playQuery=$PLAY_QUERY

From an emulator the address is $ABS_EMULATOR_URL — inside an AVD, localhost is the AVD.
Stop it with: docker rm -f $ABS_CONTAINER
SUMMARY
  fi
}

main "$@"

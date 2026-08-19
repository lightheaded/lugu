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
#
# Whether it was *supplied* matters on a second run: the config volume outlives the
# container, so a server that already has a root user needs the password it was given the
# first time, not a fresh one. It is kept beside the config it belongs to.
ABS_PASS_SUPPLIED="${ABS_PASS:+yes}"
ABS_PASS="${ABS_PASS:-$(head -c 18 /dev/urandom | base64 | tr -d '/+=' )}"
ABS_IMAGE="${ABS_IMAGE:-ghcr.io/advplyr/audiobookshelf:latest}"
ABS_CONTAINER="${ABS_CONTAINER:-lugu-test-abs}"
ROOT="${ABS_ROOT:-${RUNNER_TEMP:-/tmp}/lugu-abs}"

# The title the playback tests are allowed to play. Kept as its own value because having a
# server is not the same as agreeing that a test may play something on it.
PLAY_QUERY="Lighthouse Wakes"

# The series the next-in-series test walks. Same reasoning as PLAY_QUERY, and the same
# consent: the test plays volume 1 to its end and lets volume 2 begin, so it moves the
# position of both books. The two volumes are found by their sequence within this series,
# not by title, which is what makes the check "volume 2 followed volume 1" rather than
# "something followed something".
SERIES_QUERY="Riverton"

# Bumped whenever the generated catalogue changes. See generate_media.
MEDIA_REVISION=2

say() { printf '\033[36m==>\033[0m %s\n' "$*" >&2; }

# --------------------------------------------------------------------------------------
# The library, invented from scratch
# --------------------------------------------------------------------------------------
generate_media() {
  local ab="$ROOT/audiobooks" pc="$ROOT/podcasts"
  # The marker carries the catalogue's revision, not just the fact that a run happened.
  # A root outlives the container, so a checkout that adds a book meets a root that was
  # filled before it existed. An unversioned marker made that root claim to be complete,
  # and the failure landed two steps later as a scan that never reached the expected item
  # count — which reads as a broken scanner rather than as a stale directory. Raise
  # MEDIA_REVISION whenever the catalogue below changes.
  if [ -f "$ROOT/.media-done-$MEDIA_REVISION" ]; then say "media already generated"; return; fi
  rm -f "$ROOT"/.media-done*
  mkdir -p "$ab/James T. R. Corven/Lighthouse Wakes" \
           "$ab/Jefferson Vale/The Breakwater" \
           "$ab/Nessa Cardrow/$SERIES_QUERY/Vol. 1 - Riverton Dawn" \
           "$ab/Nessa Cardrow/$SERIES_QUERY/Vol. 2 - Riverton Dusk" \
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

  # A real series: two volumes, numbered 1 and 2, so that "the next volume began" is a
  # thing a test can watch rather than a rule a unit test asserts about empty tables.
  #
  # Audiobookshelf takes the series and the sequence from the folder layout —
  # `<author>/<series>/Vol. N - <title>` — and no API call is needed after the scan. Two
  # other layouts were tried against a live 2.36.0 container and work equally well
  # (`N - <title>` and `<title> - Book N`); this one was kept because it reads as a volume
  # number to a person as well as to the scanner. The item titles come out as the folder
  # name with the volume prefix removed, and the joined string the API returns is
  # "Riverton #1", which is where lugu recovers the sequence from.
  #
  # Twenty-five seconds each, and the length is the point: the test seeks into the last few
  # seconds of volume 1 and then lets the audio run out, in real seconds. These are also the
  # shortest items in the catalogue, which is safe only because no other test names them —
  # the harness walks the ninety-second book to forty-five seconds and would run off the end
  # of one of these. Nothing may point lugu.test.playQuery at a Riverton volume.
  local vol title
  for vol in 1 2; do
    if [ "$vol" = 1 ]; then title="Riverton Dawn"; else title="Riverton Dusk"; fi
    ffmpeg -v error -f lavfi -i "sine=frequency=$((330 + vol * 30)):duration=25" \
      -metadata title="$title" -metadata album="$title" -metadata artist="Nessa Cardrow" \
      -c:a libmp3lame -b:a 32k \
      "$ab/Nessa Cardrow/$SERIES_QUERY/Vol. $vol - $title/$title.mp3" -y
  done

  # Episodes, so an episode can be shown to be tracked as itself rather than as the show.
  for i in 1 2 3; do
    ffmpeg -v error -f lavfi -i "sine=frequency=$((300 + i * 25)):duration=20" \
      -metadata title="Episode $i" -metadata album="The Tidelands" \
      -c:a libmp3lame -b:a 32k "$pc/The Tidelands/Episode $i.mp3" -y
  done
  touch "$ROOT/.media-done-$MEDIA_REVISION"
  say "generated $(find "$ab" "$pc" -type f | wc -l | tr -d ' ') files, $(du -sh "$ROOT" | cut -f1)"
}

start_server() {
  [ "${ABS_NO_DOCKER:-0}" = "1" ] && { say "using the server already at $ABS_URL"; return; }

  # Refuse to take over a container that belongs to a different ABS_ROOT.
  #
  # The container name is fixed and the root is not, so two roots on one machine fight over
  # one name — and the loser does not find out. Running this from a second root silently
  # replaced the first one's server and left its stored password pointing at an account that
  # no longer existed. What that looks like downstream is a sign-in that fails, an empty
  # library, and a test reporting "the title was not on screen", which reads like a bug in
  # the app rather than a stale credential. It cost two runs and nearly a wrong conclusion
  # about someone else's work.
  local mounted
  mounted=$(docker inspect "$ABS_CONTAINER" \
    --format '{{range .Mounts}}{{if eq .Destination "/config"}}{{.Source}}{{end}}{{end}}' 2>/dev/null || true)
  if [ -n "$mounted" ] && [ "$mounted" != "$ROOT/config" ]; then
    cat >&2 <<MSG
A container named $ABS_CONTAINER is already serving a different library.

  it is using:  $mounted
  you asked for: $ROOT/config

Re-creating it would strand whatever is pointed at the running one. Either use that
root — ABS_ROOT=$(dirname "$mounted") $0 — or take the running one down first:

  docker rm -f $ABS_CONTAINER
MSG
    exit 1
  fi

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
CRED_FILE="$ROOT/.abs-password"

initialise() {
  if python3 -c "import sys,json,urllib.request as u; sys.exit(0 if json.load(u.urlopen('$ABS_URL/status'))['isInit'] else 1)"; then
    # A second run against a config volume that survived the first. `POST /init` refuses
    # once a root user exists, so the only way in is the password that made it.
    if [ -z "$ABS_PASS_SUPPLIED" ] && [ -f "$CRED_FILE" ]; then
      ABS_PASS=$(cat "$CRED_FILE")
      say "already initialised, reusing the account from a previous run"
    else
      say "already initialised"
    fi
  else
    api POST /init "{\"newRoot\":{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASS\"}}" >/dev/null
    printf '%s' "$ABS_PASS" > "$CRED_FILE"
    chmod 600 "$CRED_FILE"
    say "created the root user"
  fi

  local response
  # Not `api POST` directly into python: curl failing there produced a JSON traceback
  # rather than the one sentence that says what to do about it.
  if ! response=$(api POST /login "{\"username\":\"$ABS_USER\",\"password\":\"$ABS_PASS\"}"); then
    cat >&2 <<MSG
Could not log in as $ABS_USER at $ABS_URL.

The server already has a root user and this script does not have its password. That
happens when $ROOT/config outlived the run that created it. Either:

  rm -rf "$ROOT/config"      # start the server over from nothing
  ABS_PASS=... $0            # or supply the password it was given
MSG
    exit 1
  fi
  TOKEN=$(printf '%s' "$response" \
    | python3 -c 'import sys,json; u=json.load(sys.stdin)["user"]; print(u.get("accessToken") or u["token"])')
  [ -n "$TOKEN" ] || { echo "Logged in but got no token back" >&2; exit 1; }
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
  # Four books now: the chaptered one, the two-file one, and the two Riverton volumes.
  scan_and_wait "$books" 4
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
      echo "series-query=$SERIES_QUERY"
    } >> "${GITHUB_OUTPUT:?--github-output needs GITHUB_OUTPUT}"
    say "wrote the connection details to GITHUB_OUTPUT"
  else
    cat <<SUMMARY

Ready. Put these in local.properties to point the instrumented tests at it:

  lugu.dev.serverUrl=$ABS_URL
  lugu.dev.user=$ABS_USER
  lugu.dev.pass=$ABS_PASS
  lugu.test.playQuery=$PLAY_QUERY
  lugu.test.seriesQuery=$SERIES_QUERY

From an emulator the address is $ABS_EMULATOR_URL — inside an AVD, localhost is the AVD.
Stop it with: docker rm -f $ABS_CONTAINER
SUMMARY
  fi
}

main "$@"

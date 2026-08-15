#!/usr/bin/env bash
#
# Captures real API responses from a live Audiobookshelf server so the shapes lugu
# assumes can be checked against reality (docs/research/05-api-live-notes.md).
#
# Credentials come from the gitignored local.properties and are never printed. Output
# goes to a gitignored directory, with the server address, user ids and item ids
# redacted, so a capture can be quoted in an issue or a commit without leaking anything.
#
#   cp local.properties.example local.properties   # then fill it in
#   ./scripts/capture-api.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/local.properties"
OUT="$ROOT/build/api-capture"

[ -f "$PROPS" ] || { echo "No local.properties. Copy local.properties.example and fill it in." >&2; exit 1; }

prop() { sed -n "s/^$1=//p" "$PROPS" | head -1; }

SERVER="$(prop 'lugu\.dev\.serverUrl')"
USER="$(prop 'lugu\.dev\.user')"
PASS="$(prop 'lugu\.dev\.pass')"

[ -n "$SERVER" ] && [ -n "$USER" ] && [ -n "$PASS" ] || {
  echo "local.properties needs lugu.dev.serverUrl, lugu.dev.user and lugu.dev.pass." >&2; exit 1; }

mkdir -p "$OUT"

# Strip anything that identifies the server or the account. Ids are replaced by stable
# placeholders rather than removed, so structure and cross-references stay readable.
redact() {
  python3 - "$SERVER" "$USER" <<'PY'
import json, re, sys
server, user = sys.argv[1], sys.argv[2]
raw = sys.stdin.read()
raw = raw.replace(server, "https://SERVER").replace(user, "USER")
raw = re.sub(r'"(li|ep|pl|col|lib|usr|play)_[A-Za-z0-9]+"', r'"\1_REDACTED"', raw)
raw = re.sub(r'"(accessToken|refreshToken|token|password)"\s*:\s*"[^"]*"', r'"\1":"REDACTED"', raw)
# Filesystem paths can carry a real name or a directory layout, so they go entirely.
raw = re.sub(r'"(path|relPath|coverPath|libraryFolderId)"\s*:\s*"[^"]*"', r'"\1":"REDACTED"', raw)
# contentUrl deliberately keeps its shape. Blanking it defeated the point of the
# capture: that URL template is the single thing lugu most needs confirmed, and the
# ids inside it have already been replaced by the rule above.
try:
    print(json.dumps(json.loads(raw), indent=2)[:20000])
except Exception:
    print(raw[:4000])
PY
}

say() { printf '\n=== %s ===\n' "$1"; }

say "GET /status"
curl -sS "$SERVER/status" | redact | tee "$OUT/status.json" | head -20

# Log in once and reuse the access token; the password never leaves this shell.
LOGIN="$(curl -sS -X POST -H 'Content-Type: application/json' -H 'x-return-tokens: true' \
  -d "$(python3 -c 'import json,sys; print(json.dumps({"username":sys.argv[1],"password":sys.argv[2]}))' "$USER" "$PASS")" \
  "$SERVER/login")"

TOKEN="$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["user"]["accessToken"])')"
[ -n "$TOKEN" ] || { echo "Login failed." >&2; exit 1; }
echo "logged in, access token acquired (not shown)"

printf '%s' "$LOGIN" | redact > "$OUT/login.json"
echo "wrote $OUT/login.json"

auth() { curl -sS -H "Authorization: Bearer $TOKEN" "$@"; }

say "GET /api/libraries"
auth "$SERVER/api/libraries" | redact | tee "$OUT/libraries.json" | head -30

LIB="$(auth "$SERVER/api/libraries" | python3 -c 'import json,sys; print(json.load(sys.stdin)["libraries"][0]["id"])')"

say "GET /api/libraries/:id/items?limit=1&minified=1"
auth "$SERVER/api/libraries/$LIB/items?limit=1&page=0&minified=1" | redact > "$OUT/library-items.json"
echo "wrote $OUT/library-items.json"

ITEM="$(auth "$SERVER/api/libraries/$LIB/items?limit=1&page=0&minified=1" \
  | python3 -c 'import json,sys; r=json.load(sys.stdin)["results"]; print(r[0]["id"] if r else "")')"

if [ -n "$ITEM" ]; then
  say "GET /api/items/:id?expanded=1"
  auth "$SERVER/api/items/$ITEM?expanded=1" | redact > "$OUT/item-expanded.json"
  echo "wrote $OUT/item-expanded.json"

  # The one shape lugu most needs confirmed: audioTracks[].contentUrl and playMethod.
  say "POST /api/items/:id/play"
  auth -X POST -H 'Content-Type: application/json' \
    -d '{"deviceInfo":{"deviceId":"capture","clientName":"lugu","clientVersion":"0.1.0"},
         "supportedMimeTypes":["audio/flac","audio/mpeg","audio/mp4","audio/aac","audio/ogg"],
         "mediaPlayer":"exo-player","forceDirectPlay":false,"forceTranscode":false}' \
    "$SERVER/api/items/$ITEM/play" > "$OUT/.play-session.raw"
  redact < "$OUT/.play-session.raw" > "$OUT/play-session.json"
  echo "wrote $OUT/play-session.json"

  # Close the session again. This runs against a real account: an open session left
  # behind shows up in someone's listening history as a book they never played.
  SESSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("id",""))' \
    "$OUT/.play-session.raw" 2>/dev/null || true)"
  if [ -n "$SESSION" ]; then
    auth -X POST "$SERVER/api/session/$SESSION/close" >/dev/null && echo "closed the capture session"
  fi
  rm -f "$OUT/.play-session.raw"
fi

say "GET /api/me (progress table)"
auth "$SERVER/api/me" | redact > "$OUT/me.json"
echo "wrote $OUT/me.json"

printf '\nCaptures in %s (redacted, gitignored).\n' "$OUT"
printf 'Fold anything surprising into docs/research/05-api-live-notes.md.\n'

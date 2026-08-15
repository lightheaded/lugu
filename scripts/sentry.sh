#!/usr/bin/env bash
#
# Read and triage this project's Sentry issues, from a shell.
#
# Exists because the lowest common denominator across coding agents is bash, not
# MCP: this works in any harness, in a plain terminal, and in an unattended run.
# The MCP server in .mcp.json is a convenience on top, not a replacement — it needs
# an interactive OAuth flow the first time, which an unattended session cannot do.
#
# The token comes from the SOPS-encrypted secrets file rather than a keychain, so
# no biometric prompt can block an unattended session. Set SENTRY_AGENT_TOKEN to
# override (CI, or a shell that already has one).
#
#   scripts/sentry.sh issues                      # unresolved, last 14 days
#   scripts/sentry.sh issues "is:unresolved ANR"  # any Sentry search query
#   scripts/sentry.sh issue 12345                 # one issue's detail
#   scripts/sentry.sh latest 12345                # newest event: exception + frames
#   scripts/sentry.sh resolve 12345               # triage (needs event:write)
#   scripts/sentry.sh ignore 12345
#   scripts/sentry.sh raw /organizations/         # escape hatch, raw JSON
#
# SENTRY_ORG / SENTRY_PROJECT override auto-detection, which picks the single
# org/project when there is exactly one — true for this account by design.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SECRETS="$REPO_ROOT/secrets.enc.yaml"
RENDER="$REPO_ROOT/scripts/sentry_render.py"
API="${SENTRY_API:-https://sentry.io/api/0}"

die() { printf 'sentry.sh: %s\n' "$*" >&2; exit 1; }

# --- auth ---------------------------------------------------------------------
if [ -n "${SENTRY_AGENT_TOKEN:-}" ]; then
  TOKEN="$SENTRY_AGENT_TOKEN"
else
  command -v sops >/dev/null 2>&1 || die "sops is not installed (brew install sops)"
  [ -f "$SECRETS" ] || die "no $SECRETS"
  TOKEN="$(sops -d --extract '["sentry_agent_token"]' "$SECRETS" 2>/dev/null | tr -d '\n')" || true
fi

if [ -z "${TOKEN:-}" ] || [ "$TOKEN" = "null" ]; then
  die "no sentry_agent_token. Either the lugu age key is missing from
  ~/.config/sops/age/keys.txt, or the token was never added:
    sops set secrets.enc.yaml '[\"sentry_agent_token\"]' '\"<token>\"'"
fi

# The token goes to curl through --config on stdin, never as an argument, so it
# does not show up in the process list. The HTTP status is appended and checked
# here: without that a 404 still exits 0 through jq and renders a wall of nulls,
# which reads like an empty result rather than a failure.
api() { # api <path> [extra curl args...]
  local path="$1"; shift
  local out status body
  out="$(printf 'header = "Authorization: Bearer %s"\nsilent\nshow-error\nwrite-out = "\\n%%{http_code}"\n' "$TOKEN" \
    | curl --config - "$@" "$API$path")" || die "could not reach $API$path"
  status="${out##*$'\n'}"
  body="${out%$'\n'*}"
  case "$status" in
    2*) printf '%s' "$body" ;;
    401|403) die "HTTP $status on $path — token rejected or missing a scope" ;;
    *) die "HTTP $status on $path: $(printf '%s' "$body" | head -c 300)" ;;
  esac
}

# --- org / project ------------------------------------------------------------
ORG="${SENTRY_ORG:-}"
if [ -z "$ORG" ]; then
  ORG="$(api /organizations/ | python3 "$RENDER" slug)" \
    || die "could not auto-detect the organization; set SENTRY_ORG"
fi

PROJECT="${SENTRY_PROJECT:-}"
need_project() {
  [ -n "$PROJECT" ] && return 0
  PROJECT="$(api "/organizations/$ORG/projects/" | python3 "$RENDER" slug)" \
    || die "could not auto-detect the project; set SENTRY_PROJECT"
}

# --- commands -----------------------------------------------------------------
cmd="${1:-issues}"; shift || true

case "$cmd" in
  orgs)     api /organizations/ | jq -r '.[].slug' ;;
  projects) api "/organizations/$ORG/projects/" | jq -r '.[].slug' ;;

  issues)
    need_project
    api "/projects/$ORG/$PROJECT/issues/" \
      --get --data-urlencode "query=${1:-is:unresolved}" \
            --data-urlencode "statsPeriod=14d" \
      | python3 "$RENDER" issues
    ;;

  # Issue endpoints are organization-scoped. The bare /issues/<id>/ form is gone
  # and answers 404, which looks like "no such issue" rather than "wrong URL".
  issue)
    [ $# -ge 1 ] || die "usage: sentry.sh issue <issueId>"
    api "/organizations/$ORG/issues/$1/" \
      | jq '{shortId, title, culprit, level, count, userCount,
             firstSeen, lastSeen, status, permalink}'
    ;;

  latest)
    [ $# -ge 1 ] || die "usage: sentry.sh latest <issueId>"
    api "/organizations/$ORG/issues/$1/events/latest/" | python3 "$RENDER" event
    ;;

  resolve|ignore)
    [ $# -ge 1 ] || die "usage: sentry.sh $cmd <issueId>"
    if [ "$cmd" = resolve ]; then status=resolved; else status=ignored; fi
    api "/organizations/$ORG/issues/$1/" -X PUT -H 'Content-Type: application/json' \
      -d "{\"status\":\"$status\"}" | jq -r '"\(.shortId) -> \(.status)"'
    ;;

  raw)
    [ $# -ge 1 ] || die "usage: sentry.sh raw <api-path>"
    api "$1" | jq .
    ;;

  *) die "unknown command '$cmd' — see the header of this file for usage" ;;
esac

#!/usr/bin/env bash
# Writes the notes of one lugu release to stdout.
#
#   release-notes.sh <version> <sha> <since-tag|""> <prerelease|release>
#
# The notes are built from git history: every commit since <since-tag> up to <sha>. The
# subjects become the TL;DR, and the bodies become "What changed" (AGENTS.md → Releases).
#
# Two callers, and they differ only in where the range starts:
# - The build on `main` publishes a prerelease. Its range starts at the tag before it,
#   whatever kind that is, so each prerelease says what is new since the last one.
# - Promotion turns a tested prerelease into a release. Its range starts at the previous
#   full release, because most people skip the prereleases. If the notes of the tested
#   build were kept, a person who updates from the last release would not see the
#   changes of the prereleases between the two.
#
# An empty <since-tag> means no tag to start from. The range is then the last 50
# commits, because the whole history is not a release note.
set -euo pipefail

VERSION=$1
SHA=$2
SINCE=$3
KIND=$4

if [ -n "$SINCE" ]; then
  RANGE="$SINCE..$SHA"
  LIMIT=""
else
  RANGE="$SHA"
  LIMIT="--max-count=50"
  echo "No tag to start from. Describing the last 50 commits." >&2
fi
echo "Release notes cover $RANGE ${LIMIT:-(no limit)}" >&2

TLDR=$(git log $LIMIT "$RANGE" --format='- %s' --no-merges)
DETAILS=$(git log $LIMIT "$RANGE" --format='### %s%n%n%b' --no-merges \
  | grep -v '^Co-Authored-By:' | grep -v '^Claude-Session:' || true)

# A release body is capped at 125000 characters by the API, so a long run of work has to
# be cut somewhere. The TL;DR is kept whole, because it names every change. The detail is
# what gets cut, and the note says so.
DETAIL_CAP=90000
if [ "$(printf '%s' "$DETAILS" | wc -c)" -gt "$DETAIL_CAP" ]; then
  DESCRIBED=$(printf '%s' "$DETAILS" | head -c "$DETAIL_CAP")
  DETAILS="$DESCRIBED

The detail is cut here, because it passed the size a release page holds. Every
change is named in the TL;DR above, and \`git log $RANGE\` holds the rest."
fi

if [ "$KIND" = "prerelease" ]; then
  echo "lugu $VERSION — prerelease for testing, built from \`${SHA::7}\`."
  echo
  echo "It becomes a release when the maintainer has tested it on a phone. Obtainium"
  echo "offers it only when \"Include prereleases\" is on for lugu."
else
  echo "lugu $VERSION — alpha build from \`${SHA::7}\`."
  if [ -n "$SINCE" ]; then
    echo
    echo "Changes since $SINCE."
  fi
fi
echo
echo "## TL;DR"
echo "$TLDR"
echo
echo "## What changed"
echo "$DETAILS"
echo
echo "Install over any previous lugu build; the signing key is stable."
echo "Always-current download of the newest release: https://github.com/lightheaded/lugu/releases/latest/download/lugu-latest.apk"
echo "Pre-alpha: see the README for what works and what does not."
echo
echo "\`lugu-mapping-$VERSION.txt\` is the R8 mapping for this exact build. A stack"
echo "trace from it is obfuscated and means nothing without it:"
echo "\`retrace lugu-mapping-$VERSION.txt trace.txt\`."

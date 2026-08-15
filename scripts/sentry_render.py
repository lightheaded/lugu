#!/usr/bin/env python3
"""Render Sentry API JSON as compact text for humans and agents.

Kept out of sentry.sh because embedding Python in shell quoting is a bug farm.
Reads JSON on stdin; the mode is the single argument.
"""

import json
import sys


def issues(data):
    if not data:
        print("no issues match")
        return
    for i in data:
        print(
            f"{i['shortId']:<18} {i.get('level', '?'):<7} {i['count']:>5}x  "
            f"last {i['lastSeen'][:16]}  [{i['id']}]"
        )
        print(f"  {i['title']}")
        if i.get("culprit"):
            print(f"    in {i['culprit']}")


def event(e):
    print(f"event {e.get('eventID', '?')}  {e.get('dateCreated', '')[:19]}")

    tags = {t["key"]: t["value"] for t in e.get("tags", [])}
    for key in ("release", "environment", "device", "os", "level"):
        if key in tags:
            print(f"  {key}: {tags[key]}")

    for entry in e.get("entries", []):
        if entry.get("type") != "exception":
            continue
        for exc in entry["data"].get("values", []):
            print(f"\n{exc.get('type')}: {exc.get('value')}")
            frames = (exc.get("stacktrace") or {}).get("frames") or []
            # Sentry orders frames oldest-first, so the crash site is last. Show the
            # deepest 15, nearest-first, and mark the ones in our own code.
            for f in reversed(frames[-15:]):
                mark = "*" if f.get("inApp") else " "
                where = f.get("module") or f.get("filename") or "?"
                print(f"  {mark} {where}.{f.get('function')}:{f.get('lineNo')}")


def project_slug(data):
    slugs = [i["slug"] for i in data]
    if len(slugs) != 1:
        sys.exit(f"expected exactly one, found: {', '.join(slugs) or 'none'}")
    print(slugs[0])


MODES = {"issues": issues, "event": event, "slug": project_slug}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in MODES:
        sys.exit(f"usage: {sys.argv[0]} {{{'|'.join(MODES)}}} < json")
    MODES[sys.argv[1]](json.load(sys.stdin))

# M4 — the re-plan on arrival

*2026-09-02. `EXECUTION-PLAN.md` §Phase 6 summarises M4 and says "re-plan on arrival".
This is that plan. It replaces the summary for execution purposes. The summary stays where
it is as the record of what was expected in August.*

M4 as summarised holds six items: Chromecast, Glance widgets, stats screens, multi-server
UI, OIDC through Custom Tabs, and WearOS/TV spikes. One of the six cannot be built under
the project's own locked decisions. The reason is below, and it is a licence conflict
rather than a matter of effort.

## What is built, and in what order

| # | Item | Why this position |
| --- | --- | --- |
| 1 | **Stats screens** | The data already exists. `SessionLedgerRepository` writes every session and never deletes one, and no UI has ever read it. Nothing else has to move first |
| 2 | **Multi-server UI** | The largest real capability gap. `ServerEntity` and `setActive` exist, and every user-scoped row is keyed `(serverId, userId)`, so the schema was built for this in v1. One thing does have to move: the token store |
| 3 | **OIDC through Custom Tabs** | Shares the login screen and the token path with item 2, so it follows it rather than interleaving with it |
| 4 | **Glance widget** | Independent of the other three. Last because it is the only one that needs a new UI toolkit in the build |
| 5 | **WearOS and TV** | The summary calls these spikes. A spike is a document that answers "what would this cost and what blocks it", not a product |

## Chromecast is not built, and this is why

`androidx.media3:media3-cast:1.11.0` declares a hard dependency on
`com.google.android.gms:play-services-cast-framework`. That artifact is proprietary. It
ships under Google's SDK terms and not under Apache-2.0.

Two locked rules refuse it:

- `AGENTS.md`: "Every dependency must carry a GPL-compatible license (everything today is
  Apache-2.0)."
- `EXECUTION-PLAN.md` M5: F-Droid reproducible builds. F-Droid refuses a build that
  depends on Google Play services.

A GPL-3.0 app linked against a proprietary library is the exact incompatibility the first
rule exists to prevent. Product flavors do not fix it. A `play` flavor would still be a
GPL-3.0 app linked against a proprietary blob, and it would still be the variant on the
Play Store.

**Rejected, with the reason, so the obvious re-attempt does not happen:**

1. **A flavor split.** See above. It moves the conflict into one variant and does not
   remove it.
2. **A reverse-engineered Cast sender.** The Cast v2 protocol over mDNS and protobuf is
   documented well enough for a research project. It is not an overnight build, and a
   half-working sender that drops a listener mid-book is worse than no button.
3. **Local network playback by another route.** DLNA or a plain HTTP handoff would serve
   part of the same need with no proprietary dependency. This is worth its own decision
   later. It is not Chromecast, so it must not be recorded as Chromecast.

**What this needs from Tom:** a decision, not code. Either the GPL-compatible-dependency
rule changes and F-Droid goes, or Chromecast leaves M4. Nothing in this repository can
settle that.

## What each item cannot prove on this machine

Recorded per item so that no reader mistakes written for verified. This project already
carries this category for `io.socket` and `AbsPodcasts`.

| Item | Proven here | Needs hardware or a service |
| --- | --- | --- |
| Stats | The aggregation, the day boundaries and the screen | Nothing |
| Multi-server | Token isolation, the switch, and the sign-out of one account | Two real servers |
| OIDC | The URL construction, the state check and the callback parse | A provider, and a server configured to use it |
| Glance widget | The state mapping | A launcher, and a real widget host |

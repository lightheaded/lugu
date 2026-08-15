# Security

lugu is a client. It holds your Audiobookshelf credentials' derived tokens (encrypted,
on your phone, excluded from backups) and talks only to the server you point it at.
There is no lugu server, no telemetry endpoint, and no third-party service in the path.

## Reporting a vulnerability

Report privately through GitHub's [private vulnerability reporting](../../security/advisories/new)
rather than a public issue, so a fix can ship before the details do. Expect an
acknowledgement within a week.

Most interesting areas: token storage and refresh, the HTTP client (auth headers on
every request, including cached/downloaded media), and anything that could make the
app talk to a host other than the configured server.

## Supported versions

Pre-alpha: only the latest rolling release is supported. There are no backports.

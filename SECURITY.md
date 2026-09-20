# Security

ME Control Center exposes control of Minecraft/AE2 state over HTTP, usually on public multiplayer servers.
This document describes how it is protected, how to deploy it safely, and what it does not protect against.

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub security advisories](https://github.com/Codaaaaaa/ME-Control-Center/security/advisories/new), not
in public issues. Include the ME Control Center version, the server setup (direct or behind which proxy),
and steps to reproduce.

## Deploying safely

- ME Control Center speaks plain HTTP. For anything beyond a LAN, keep `host = "127.0.0.1"` and put an HTTPS
  reverse proxy in front (see *Reverse proxy* in the README), list it in `trusted_proxies`, set
  `public_base_url`, and enable `require_https_cookie`.
- Leave `trusted_proxies` empty when players connect directly. Every entry is trusted to report the real
  client address and scheme; listing anything else lets clients forge their address.
- Decide whether server admins need `admin_override`. It gives every operator at `admin_op_level` Owner
  access to every network; their use of it is audited.
- Back up `<world>/mecc/` with the world (see *Backups* in the README). The database holds player names and
  UUIDs, the last IP address of each paired device, watchlists, and the audit log; treat backups accordingly.

## Security model

| Requirement (spec section 36) | Implementation |
|---|---|
| Random pairing keys and tokens | `SecureRandom`; 12-character pairing keys (about 59 bits), 256-bit device tokens |
| Hashed persistent tokens | Only a SHA-256 verifier of each device token is stored; tokens are never logged |
| One-time pairing keys | Valid 5 minutes (configurable), single use, bound to the player; a new `/mecc pair` invalidates the previous key |
| Authentication rate limits | Pairing: 10 attempts per address per 5 minutes, 120 per minute server-wide, 5 keys per player per 5 minutes |
| Per-IP and per-user rate limits | `rate_limit_requests_per_minute` per client address (all API requests and WebSocket upgrades; icons exempt), `rate_limit_writes_per_minute` per player (all changes); `429 RATE_LIMITED` |
| Permission check on every network request | `NetworkGuard` resolves the caller's role on every request; unknown and forbidden networks both answer `NETWORK_NOT_FOUND` |
| CSRF-safe browser authentication | HttpOnly `SameSite=Strict` cookie; state-changing requests must be `application/json` (preflighted) and are refused from foreign `Origin`s or `Sec-Fetch-Site: cross-site` |
| WebSocket authentication | Same token and origin checks as REST; the device is re-checked every minute (revoked devices are disconnected); at most 16 connections per player and 120 messages per minute per connection |
| Origin configuration | Own host, `public_base_url`, and `allowed_origins` only |
| No trust of arbitrary proxy headers | `X-Forwarded-For`/`-Proto` are read only from `trusted_proxies`, walking the chain from the right |
| Body size limits | 64 KiB per request; WebSocket messages 4 KiB |
| Order and pattern limits | `max_craft_amount`, `max_pattern_inputs`, `max_pattern_outputs`, `max_drafts_per_user`, `max_watchlist_entries_per_user`, `max_rules_per_user`, 200 saved orders per player |
| Outbound requests (alert webhooks) | Discord URLs must be `https://discord.com/api/webhooks/...`; generic URLs `http(s)` without credentials; every address the host resolves to is checked before sending and loopback, private, link-local, CGNAT, and other internal ranges are refused (`allow_private_webhook_targets` to allow); redirects are not followed; 10 s timeout; Discord messages cannot mention `@everyone` or roles |
| Safe content-pack handling | Packs are read as data only (PNG, JSON, language files); icon keys are validated and cannot leave a pack; oversized files are skipped |
| Safe response headers | `Content-Security-Policy` (self only, no framing), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, no server version |
| No stack traces for users | API errors are typed `{error: {code, message, details}}`; unexpected failures answer `INTERNAL_ERROR` and are logged on the server only |
| Server-thread safety | HTTP, WebSocket, database, and analytics threads never touch game state; all access goes through `ServerThreadGateway` with a bounded queue and deadlines |
| Audit log | Every control action, with actor, device, network, target, result, and admin-override flag |

## Milestone 6 review

A review of the whole request path before the first public release found and fixed:

- **No request budget outside pairing.** A paired player, or anyone hammering unauthenticated endpoints,
  could send unlimited requests. Added per-address and per-player limits (above).
- **Unbounded WebSocket use.** One device could open any number of live connections and send unlimited
  `subscribe` messages, each costing a database read. Connections per player and messages per connection are
  now capped, and upgrades count against the address's request budget.
- **Removed members' watchlists kept being sampled.** Their entries are now deleted with the membership.
- **Audit log not reviewable.** Events were recorded but could not be read without opening the database.
  Owners now see their network's log and server admins the server-wide log.
- **No safe way to back up a running server.** Copying `mecc.db` alone while the server runs can miss
  changes still in the WAL file. Added consistent online backups and an automatic backup before schema
  upgrades.

Checked without findings: pairing-key replay and expiry, revoked-device handling (REST and WebSocket),
cross-network access with forged and malformed network IDs, forwarded-header spoofing, CSRF on every
state-changing route, request size limits, icon-key path traversal, craft/pattern amount limits, and error
responses. These are covered by the tests in `web-server` (`ApiSecurityTest`) and `runtime`
(`MultiplayerAccessTest`, `CraftingTest`, `PatternStudioTest`, `PublicServerLoadTest`).

## Milestone 8 review

Alert webhooks are the first feature that makes the server send requests on a player's behalf, so any player could
otherwise aim them at the server's own network (SSRF). Addresses are checked after DNS resolution and redirects are
off (above). Names shown in webhook messages come from players (network names, renamed items); Discord messages
disable mentions. Alert rules, events, and saved orders are personal: other players' IDs answer `NOT_FOUND`, and all
of them are deleted when their owner leaves the network. Covered by `OperationsTest` and `WebhookTargetsTest`.

## Known limitations

- Rate-limit counters live in memory and reset when the server restarts.
- The audit log is kept indefinitely. It grows by one row per control action, so its size is normally
  negligible.
- ME Control Center does not implement TLS itself; use a reverse proxy.
- The webhook address check resolves the host and the HTTP client resolves it again when connecting; the JVM's DNS
  cache (30 s) makes both the same answer in practice, but a DNS server built to rebind within that window is not
  ruled out. Keep `allow_private_webhook_targets = false` and, on sensitive hosts, egress-filter the server.
- Rules created by a server admin through `admin_override` on a network they are not a member of keep running
  after the player stops being an admin, until they delete them.

# ME Control Center — HTTP & WebSocket API

`/api/v1` is served by the mod itself on the port from `[web]` in `mecc.toml`.
See the [manual](manual.md) for configuration and the [security model](../SECURITY.md) for authentication.


`GET /api/v1/status`

```json
{
  "timestamp": "2026-09-17T12:00:00Z",
  "mecc": { "version": "0.1.0", "startedAt": "...", "uptimeSeconds": 42 },
  "platform": { "platformId": "forge-1.20.1", "minecraftVersion": "1.20.1", "loader": "forge", "loaderVersion": "47.4.16" },
  "state": "RUNNING",
  "server": { "dedicated": true, "playersOnline": 0, "maxPlayers": 20, "averageTickMillis": 3.1, "motd": "A Minecraft Server" },
  "ae2": { "loaded": true, "version": "15.4.10", "testedVersion": "15.4.10", "tested": true },
  "gateway": { "roundTripMillis": 12.4, "pendingTasks": 0, "errorCode": null }
}
```

`state` is `RUNNING`, `BUSY` (the server thread did not answer in time), or `UNAVAILABLE` (stopping).
`server` is `null` unless `state` is `RUNNING`. Errors use `{"error":{"code","message","details"}}`.

Everything except `status` and `auth/pair` requires the device cookie (or `Authorization: Bearer <token>`).
State-changing requests must send `Content-Type: application/json` and must not come from a foreign origin.

```
GET    /api/v1/status                                   public
POST   /api/v1/auth/pair            {key, deviceName?}  public; sets the device cookie
POST   /api/v1/auth/logout
GET    /api/v1/me
GET    /api/v1/devices
PATCH  /api/v1/devices/{deviceId}   {name}
DELETE /api/v1/devices/{deviceId}
POST   /api/v1/devices/revoke-others
GET    /api/v1/networks                                 networks you can access
GET    /api/v1/networks/candidates                      loaded, unenrolled networks you may claim
POST   /api/v1/networks             {candidateKey, displayName}
GET    /api/v1/networks/{networkId}                     live status, anchors, your capabilities
PATCH  /api/v1/networks/{networkId} {name}
DELETE /api/v1/networks/{networkId}
GET    /api/v1/networks/{networkId}/members
POST   /api/v1/networks/{networkId}/members             {player, role}
PATCH  /api/v1/networks/{networkId}/members/{playerUuid} {role}
DELETE /api/v1/networks/{networkId}/members/{playerUuid}
GET    /api/v1/networks/{networkId}/resources           ?q&sort&desc&type&offset&limit&snapshot&locale
GET    /api/v1/networks/{networkId}/resources/detail    ?id&snapshot&locale
GET    /api/v1/icons                                    ?key&v
GET    /api/v1/networks/{networkId}/crafting/cpus       ?locale
GET    /api/v1/networks/{networkId}/crafting/cpus/{cpuId}/tree  ?locale; the running job as a tree of steps
GET    /api/v1/networks/{networkId}/machines            ?locale; machines behind the pattern providers and their status
POST   /api/v1/networks/{networkId}/crafting/cpus/{cpuId}/cancel   {jobId}
POST   /api/v1/networks/{networkId}/crafting/plan       {resourceId, amount, locale}
GET    /api/v1/networks/{networkId}/crafting/plans/{planId}        ?locale
POST   /api/v1/networks/{networkId}/crafting/orders     {planId, cpuId?, source?=MANUAL|SAVED_ORDER, locale}
GET    /api/v1/networks/{networkId}/crafting/orders     ?status=ACTIVE|COMPLETED|FAILED|CANCELLED&limit&before&locale
GET    /api/v1/networks/{networkId}/crafting/orders/{orderId}      ?locale
POST   /api/v1/networks/{networkId}/crafting/orders/{orderId}/cancel
GET    /api/v1/networks/{networkId}/crafting/saved-orders  ?locale; your presets
POST   /api/v1/networks/{networkId}/crafting/saved-orders  {name, resourceId, amount, cpuId?, notes?, locale}
PATCH  /api/v1/networks/{networkId}/crafting/saved-orders/{savedOrderId}  {name, amount, cpuId?, notes?}
DELETE /api/v1/networks/{networkId}/crafting/saved-orders/{savedOrderId}
GET    /api/v1/patterns/drafts                          ?locale; your own drafts
POST   /api/v1/patterns/drafts      {name, description?, networkId?, definition}
GET    /api/v1/patterns/drafts/{draftId}                ?locale
PATCH  /api/v1/patterns/drafts/{draftId}                {name, description?, networkId?, definition}
DELETE /api/v1/patterns/drafts/{draftId}
GET    /api/v1/patterns/catalog                         ?q&sort&desc&type&offset&limit&locale; every registered item and fluid
GET    /api/v1/patterns/recipes                         ?type&output|input&locale
GET    /api/v1/networks/{networkId}/providers           ?locale
PATCH  /api/v1/networks/{networkId}/providers/{providerId} {name?, priority?, blocking?, lockMode?, visibleInTerminal?}
POST   /api/v1/networks/{networkId}/patterns/validate   {definition, locale}
POST   /api/v1/networks/{networkId}/patterns/encode     {definition, draftId?, locale}; into ME storage
POST   /api/v1/networks/{networkId}/patterns/deploy     {definition, draftId?, providerId, locale}
GET    /api/v1/networks/{networkId}/patterns/deployments ?locale
GET    /api/v1/watchlist                                ?networkId&locale; your entries with current amounts
POST   /api/v1/watchlist            {networkId, resourceId, locale}
DELETE /api/v1/watchlist/{entryId}
GET    /api/v1/insights/series                          ?networkId&range=1h|6h|1d|7d|30d|180d|360d|max&resource
GET    /api/v1/alerts                                   ?networkId&before&limit&locale; your alert events, newest first
GET    /api/v1/alerts/rules                             ?networkId&locale
POST   /api/v1/alerts/rules         {networkId, type, resourceId?, threshold?, windowMinutes?, cooldownMinutes?, enabled?, locale}
PATCH  /api/v1/alerts/rules/{ruleId}                    {threshold?, windowMinutes?, cooldownMinutes?, enabled?}
DELETE /api/v1/alerts/rules/{ruleId}
GET    /api/v1/alerts/settings                          your webhooks
PATCH  /api/v1/alerts/settings      {discordWebhookUrl, webhookUrl, locale}
POST   /api/v1/alerts/test                              send a test message to your webhooks
GET    /api/v1/networks/{networkId}/automation/restock  ?locale; the network's Keep Stock rules
POST   /api/v1/networks/{networkId}/automation/restock  {resourceId, minimum, restockTo, cpuId?, cooldownMinutes?, enabled?, locale} (Manager)
PATCH  /api/v1/networks/{networkId}/automation/restock/{ruleId}  {minimum?, restockTo?, cpuId?, cooldownMinutes?, enabled?} (Manager)
DELETE /api/v1/networks/{networkId}/automation/restock/{ruleId}  (Manager)
POST   /api/v1/networks/{networkId}/automation/stop     kill switch: turn every rule off (Manager)
GET    /api/v1/networks/{networkId}/explorer            ?locale; devices, channels, and what is offline
GET    /api/v1/networks/{networkId}/audit               ?before&limit; the network's audit log (Owner)
GET    /api/v1/admin                                    effective configuration and database state (server admins)
GET    /api/v1/admin/audit                              ?before&limit; the server-wide audit log (server admins)
POST   /api/v1/admin/backups                            back up the database now (server admins)
GET    /ws/v1                                           WebSocket, see below
```

Audit pages are `{entries: [...], nextBefore}`, newest first; pass `nextBefore` as `before` for older entries.
Over a rate limit, requests fail with `429 RATE_LIMITED`.

Networks you may not access return `NETWORK_NOT_FOUND`, indistinguishable from nonexistent ones.
Status values that cannot be read reliably are `null`, never `0`.

A pattern `definition` is
`{type, inputs: [{resource, amount} | null, ...], outputs: [...], substitutes, fluidSubstitutes, recipeId}` with
`type` one of `CRAFTING` (9 input slots, row by row), `PROCESSING`, `SMITHING` (template, base, addition), and
`STONECUTTING` (1 input, `recipeId` required); `resource` is a resource ID and `amount` a raw amount
(millibuckets for fluids). Outputs are only authored for processing patterns. Validation answers
`{valid, issues: [{code, field, message}], outputs, recipeId, blankPatterns}`. Encode and deploy fail with
`PATTERN_INVALID` (`details.issues`), `NO_BLANK_PATTERN`, `NETWORK_NO_POWER`, `PROVIDER_NOT_FOUND`,
`PROVIDER_OFFLINE`, `PROVIDER_FULL`, `DEPLOY_FAILED`, `VERIFY_FAILED`, or `ENCODE_FAILED`, each with
`details.deploymentId`; after a failure the Blank Pattern is always back in storage (`details.blankReturned`
reports this when it had been taken).

A resources page returns `snapshotId`; pass it back as `snapshot` to keep paging consistent while storage
changes (the last few snapshots per network stay addressable). Resource IDs are version-independent:
`item:minecraft:iron_ingot`, `fluid:minecraft:water`, and a short NBT hash for variants
(`item:minecraft:enchanted_book:3f9a0c1d2e4b`). Icon URLs carry the asset version, so browsers may cache
them for a long time.

`POST .../crafting/plan` answers once the plan is ready, or after about 10 seconds with
`"state": "CALCULATING"`; then poll `GET .../plans/{planId}`. Plans belong to the player who calculated
them, can be submitted once, and expire after 10 minutes. A refused submission fails with the reason as
error code (`NO_SUITABLE_CPU`, `CPU_BUSY`, `MISSING_INGREDIENTS`, `PLAN_INCOMPLETE`, ...) and
`details.orderId` of the recorded failed order. Progress is `{completed, remaining, requested, percent,
confidence}`; `percent` is `null` when there is no meaningful value.

Watching needs a loaded network that stores or can craft the resource (`NETWORK_OFFLINE`,
`RESOURCE_NOT_FOUND`); watching it again returns the existing entry; beyond `max_watchlist_entries_per_user`
it fails with `CONFLICT`. A watchlist `amount` is `null` while the network cannot be read. A series set is
`{range, from, to, stepSeconds, resolution, sampling, series: [{entryId, resourceId, points}]}`, each point
`[epochMillis, avg, min, max]` (raw amounts, e.g. millibuckets); a point with `null` values marks a gap.

`/ws/v1` uses the device cookie (or `Authorization: Bearer`), refuses foreign origins, and re-checks the
device every minute. Send `{"type":"subscribe","networkId":"...","locale":"zh_cn"}` (access is checked like
REST), `{"type":"unsubscribe","networkId":"..."}`, or `{"type":"ping"}`. Events have the envelope
`{type, timestamp, networkId, payload}`: `session.ready`, `subscribed`, `cpu.updated` (the CPU list, sent
when it changes), `crafting.order.created|updated|completed|failed` (the order), `pattern.deployed`, `network.status.changed`,
`subscription.ended` (access lost), `error`, and `pong`. Live events only improve freshness; after
reconnecting, reload state through REST.

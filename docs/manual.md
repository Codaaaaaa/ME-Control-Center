# ME Control Center — User & Admin Manual

Pairing, every page of the web UI, server installation, and the full `mecc.toml` reference.
For the design baseline see [PRODUCT_TECHNICAL_SPEC.md](../PRODUCT_TECHNICAL_SPEC.md), for the security
model [SECURITY.md](../SECURITY.md), and for the HTTP API [api.md](api.md).

## Players: pairing and networks

1. In game, run `/mecc pair`. A one-time key such as `AB7K-3M2Q-9RXF` appears in chat (click to copy).
   It expires after 5 minutes, works once, and a new `/mecc pair` invalidates the previous key.
2. Open ME Control Center in a browser and enter the key. The browser receives an HttpOnly device cookie; only a
   SHA-256 verifier is stored on the server.
3. Place a **Wireless Access Point** on your ME network. On the Overview (or Settings → Networks) the
   network appears under *Enroll a network*; enrolling makes you its owner in ME Control Center.
4. Share it from the network's settings page with a role:

| Role | Can |
|---|---|
| Viewer | view the network (terminal, CPUs, orders), keep a watchlist and see its charts |
| Operator | + craft, cancel own crafts |
| Manager | + cancel any craft, Pattern Studio, deploy patterns, provider settings (priority, blocking, lock mode, terminal visibility) |
| Owner | + share, manage members, rename, remove from ME Control Center |

Wireless Access Points are the network's identity anchors. If two enrolled networks get connected, or one
network's access points end up in separate networks, ME Control Center marks them **Needs attention** and pauses live
access instead of merging permissions. Server admins (operator level ≥ `admin_op_level`) get Owner access
to every network when `admin_override = true`; such actions are recorded in the audit log.

Commands:

```
/mecc pair                  issue a pairing key (players only)
/mecc devices [player]      list paired devices (another player's: admins only)
/mecc revoke <device|all>   revoke a device by ID, or all of your devices (admins may revoke any device)
```

Data is stored in `<world>/mecc/mecc.db` (SQLite, WAL mode). Schema migrations run automatically on
startup, after an automatic backup; see [Backups](#backups).

## Resource terminal

The Terminal page lists everything in the selected network's storage, including craftable resources that
are not in stock. Search accepts plain text (display name in your language, English name, or registry
path), `minecraft:iron_ingot`, `@mod`, `#tag`, `craftable:true`, `type:fluid`, and `amount:<1000`
(`>`, `>=`, `<=`, `=`; `k`/`m`/`b` suffixes). Sort by name, amount, mod, or craftable-first, and choose a
grid density. Searching, sorting, and paging happen on the server against a cached snapshot.

Reading storage is bounded work: one snapshot per network is taken at most every
`resources.snapshot_max_age_seconds`, however many browsers are open. On the server thread ME Control Center only
copies AE2's cached inventory; names, sorting, and search run on ME Control Center worker threads.

## Crafting

Open a craftable resource in the Terminal and choose **Craft** (Operator role or higher):

1. Enter an amount and press **Calculate**. Nothing is submitted yet. AE2 calculates the plan exactly as the
   in-game crafting terminal does; the plan lists what comes from storage, what is crafted, and what is
   missing, plus every crafting CPU and why it could or could not take the job.
2. Pick a CPU or leave it on *Automatic*, then press **Start craft**. The job is submitted on behalf of your
   player, so CPU selection modes apply and AE2 shows its usual in-game notice when you are online.

Every request becomes a **crafting order** before it is handed to AE2, so it stays traceable when the
browser closes. The **Crafting** page lists orders by *Active*, *Completed*, *Failed*, and *Cancelled*, with
progress (as AE2 reports it, never invented), CPU, requester, timing, and history; **Craft again** reopens
the dialog with the same amount, and **Save as preset** keeps it as a saved order. The **CPUs** page shows every crafting CPU, its storage, co-processors,
selection mode, and current job, including jobs started in game. Each job names who requested it: the web
order's creator, or, for jobs started in game, the player AE2 recorded (marked *not paired* when that player never
paired a browser); jobs requested by machines show as unknown. Players may cancel jobs they started in game like
their own orders.

**Crafting tree.** A busy CPU's *Crafting tree* button shows the job as a tree of steps that can be dragged and
zoomed (wheel or buttons), with each step's status: *Crafting* (inputs are in a machine; animated), *Waiting for a
machine* (inputs ready, every machine with the pattern busy), *Ready*, *Waiting for inputs*, *Done*, and ingredients
*From storage*. Finished branches can be hidden and any step folded; a side panel lists what is happening right now.
AE2 only reports the patterns a job still has to run, so ME Control Center remembers every job's steps from the first
time it sees it; for a job first seen mid-way the tree says that earlier steps may be missing.

| Action | Needs |
|---|---|
| Watch CPUs and orders | Viewer |
| Calculate and start crafts, cancel your own | Operator |
| Cancel anyone's craft, including jobs started in game | Manager |

Submissions and cancellations are recorded in the audit log. Refused submissions are kept as *Failed* orders
with the reason (no suitable CPU, missing ingredients, ...).

How ME Control Center follows jobs: for AE2's own crafting CPUs it follows the job's AE2 crafting ID, which survives
server restarts and CPU rebuilds, and tells completed from cancelled jobs reliably. A job whose CPU is not
loaded stays *Running* (shown as not currently visible) and is only given up as *Unknown* after 24 hours
without being seen. CPUs added by other mods are followed by CPU and output; when their job ends, the
outcome is shown as *Unknown* rather than guessed.

Pages update live over a WebSocket (`/ws/v1`) and fall back to polling when it is unavailable. If you use
a reverse proxy, make sure it forwards WebSocket upgrades for `/ws/`.

### Saved orders

The **Saved** tab of the Crafting page holds your personal presets per network (spec section 24): a name, the
resource, the amount, an optional preferred CPU, and notes. **Run** opens the usual craft dialog with them filled
in: the plan is calculated and you confirm it as always; nothing is ever submitted on its own. **Edit**, **Clone**,
and **Delete** do what they say. Saving and editing presets needs the Operator role; each player may keep 200.

## Machines

The **Machines** page lists the machines the network's pattern providers push into (and the multiblocks pattern
buffers belong to), with their status, refreshed every few seconds from loaded chunks only:

| Status | Meaning |
|---|---|
| Working | the machine reports it is working (GregTech), or its contents changed within the last 20 s |
| Waiting | a crafting job waits for it, it holds inputs, but it has not changed for a little while |
| Stuck | the same for 2 minutes: shown in red, with the reason (inputs not accepted, the machine reports it cannot run, or nothing changes) |
| Idle | nothing is expected of it |

A machine counts as expected to work when a crafting CPU waits for something its patterns make, or when its
provider holds items it could not push into it. GregTech machines are judged by their own status and progress;
others by whether their inventory changes. *Alert me when a machine is stuck* adds a personal **Machine stuck**
alert rule (see Alerts).

## Pattern Studio

The **Patterns** page builds AE2 patterns without the in-game Pattern Encoding Terminal:

- **Crafting**: a 3×3 grid; the output comes from the server's crafting recipe that matches it, exactly
  as in the Pattern Encoding Terminal (substitutes and fluid substitutes as options).
- **Processing**: any number of inputs and outputs (up to AE2's 81 / 27), any resource type, exact
  amounts; fluids are entered in buckets. The first output is the primary output.
- **Smithing**: template, base, addition.
- **Stonecutting**: an input and the stonecutter recipe that decides the output.

**Fill from recipe** looks up the server's own recipes (no JEI needed) by what they make, or for the
stonecutter by what they take. Slots can be filled from the network's storage or from every item and fluid
registered on the server, so patterns can name things the network has never held.

The editor checks the pattern against the server as you edit. **Encode to storage** or **Encode & Deploy**
then works like the terminal, in one server tick:

1. the pattern is validated again on the server thread, and the destination provider must be loaded,
   online (powered, with a channel), and have a free slot. ME Control Center never loads chunks for this;
2. exactly one Blank Pattern is taken from the network's storage (with AE2's usual energy cost);
3. the pattern is encoded with AE2's own encoders and placed into the provider, or into storage;
4. the provider slot is read back and must decode to the same pattern.

If any step after 2 fails, the Blank Pattern goes back into storage; nothing is consumed by a refused
request. Every attempt is kept in the **History** tab and in the audit log (`PATTERN_ENCODE`,
`PATTERN_DEPLOY`). The **Providers** tab lists every Pattern Provider of the network with its machine,
location, slots, priority, blocking and lock-crafting modes, and stored patterns. Managers change a provider's
priority, blocking mode, lock-crafting mode, and Pattern Access Terminal visibility from its **Settings** button,
exactly as its in-game screen would (audited as `PROVIDER_SETTING_CHANGE`). The **Duplicates** tab lists every
output that more than one pattern makes, highest priority first, and tells equivalent patterns (same inputs) from
alternative recipes. Duplicates are often deliberate, for parallel machines, so they are only shown, never
flagged as errors.

| Action | Needs |
|---|---|
| View providers and history | Viewer |
| Validate, encode into storage | Manager (`PATTERN_STUDIO`) |
| Encode & deploy into a provider | Manager (`DEPLOY_PATTERNS`) |

**Drafts** are personal: every player keeps up to `max_drafts_per_user` of them, incomplete or not, and
can export a draft as a portable `.mecc-pattern.json` (version-independent resource IDs only) and import it
again, e.g. on another server.

## Insights

Every player keeps a personal **watchlist** per network (Viewer role or higher). Open a resource in the
Terminal and choose **Watch**; watched resources get a ★ on their tile. The **Insights** page (and the
Overview) shows each watched resource's current amount and a trend chart:

- **Quantity** plots the amount; **Change %** plots `(value - first) / first` over the visible range. A
  range that starts at 0 has no percentage; the chart says so instead of dividing by zero.
- Ranges: 1h, 6h, 1d, 7d, 30d, 180d, 360d, and Max (everything retained). Click a legend entry to hide a line;
  tap or hover for time, amount, and change.
- Time while the server was stopped or the network was not loaded is shown as a **gap**, never filled in.

How sampling works: every `sample_interval_seconds` ME Control Center takes **one** storage snapshot per network that has
watched resources (the same cached snapshot the Terminal uses) and records each distinct watched resource
once, however many players watch it. A resource the loaded network does not hold is recorded as 0.
Samples are stored raw and, in the same transaction, folded into 1-minute, 5-minute, and 1-hour buckets
(first, last, min, max, average, sample count), so history survives restarts and long ranges stay fast.
Charts pick the resolution that fits the range.

| Table | Default retention |
|---|---|
| raw samples | 24 hours |
| 1-minute buckets | 7 days |
| 5-minute buckets | 90 days |
| 1-hour buckets | 2 years |

## Alerts

The **Alerts** page (and the Overview's *Current alerts* card) holds your personal rules per network (Viewer role
or higher; spec section 23):

| Rule | Fires when |
|---|---|
| Amount below / above | a resource's stored amount crosses the threshold (a resource missing from storage counts as 0) |
| Amount dropped / rose by | the amount changed by at least the given percent compared with *N* minutes ago (default 60); a window that starts at 0 has no percentage and is not judged |
| Network offline | the network is not loaded or has no power |
| Energy below | stored energy falls below a percentage of capacity |
| All crafting CPUs busy | every CPU runs a job |
| My craft completed / failed | one of your crafting orders ends, optionally only for one resource |
| My craft made no progress for | one of your running jobs (web orders and jobs you started in game) shows the same progress for the given minutes (default 15); reported once per stall, resolved when it moves again |
| A machine is stuck for | a machine a craft waits for has been stuck for the given minutes (default 2), optionally only machines of one kind; reported once per machine |

The server checks rules every `check_interval_seconds` from data it already keeps: the network status from
discovery and one storage snapshot per network (shared with the terminal), however many rules and players.
Condition rules fire once when the condition starts, then report when it clears; a condition that returns within
the rule's cooldown (30 minutes by default) is only announced once the cooldown has passed. A condition that
cannot be judged (network identity ambiguous, energy capacity unknown) changes nothing. Percentage and stall
rules keep their readings in memory: after a restart a percentage rule needs one window of readings before it can
fire, and stalls are measured from the first reading. **Blank Pattern below 32**
adds the common rule in one click.

Alerts appear on the page and, if you turn them on, as browser notifications while ME Control Center is open.
They can also go to a **Discord webhook** and a **generic webhook** (a JSON `POST` with `event`, `type`,
`networkName`, `resourceId`, `resourceName`, `value`, `threshold`, `at`, and a ready-made `text`); *Send test*
checks both. Webhooks may not point into the server's own network (loopback, private, link-local addresses are
refused after DNS resolution; redirects are not followed) unless the admin sets
`allow_private_webhook_targets = true`. Alert history is kept for 30 days. Leaving a network deletes your rules
and saved orders there.

## Auto Restock / Keep Stock

The **Automation** page holds the network's Keep Stock rules (spec section 25): *when this resource falls below
`minimum`, craft it back up to `target`*. Rules belong to the network, not to one player: every **Manager** sees
and edits them, and the crafting jobs are requested as the player who created the rule.

Automation is off by default and an update never switches it on. It runs only when **all** of this holds:

- the server admin set `auto_restock_enabled = true` in `[automation]`;
- the rule itself is enabled (new rules start off);
- its creator still has the Manager role on the network (otherwise the rule turns itself off and the audit log
  says why);
- the rule is past its cooldown (default 30 minutes) and out of failure backoff;
- the resource is not already being crafted, by this network or by anyone in game;
- the network is under `max_active_jobs_per_network` automation jobs (default 2).

A run submits an ordinary crafting order marked `AUTOMATION`, visible in Crafting like any other and cancellable
in the same way; the amount can never exceed the rule's target. A failed submission backs the rule off
exponentially (cooldown doubled per failure, capped at a day) and five failures in a row switch it off; editing a
rule clears the backoff. Every rule change and every automation craft is written to the network's audit log
(`RESTOCK_RULE_CHANGE`, `RESTOCK_CRAFT`, `AUTOMATION_STOPPED`). **Stop all automation** is the kill switch: it
turns every rule of the network off at once.

The same page also lists the **ME Requesters** standing in the network, when the optional
[ME Requester](https://modrinth.com/mod/merequester) mod is installed: those blocks keep stock the same way, so
each of their requests is shown with what it keeps, how much it asks for at a time, what it last saw in storage,
and what it is doing right now. A Manager can empty one request, which is exactly like taking it out of the block
in game and is audited as `REQUESTER_REQUEST_CLEARED`; anything else about a requester is still set in game. The
card is hidden on servers without the mod.

## Network Explorer

The **Explorer** page shows what the network is made of (spec section 26): its devices grouped by block, how many
there are, how many have no power or no channel, the channels and idle power they use, and where they are. The
grid's nodes are walked on request and the result is shared by everyone looking at the network for ten seconds,
so a large network is never walked per browser. Devices are named by the item AE2 itself uses for the node, so
addon blocks (MEGA Cells, ExtendedAE, AdvancedAE, ...) appear without ME Control Center knowing them; kinds that
cannot be told reliably stay *Other devices* instead of being guessed. A graph view of the topology is not part
of this release.

### Icons and names

Best results come from a **content pack** made with the optional ME Control Center Client Exporter (spec section 19):
icons rendered by the real game client, exactly as in an inventory, plus the client's complete translations,
including vanilla's. Icons that move in game move on the web page too: animated textures (lava, sea
lanterns, machine screens) loop exactly as their resource pack defines them, and enchanted items shimmer. Without one, ME Control Center renders icons from static assets on the server.

#### Content packs (recommended)

1. On a PC with the same modpack, put `me-control-center-client-exporter-forge-1.20.1-<version>.jar` into the client's
   `mods/` folder. It is client-side only and never needed to join a server.
2. In game (single player or any server), run `/mecc_export`. English, Chinese and the client's current
   language are always exported; add more with `/mecc_export ja_jp ko_kr`, or `/mecc_export all`.
   A few seconds later `mecc-exports/mecc-content-pack-<hash>.zip` appears in the game directory.
3. Copy the zip into the server's `config/mecc/content-packs/` and restart the server.

The file name identifies the modpack version. The server log and the admin page (*Settings → Server → Content
packs*, spec section 45) say whether a pack matches the installed mods and list what differs; a pack from an older version still covers everything that did not change, so re-export after
updating the modpack. For scripted builds, `-Dmecc.exporter.autorun=en_us,zh_cn -Dmecc.exporter.exitAfter=true`
exports as soon as the client has loaded and then quits.

A single icon can also be replaced by hand: put a PNG at
`assets/<namespace>/mecc_icons/item/<path>.png` (or `.../fluid/...`) in a resource pack in
`config/mecc/resourcepacks/`.

#### Without a content pack

- textures and models come from installed mod jars, `kubejs/assets/`, and any resource packs (folders or
  `.zip`) placed in `config/mecc/resourcepacks/`;
- flat items are composited from their layers, block-shaped models are drawn in the inventory isometric
  view (including models inside most custom model loaders), and fluids use their still texture;
- resources whose look is generated by client code (GregTech material items, AE2 memory cards, banners)
  fall back to a representative texture or a placeholder; the name and registry ID are always shown.

**Dedicated servers do not ship vanilla Minecraft textures or translations**, so without a content pack
vanilla items show placeholder icons and English names until you either set
`[assets] download_vanilla_assets = true` (downloads the client jar and language files once from Mojang's
official servers, verified by SHA-1, into `config/mecc/cache/vanilla/`) or place `client.jar` there
yourself. Mod-provided resources always work.

## Audit log and server administration

Control actions are recorded in the audit log (spec section 35): pairing and revoking devices, enrolling,
renaming, sharing, and removing networks, member changes, starting and cancelling crafts, encoding and
deploying patterns, provider settings, restock rules and the crafts automation submitted, and database backups,
each with time, player, device, network, target, result, and important parameters. Actions that were only allowed through `admin_override` are marked as such.
Tokens and pairing keys are never logged.

- Network **owners** see their network's log on its settings page (*Settings → Networks → Open*).
- **Server admins** (operator level ≥ `admin_op_level`) get a *Server* tab in Settings with the server-wide
  log, the effective configuration as read from `mecc.toml`, the database's schema version and size, its
  backups, and the installed content packs with whether each matches this server. Configuration is edited in `mecc.toml` and applies after a restart; the web page only shows it.

## Backups

The database is `<world>/mecc/mecc.db` plus, while the server runs, `mecc.db-wal` and `mecc.db-shm`.
Backups go to `<world>/mecc/backups/` as single, self-contained files (`mecc-<UTC time>-<reason>.db`); the
newest 10 are kept.

- **Automatic:** before a new ME Control Center version upgrades the database schema, the old database is
  copied to `mecc-...-pre-v<N>.db`.
- **On demand:** *Settings → Server → Back up now*. The copy is taken with SQLite's `VACUUM INTO` while the
  server runs and is always consistent.
- **With the world:** when the server is **stopped**, copying the `mecc/` folder is enough. While it runs,
  do not copy `mecc.db` by itself (recent changes may still be in `mecc.db-wal`); use *Back up now* instead,
  or include all three `mecc.db*` files from a file-system snapshot.

To restore: stop the server, move `mecc.db`, `mecc.db-wal`, and `mecc.db-shm` away, copy the backup to
`<world>/mecc/mecc.db`, and start the server. A backup from an older version is upgraded on startup.
Downgrading is not supported: an older ME Control Center refuses a database written by a newer one.

## Installing on a server

1. Install Forge 47.4.16 on the dedicated server.
2. Put these in `mods/`: `me-control-center-forge-1.20.1-<version>.jar`, AE2 15.4.10, GuideME 20.1.x.
   Use the AE2 **release** jar from Modrinth/CurseForge. The `modmaven.dev` artifact is a developer build
   with Mojang names and crashes a production server (`@Shadow field levels was not located`).
3. Start the server once. ME Control Center creates `config/mecc/mecc.toml`.
4. By default ME Control Center listens on `127.0.0.1:18181` (this machine only). To open it from another device,
   set `host = "0.0.0.0"` and restart, then browse to `http://<server-ip>:18181`.

> For internet exposure, put ME Control Center behind an HTTPS reverse proxy, list the proxy in `trusted_proxies`,
> set `public_base_url`, and enable `require_https_cookie`. See [Reverse proxy](#reverse-proxy).

### Reverse proxy

Keep `host = "127.0.0.1"` and let the proxy terminate TLS. It must pass WebSocket upgrades for `/ws/` and
set `X-Forwarded-For` and `X-Forwarded-Proto`. ME Control Center believes those headers **only** from addresses
in `trusted_proxies`; without that, every player appears to come from the proxy's address and shares one
rate-limit budget.

Caddy (`Caddyfile`), which forwards WebSockets and the headers by itself:

```
mecc.example.org {
    reverse_proxy 127.0.0.1:18181
}
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name mecc.example.org;
    # ssl_certificate / ssl_certificate_key ...
    client_max_body_size 1m;

    location / {
        proxy_pass http://127.0.0.1:18181;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 120s;
    }
}
# in the http block:
map $http_upgrade $connection_upgrade { default upgrade; '' close; }
```

Then in `mecc.toml`:

```toml
[web]
public_base_url = "https://mecc.example.org"
[security]
trusted_proxies = ["127.0.0.1"]
require_https_cookie = true
```

`mecc.toml` (new sections are optional; missing keys use these defaults):

```toml
[web]
enabled = true
host = "127.0.0.1"
port = 18181
max_threads = 32
public_base_url = ""            # shown by /mecc pair, also an allowed origin

[security]
pairing_key_ttl_seconds = 300
trusted_proxies = []            # IPs/CIDRs whose X-Forwarded-For/-Proto are trusted
admin_override = true
admin_op_level = 4
require_https_cookie = false
allowed_origins = []
rate_limit_requests_per_minute = 1200  # per client address: API requests and WebSocket connections (icons exempt)
rate_limit_writes_per_minute = 120     # per player: changes (crafting, patterns, sharing, ...)

[networks]
discovery_interval_seconds = 10

[resources]
snapshot_max_age_seconds = 5    # a network's storage is read at most this often

[assets]
download_vanilla_assets = false # vanilla icons and names; see "Icons and names" above

[crafting]
max_craft_amount = 1000000000   # largest amount one web crafting request may ask for
calculation_timeout_seconds = 60

[patterns]
max_pattern_inputs = 81         # most inputs of one processing pattern (1-81)
max_pattern_outputs = 27        # most outputs of one processing pattern (1-27)
max_drafts_per_user = 200

[analytics]
enabled = true                  # record the history of watched resources
sample_interval_seconds = 15    # 5-300
raw_retention_hours = 24
one_minute_retention_days = 7
five_minute_retention_days = 90
one_hour_retention_days = 730
max_watchlist_entries_per_user = 100

[alerts]
enabled = true
check_interval_seconds = 15     # 5-300
webhooks_enabled = true         # let players send alerts to Discord / generic webhooks
allow_private_webhook_targets = false  # keep false on public servers (blocks webhooks into the server's own network)
max_rules_per_user = 50

[automation]
auto_restock_enabled = false    # Keep Stock rules; off by default, never enabled by an update
check_interval_seconds = 60     # 15-3600
max_active_jobs_per_network = 2 # 1-64; automation jobs running at the same time on one network
max_rules_per_network = 50      # 1-500
```

The system properties `-Dmecc.web.host=...` and `-Dmecc.web.port=...` override the file.


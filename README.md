# ME Control Center

**A web control center for your Applied Energistics 2 networks — served by the Minecraft server itself.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.4.16-orange)](https://files.minecraftforge.net/)
[![AE2](https://img.shields.io/badge/AE2-15.4.10-blue)](https://modrinth.com/mod/ae2)
[![Java](https://img.shields.io/badge/Java-17-red)](https://adoptium.net/)
[![Release](https://img.shields.io/github/v/release/Codaaaaaa/ME-Control-Center?include_prereleases)](https://github.com/Codaaaaaa/ME-Control-Center/releases)

ME Control Center is a **server-side** Forge mod. It runs an embedded web server next to your world, so
players open their ME network in a browser — on a phone, a second monitor, anywhere — and see storage,
crafting, patterns and machines live. Players do **not** need to install anything on their client.

```
/mecc pair  →  AB7K-3M2Q-9RXF  →  http://your-server:18181
```

## Features

| | |
|---|---|
| 🔎 **Resource terminal** | Every item and fluid in the network, with real rendered icons, AE2-style search (`@mod`, `#tag`, `craftable:true`, `amount:<1000`), sorting and paging — all computed server-side on a shared snapshot. |
| 🔨 **Crafting** | Calculate a plan exactly as the in-game terminal does, pick a CPU, start it, and follow it live: order history, progress, a draggable **crafting tree**, cancellation, and saved presets. |
| 🧩 **Pattern Studio** | Author crafting, processing, smithing and stonecutting patterns in the browser, fill them from the server's own recipes (no JEI needed), then encode them to storage or deploy straight into a Pattern Provider. |
| ⚙️ **Machines** | See what every pattern provider pushes into and whether it is working, waiting, stuck or idle. |
| 📈 **Insights** | A personal watchlist per network with history charts from 1 hour to 2 years, sampled once per network however many players watch. |
| 🔔 **Alerts** | Low stock, network offline, low energy, all CPUs busy, craft finished/failed/stalled, machine stuck — delivered to the browser, Discord, or a webhook. |
| 🔁 **Auto Restock** | Opt-in "keep this in stock" rules per network, with cooldowns, failure backoff, an audit trail and a kill switch. Off by default. Integrates [ME Requester](https://modrinth.com/mod/merequester) when installed. |
| 🗺️ **Network Explorer** | What the network is built from: devices, channels, idle power, and what is offline. |
| 👥 **Sharing & roles** | Viewer / Operator / Manager / Owner per network. Everything is authorized, rate limited, and audited. |
| 🌐 **Localized** | English and Simplified Chinese. |

## Quick start

1. Install **Forge 47.4.16** on your dedicated server.
2. Drop into `mods/`: the [latest ME Control Center release](https://github.com/Codaaaaaa/ME-Control-Center/releases),
   **AE2 15.4.10** and **GuideME 20.1.x**.
   Use the AE2 *release* jar from Modrinth/CurseForge — the `modmaven.dev` artifact is a developer build and crashes production servers.
3. Start the server once. `config/mecc/mecc.toml` appears; by default the UI listens on `127.0.0.1:18181`.
   Set `host = "0.0.0.0"` to reach it from your LAN, or put it behind an HTTPS reverse proxy for the internet.
4. In game, run `/mecc pair`, open the site, paste the key.
5. Place a **Wireless Access Point** on your network — it is the network's identity anchor — and enroll it
   from the Overview. You are now its owner; share it with your friends.

> 📘 Full instructions, every page explained, reverse-proxy recipes and the complete `mecc.toml` reference
> live in the **[manual](docs/manual.md)**. The HTTP/WebSocket API is documented in **[docs/api.md](docs/api.md)**.

### Icons and names

Dedicated servers ship no textures. For inventory-perfect icons (animated ones included) and complete
translations, install the optional **ME Control Center Client Exporter** on one client, run `/mecc_export`,
and drop the resulting content pack into `config/mecc/content-packs/`. Without it, icons are rendered on
the server from mod jars and resource packs. See [Icons and names](docs/manual.md#icons-and-names).

## Compatibility

| Target | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.16 |
| Applied Energistics 2 | 15.4.10 (needs GuideME 20.1.x) |
| Java | 17 |

Addon blocks (MEGA Cells, ExtendedAE, AdvancedAE, …) show up without ME Control Center knowing them,
because devices are named by the item AE2 itself uses for the node.

## Architecture

```
core-domain/            Domain: auth, permissions, network identity, resources, repository ports. No dependencies.
platform-api/           Contracts platform adapters implement + ServerThreadGateway.
persistence/            SQLite (WAL) repositories and versioned schema migrations.
assets/                 Game assets as data: language tables, model resolution, offline icon rendering.
web-server/             Embedded Jetty: /api/v1 REST, auth/CSRF/proxy handling, static UI hosting.
runtime/                Config, thread pools, service wiring, network discovery, lifecycle.
web-ui/                 React + TypeScript + Vite frontend.
platform-forge-1.20.1/  Forge 47 / AE2 15 adapter. Builds the distributable mod jar.
buildSrc/               Shared Gradle conventions and the dependency-boundary check.
```

Only `platform-forge-1.20.1` may touch `net.minecraft.*`, `net.minecraftforge.*` or `appeng.*` — enforced by
a Gradle dependency check *and* ArchUnit tests. Porting to another Minecraft version means adding another
`platform-*` module, nothing else.

**Nothing blocks the server thread.** HTTP, WebSocket, database and analytics threads reach game state only
through `ServerThreadGateway`: the task runs on the server thread and copies state into immutable DTOs, the
future completes on a worker thread, at most 256 tasks may queue, a task that misses its timeout is
cancelled and guaranteed not to run, and anything over 5 ms on the server thread is logged.

## Building

Needs a JDK 17 or 21 to run Gradle; the Java toolchain and Node.js are downloaded automatically.

```sh
./gradlew buildMod   # → platform-forge-1.20.1/build/libs/me-control-center-forge-1.20.1-<version>.jar
./gradlew check      # JUnit + ArchUnit + dependency boundaries + Vitest + a 40-player load test
```

The jar bundles every module, the web UI under `mecc-web/`, relocated Jetty and Jackson, and sqlite-jdbc as
a Forge JarJar library. `*-slim.jar` and `*-all.jar` are intermediates, not for distribution.

### Development

```sh
./gradlew :platform-forge-1.20.1:runServer   # dev server with AE2 + GuideME, UI on 0.0.0.0:18181
cd web-ui && npm install && npm run dev      # Vite on :5173, proxies /api to :18181

# Export a content pack from a dev client:
./gradlew :client-exporter-forge-1.20.1:runClient -Pmecc.exporter.autorun=en_us,zh_cn
```

`MECC_DEV_API=http://host:port` points the Vite dev server at another ME Control Center instance.

Releases are cut automatically: every push to `main` bumps `mod_version`, tags it, builds the jar and
publishes a GitHub release ([release.yml](.github/workflows/release.yml)). Use `feat:` for a minor bump and
`feat!:` / `BREAKING CHANGE` for a major one.

## Documentation

- [Manual](docs/manual.md) — pairing, every UI page, server installation, reverse proxy, `mecc.toml`
- [API](docs/api.md) — REST endpoints, error codes, WebSocket events
- [Security](SECURITY.md) — threat model, safe deployment, reporting vulnerabilities
- [Product & technical specification](PRODUCT_TECHNICAL_SPEC.md) — the design baseline

## License

All rights reserved. See [gradle.properties](gradle.properties).

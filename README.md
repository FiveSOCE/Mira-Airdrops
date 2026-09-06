# MiraAirdrops

First-party randomized supply-drop events for the Mira Minecraft ecosystem.

## Download

**Current release: v0.1.6**

- Direct JAR: https://github.com/FiveSOCE/Mira-Airdrops/releases/download/v0.1.6/MiraAirdrops-0.1.6.jar
- All releases: https://github.com/FiveSOCE/Mira-Airdrops/releases

Verified v0.1.6 asset:

- Size: 44,946 bytes
- SHA-256: `dc0abb7b504fa77f281388b5be3ac19460b3450d5c5664703e539967bb9f202e`
- Release target: `dc36bd4044b2aa4967f0afc78fc69c7e741c5a47`

## v0.1.6 hardening

- MiraFactions and WorldEdit integrations are isolated behind conditionally loaded bridges, so either soft dependency can be absent without breaking the base plugin.
- WarZone resolution now uses the Bukkit ServicesManager where MiraFactions actually registers its public API.
- Active events reconcile missing falling entities and missing landed crates every configured interval, respawning the original reward payload instead of leaving unreachable remaining crates.
- Releases are no longer published from ordinary main-branch pushes. Publication only occurs from an explicit `release/*` branch based on the final successful commit.

## Requirements

- Paper 1.21.11
- Java 21
- MiraCore 0.4.1+
- MiraFactions 0.2.18+ when using WARZONE region mode
- WorldEdit 7.3.19+ when capturing a WorldEdit region

MiraFactions and WorldEdit are optional integrations. The selected region mode must have its dependency/configuration available before an event can start.

## Core behavior

- automatic or administrator-started airdrop events
- configurable 30-second inbound warning
- randomly spawns 20-50 crates by default
- no fixed crate locations
- falling chest presentation
- crates never intentionally replace existing blocks
- 1-5 ItemStack rewards per crate by default
- exact ItemStacks are preserved, including custom metadata/PDC
- right-click instantly claims a crate and removes it
- MiraCore RewardService queues rewards before the crate is consumed
- inventory overflow remains safely claimable through MiraCore rewards
- event remains active until every crate is claimed or an administrator cancels it
- remaining crate count broadcasts after claims
- completion and cancellation broadcasts
- standard Mira player-facing prefix
- MiraCore audit entries for inbound/start/claim/cancel/completion actions
- tab completion for the command suite

## Persistence and protection

Active events are persisted in `event-state.yml`.

On a clean restart, transient falling entities/chests are removed after their logical state is saved, then restored on startup. Remaining crate payloads and locations survive the restart.

Airdrop chests use PersistentDataContainer identity and are protected against:

- normal block breaking
- pistons
- explosions
- fire/burn
- fluid replacement
- relevant block physics

This avoids treating ordinary player chests as airdrop crates and reduces duplication or accidental destruction paths.

## Region modes

### MiraFactions WarZone

Set `region.mode: WARZONE`.

MiraAirdrops asks the MiraFactions public API whether sampled locations are WarZone territory. Sampling first checks around the configured world's spawn within `warzone-search-radius`, then falls back toward the world border if necessary.

Relevant config:

```yaml
region:
  mode: WARZONE
  warzone-world: world
  warzone-search-radius: 1500
  warzone-search-attempts: 2000
```

### WorldEdit

1. Make a WorldEdit selection with the wand.
2. Open `/airdrop`.
3. Click **Capture WorldEdit Selection**.
4. Switch the region mode to `WORLDEDIT`.

The captured bounds are copied into MiraAirdrops configuration so the event does not depend on the administrator keeping the WorldEdit selection active.

## Administration

`/airdrop` opens the administration GUI.

The GUI controls:

- start/cancel
- event status
- region mode
- capture WorldEdit selection
- WarZone target world
- minimum/maximum crate count
- minimum/maximum rewards per crate
- automatic event toggle
- automatic event interval
- real ItemStack loot-pool editor

Commands:

- `/airdrop`
- `/airdrop start`
- `/airdrop cancel`
- `/airdrop status`

Alias:

- `/airdrops`

## Permissions

- `miraairdrops.admin` - administration, default OP
- `miraairdrops.status` - view event status, default true

## Configuration files

- `config.yml` - event scheduling, region mode, crate counts and messages
- `loot.yml` - exact ItemStack loot-pool storage
- `event-state.yml` - runtime event persistence generated automatically

## Building

The repository builds with Gradle using Java 21.

```bash
gradle clean test build
```

Output:

```text
build/libs/MiraAirdrops-0.1.6.jar
```

GitHub Actions performs the Java setup, Gradle build/test, artifact upload and release publication.


## v0.1.6 watchdog fix

- MiraFactions 0.2.17 claim/territory lookups no longer call `Location#getChunk()`; claim keys are derived directly from block coordinates.
- WarZone sampling in MiraAirdrops only considers chunks that are already loaded.
- Terrain height checks therefore cannot trigger synchronous chunk generation during airdrop spawning or reconciliation.
- This directly addresses the Paper watchdog stalls caused by WarZone probes waiting for chunk generation on the server thread.



## v0.1.6 fixed-altitude falling crates

- Supply crates now spawn at `event.spawn-y`, default **Y=110**.
- The exact spawn block must be air. Occupied Y=110 positions are skipped and another X/Z column is selected.
- WarZone mode samples loaded MiraFactions WarZone chunks.
- WorldEdit mode uses the saved selection as the X/Z footprint.
- Falling crates use normal gravity and settle as chests on the first valid solid surface below.
- Duplicate active drop columns are rejected.
- Persisted or externally removed crates respawn through the same Y=110 selector.
- The admin GUI now includes **Teleport to nearest Crate**. Each click resolves the nearest currently landed active crate, so claimed/removed crates are skipped automatically.



## v0.1.6 falling sand crate physics

- Airborne airdrops are now `FallingBlock` **sand**, not chest blocks.
- Sand spawns at the configured Y=110 air position and uses vanilla falling-block gravity.
- On a valid landing, MiraAirdrops cancels the sand placement and explicitly places the marked loot chest at the landing block.
- No sand block is left behind after a successful landing.
- Existing Y=110 spawn selection, WarZone/WorldEdit targeting, persistence, reconciliation, and nearest-crate teleport remain intact.


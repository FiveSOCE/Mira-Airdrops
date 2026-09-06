# MiraAirdrops

First-party randomized supply-drop events for the Mira Minecraft ecosystem.

## Download

**Current release: v0.1.2**

- Direct JAR: https://github.com/FiveSOCE/Mira-Airdrops/releases/download/v0.1.2/MiraAirdrops-0.1.2.jar
- All releases: https://github.com/FiveSOCE/Mira-Airdrops/releases

Verified v0.1.2 asset:

- Size: 38,814 bytes
- SHA-256: `428a807674f1bc7738dce3ee039a5a066b316d1964ac20e7dccdbe2e92c52bfd`
- Release target: `7efd9f261bdcf25dfa9f219560d3fd724d1c69bb`

## Requirements

- Paper 1.21.11
- Java 21
- MiraCore 0.4.1+
- MiraFactions 0.2.15+ when using WARZONE region mode
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
build/libs/MiraAirdrops-0.1.2.jar
```

GitHub Actions performs the Java setup, Gradle build/test, artifact upload and release publication.

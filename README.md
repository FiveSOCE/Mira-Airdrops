# MiraAirdrops

[**Download MiraAirdrops v0.1.0**](https://github.com/FiveSOCE/Mira-Airdrops/releases/download/v0.1.0/MiraAirdrops-0.1.0.jar)

First-party Paper 1.21.11 / Java 21 supply-drop events for the Mira ecosystem.

## Core behavior

- periodically or manually starts an airdrop
- public inbound warning before the event
- randomly drops 20-50 falling chest crates
- no fixed chest locations
- chests only materialize into air and never intentionally replace existing blocks
- each chest has 1-5 randomly selected ItemStack rewards from the configured loot pool
- right-clicking an airdrop chest instantly claims it and removes the chest
- MiraCore RewardService guarantees delivery; overflow remains safely available through `/rewards`
- event stays active until every crate is found or an administrator cancels it
- broadcasts the live remaining count after every claim
- private claim messages show the actual rewards found

## Region modes

### MiraFactions Warzone

MiraAirdrops samples random positions inside the configured world's border and accepts only locations where MiraFactions reports Warzone territory.

### WorldEdit

Select a region with the WorldEdit wand, then use the MiraAirdrops GUI to capture it. The selected bounds are copied into MiraAirdrops configuration, so future events use the saved area.

## Administration

`/airdrop` opens the GUI.

The GUI controls:

- start/cancel
- current status
- region mode
- capture WorldEdit selection
- Warzone target world
- min/max crates (20-50)
- min/max loot items per chest (1-5)
- automatic event toggle
- event interval
- ItemStack loot-pool editor

`/airdrop status` is available separately for status checks.

# Modular Storage System — Resource Pack

Custom item models so MSS items are told apart from the vanilla blocks/items they're built on.
The plugin tags each item server-side (`custom_model_data` + `item_model`); this pack supplies the
models the client renders. **Items work fine without the pack** — they just look like their vanilla
material. A pack can only reskin the **inventory/hand icon**, not a block once it's *placed* in the
world.

## Editing the textures (the part you do)

Every item is **one 16×16 PNG** under `assets/modularstoragesystem/textures/item/`, seeded with the
vanilla art it's based on. **Repaint those flats** — that's the whole job. Don't rename them; the
model JSON references them by name.

- Block items (`storage_server`, `drive_bay`, `mss_terminal`, `network_cable`, `security_terminal`)
  render as a **cube** (one texture on all faces — `models/item/<id>.json` uses `cube_all`).
- Everything else (`disk_housing`, `disk_platter_*`, `storage_disk_*`) renders as a **flat sprite**
  (`item/generated`).

If you want a multi-face block or a fancier model, edit that item's `models/item/<id>.json`.

> The seeded textures are unmodified Mojang art and are **gitignored** so they aren't redistributed.
> Once you've painted your own, `git add -f` them (or drop the ignore line in `/.gitignore`).

## Building / shipping it

```sh
python3 tools/generate_pack.py        # regenerates JSON + pack.mcmeta, re-zips; keeps your edited PNGs
```
This produces `resourcepack.zip` (pack.mcmeta at the root). Then either:
- **MSS self-host:** drop it at `plugins/ModularStorageSystem/resourcepack.zip` and set
  `resource-pack.enabled: true`, `handled-by-mss: true`, `self-host.enabled: true` + a reachable
  `public-address`; or
- **External:** host the zip (e.g. a GitHub release asset) and point `resource-pack.url` at it (with
  `self-host.enabled: false`), or use `server.properties` with `handled-by-mss: false`.

## ID / custom_model_data map (must match `ItemManager.applyCustomModel`)

| item | base material | custom_model_data | model id |
|---|---|---|---|
| storage_server | chiseled_tuff | 1001 | storage_server |
| drive_bay | chiseled_tuff_bricks | 1002 | drive_bay |
| mss_terminal | crafter | 1003 | mss_terminal |
| network_cable | heavy_core | 1004 | network_cable |
| security_terminal | observer | 1005 | security_terminal |
| disk_housing | bamboo_pressure_plate | 1006 | disk_housing |
| disk_platter_1k / 4k / 16k / 64k | stone_button / gold_nugget / conduit / heart_of_the_sea | 1011–1014 | disk_platter_* |
| storage_disk_1k / 4k / 16k / 64k | acacia / heavy_weighted / light_weighted / polished_blackstone pressure_plate | 1021–1024 | storage_disk_* |

Exporter & importer are intentionally **not** here — they're player-heads with embedded textures and
already look custom without a pack.

## Cross-version notes

One pack covers MC **1.21 → latest** via the `overlay_legacy/` overlay:
- **1.21.4+** uses `assets/modularstoragesystem/items/<id>.json` (item-model definitions) via the
  `item_model` component.
- **1.21.2–1.21.3** uses the `item_model` component → `models/item/<id>.json` directly.
- **1.21 / 1.21.1** (no `item_model`) uses the `custom_model_data` overrides in
  `overlay_legacy/assets/minecraft/models/item/<base>.json`.

`pack.mcmeta` carries both `supported_formats` (read by ≤1.21.8) and `min_format`/`max_format` (read
by 1.21.9+). **Verify rendering on your exact target versions in-game** — model rendering can't be
checked headlessly, and a couple of legacy bases (e.g. `conduit`) render specially on 1.21/1.21.1.

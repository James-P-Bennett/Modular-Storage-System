# Modular Storage System


![MSS-Banner.png](media%2FMSS-Banner.png)

Modular Storage System is a comprehensive Minecraft storage plugin. It provides a multi-tiered network-based storage system with craftable, expandable storage disks and an intuitive terminal interface. The plugin features automation capabilities through importers and exporters, making it perfect for advanced storage and logistics systems.

## Features


### Core Storage System
- **Network-Based Storage**: Connect Storage Servers, Drive Bays, and Terminals with PoE Cables
- **Multi-Tier Storage Disks**: 1k, 4k, 16k, and 64k capacity tiers with hot-swappable functionality
- **Advanced Terminals**: Search, sort, and manage your items with persistent per-terminal settings
- **Disk Information**: Storage disks display detailed tooltips with capacity, usage, and crafter information
- **Disk Recycling**: Dismantle empty disks to recover components (Shift + Right-click)
![Storage network overview](media%2Fmss_overview.png)



### Automation & Management
- **Automation System**: Import and export items automatically with configurable filters
- **Furnace Integration**: Specialized exporter GUI for targeting fuel and input slots separately
- **Security Controls**: Manage network access with Security Terminals
![Security Terminal GUI](media%2Fsecurity_gui.png)

### Customization & Visuals
- **Optional Resource Pack**: Gives MSS items distinct custom models so they're told apart from the vanilla blocks they reuse — items still work and are fully named/described without the pack
- **Connection-Aware Cable Pipes**: Optionally render placed PoE Cables as pipes that visually join to their neighbours
- **Iron Wrench**: Right-click to rotate an MSS block, Shift + Right-click to dismantle it straight back into your inventory
- **Player-Facing Placement**: MSS blocks orient their front toward whoever places them
- **Terminal Screen Glow**: Optional soft light in front of Terminal / Security Terminal screens (toggle in config)
- **Vanilla Recipes**: Everything is crafted with normal recipes at a crafting table and shows up in the vanilla recipe book
![MSS item skins](media%2Fmss_items_wall.png)

### Performance & Configuration
- **Block Marker Cache**: Intelligent caching system reduces database queries with 5-second TTL
- **Click Rate Limiting**: GUI interactions are rate-limited to prevent database spam
- **Database Backend**: HikariCP connection pooling with **SQLite** (default) or **MySQL/MariaDB**
- **Cross-Version**: A single jar (built against MC 1.21) runs on Paper and its forks across MC 1.21 → latest
- **Comprehensive Configuration**: Customizable via config.yml, lang/en_US.yml, and recipes.yml files

---

## Quick Setup Tutorial

### Basic Network Setup
1. **Craft a Storage Server** - The central hub of your storage network
2. **Place a Drive Bay** - Adjacent to the Storage Server (holds up to 7 storage disks)
3. **Add a Terminal** - Adjacent to the Storage Server or Drive Bay for item access
4. **Craft Storage Disks** - Start with 1k disks and insert them into the Drive Bay
5. **Start Storing** - Right-click the Terminal to access your network storage
![A built storage network](media%2Fmss_system.png)

### Network Components
- **Storage Server**: Must be present in every network, acts as the controller
- **Drive Bay**: Stores your storage disks, can have multiple per network
- **Terminal**: Access point to view and manage stored items
- **PoE Cable**: Extends connections beyond adjacent placement (max 800 cables per network)
![Drive Bay GUI](media%2Fbay_gui.png)

---

## Importers and Exporters

### How They Work
**Importers** automatically pull items from connected containers (chests, furnaces, etc.) into your storage network.
**Exporters** automatically push items from your storage network into connected containers.

### Setup Process
1. **Craft an Importer or Exporter**
2. **Place it adjacent to a container** (chest, furnace, hopper, etc.)
3. **Connect it to your network** using PoE Cables
4. **Right-click to configure** filters and enable/disable
5. **Set filters** by dragging items into filter slots (empty = import/export everything)

### Special Container Support
- **Furnaces**: Dedicated GUI with separate filter sections for fuel (left) and input material (right) slots
  - XP Bottling Feature - Bottle XP generated from smelting
  - 18 filter slots for fuel items
  - 18 filter slots for material items
  - Intelligent slot targeting for automated smelting setups


- **Brewing Stands**: Specialized interfaces for ingredient and bottle slots
- **All Containers**: Works with chests, barrels, hoppers, dispensers, and more
![Furnace automation showcase](media%2Fmss_automation_showcase.png)


### Filter Configuration
- **Empty Filters**: Import/export all items (no restrictions)
- **Item Filters**: Drag items into filter slots to whitelist specific items
- **Multiple Filters**: Combine multiple items for complex filtering logic

---

## Terminal Features

### Search Functionality
- **Item Search**: Click the spyglass button (bottom left) to search for items
- **Type in Chat**: Enter your search term in chat to filter items instantly
- **Persistent Search**: Search terms are saved per terminal location
- **Clear Search**: Shift + Click the spyglass or search again to clear


### Sorting Options
- **Alphabetical Sort**: Default sorting mode, organizes items A-Z
- **Quantity Sort**: Click the name tag button to sort by item count (highest first)
- **Persistent Settings**: Sort preference is saved per terminal location
![Terminal GUI](media%2Fterminal_gui.png)


---

## Items & Storage

### Crafting
All MSS items are crafted with normal recipes at a **vanilla crafting table** and appear in the **vanilla recipe book**. Storage disks can also be assembled from a Storage Disk Housing + a tier Disk Platter. Every recipe — shape, ingredients, and whether it's enabled — is fully configurable in **recipes.yml**.

### Storage Capacities
- **1k Disk**: 8,128 items total (127 items per cell × 64 cells)
- **4k Disk**: 32,512 items total (508 items per cell × 64 cells)
- **16k Disk**: 130,048 items total (2,032 items per cell × 64 cells)
- **64k Disk**: 520,192 items total (8,128 items per cell × 64 cells)

### Storage Disk Features
- **Persistent Tooltips**: Disks display capacity, usage statistics, and crafter information
- **Unique IDs**: Each disk has a unique identifier for recovery purposes
- **Crafter Attribution**: Shows who crafted the disk with UUID tracking
- **Hot-Swappable**: Remove and insert disks without losing data
- **Empty Recycling**: Shift + Right-click empty disks to recover components

---

## Commands

The plugin provides several administrative and utility commands:

| Command                              | Description                                    | Permission                      |
|--------------------------------------|------------------------------------------------|---------------------------------|
| `/mss help`                          | Show help menu with all available commands     | Default                         |
| `/mss give <item> [player]`          | Give MSS items to yourself or another player   | `modularstoragesystem.admin`    |
| `/mss recovery <disk_id>`            | Recover a lost storage disk by its ID          | `modularstoragesystem.recovery` |
| `/mss info`                          | Show plugin statistics and network information | `modularstoragesystem.admin`    |
| `/mss recipes`                       | List all available recipes and their status    | `modularstoragesystem.admin`    |
| `/mss reload [config\|recipes\|all]` | Reload configuration files (incl. the resource pack) | `modularstoragesystem.admin`    |
| `/mss migratedb <sqlite\|mysql>`     | Import all data from the other database into the active one | `modularstoragesystem.admin`    |

### Available Items for `/mss give`
- `server`, `bay`, `terminal`, `security`
- `cable`, `exporter`, `importer`, `wrench`
- `housing`, `platter1k`, `platter4k`, `platter16k`, `platter64k`
- `disk1k`, `disk4k`, `disk16k`, `disk64k`

---

## Configuration Files

The plugin includes three main configuration files:

- **config.yml**: Network limits, database settings, permissions, performance options, and the resource pack
- **lang/en_US.yml**: All plugin messages and text (supports localization)
- **recipes.yml**: Complete recipe definitions with enable/disable options for each recipe

The plugin uses **HikariCP** connection pooling and supports either **SQLite** (default) or **MySQL/MariaDB** — see below.

---

## Database & Storage Backends

All plugin data (networks, storage disks, stored items, drive bays, importers/exporters) lives in a
database — either **SQLite** (default; a `storage.db` file, zero setup) or **MySQL/MariaDB** (for
shared or remote databases). Pick one with `database.type: sqlite` or `mysql` in `config.yml`; MySQL
host/credentials are configured in the same file.

### Switching storage types without data loss

Changing `database.type` by itself does **not** move your data — the new database starts empty (your
old data stays safe in the old database, it just isn't loaded). To carry everything across, use the
built-in importer:

1. Install the new plugin jar and start the server once on your **existing** database so its schema
   upgrades in place.
2. Set `database.type` to the **destination** (e.g. `mysql`) in `config.yml` and **restart** — this
   creates the empty new schema.
3. Run **`/mss migratedb <old-type> confirm`** (e.g. `/mss migratedb sqlite confirm`). This copies
   every table from the old database into the active one.
4. **Restart** the server so all in-memory data reflects the import.

> Do steps 2–4 with **no players online**, and back up first. The migration is atomic — it rolls back
> on any error and leaves the active database unchanged — and is verified to preserve **every row in
> both directions**, including when importing from an older plugin version's database. Item NBT is
> copied verbatim, so data is preserved as long as the Minecraft version stays the same.

### Upgrading from an older version

Just replace the jar and start the server — the plugin runs idempotent, additive schema migrations on
startup that bring older databases up to date **without losing data** (verified, including the legacy
`storage_items` table layout). Your config files are **never overwritten** on update, so customizations
are safe — but new keys are **not** auto-merged into them, so after a feature update:

- **config.yml** — nothing to do. Missing keys fall back to the bundled defaults, so the plugin runs
  unchanged; regenerate it only if you want to see and tweak newly-added options.
- **lang/en_US.yml** — if you've **customized** it, merge the new keys in (or delete the data-folder
  copy to regenerate). A stale language file renders newly-added text as `Missing message: <key>` — the
  data-folder file overrides the bundled one and is not silently backfilled from it.
- **recipes.yml** — if you've **customized** it, merge in new/changed recipes (or delete it to
  regenerate). Recipes that exist only in the new jar are silently not registered, and changed
  ingredients won't reach recipes your file already defines, until you refresh it.

`/mss reload` re-reads your existing files but does **not** pull in new defaults — regenerate or
hand-merge instead.

---

## Requirements
- **Server**: Paper **1.21+** and its forks (Purpur, Pufferfish, …) — **not** Spigot. One jar runs across MC 1.21 → latest.
- **Java**: OpenJDK 21 or higher

## Installation
1. Download the latest release
2. Place the .jar file in your server's plugins folder
3. Restart your server
4. Configure the plugin via the generated config files as needed
5. *(Optional)* Enable the resource pack in `config.yml` to give MSS items their custom skins

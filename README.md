# Modular Storage System


![MSS-Banner.png](media%2FMSS-Banner.png)

Modular Storage System is a comprehensive Minecraft storage plugin. It provides a multi-tiered network-based storage system with craftable, expandable storage disks and an intuitive terminal interface. The plugin features automation capabilities through importers and exporters, making it perfect for advanced storage and logistics systems.

## Features


### Core Storage System
- **Network-Based Storage**: Connect Storage Servers, Drive Bays, and Terminals with Network Cables
- **Multi-Tier Storage Disks**: 1k, 4k, 16k, and 64k capacity tiers with hot-swappable functionality
- **Advanced Terminals**: Search, sort, and manage your items with persistent per-terminal settings
- **Disk Information**: Storage disks display detailed tooltips with capacity, usage, and crafter information
- **Disk Recycling**: Dismantle empty disks to recover components (Shift + Right-click)
![TerminalItemHover.png](media%2FTerminalItemHover.png)



### Automation & Management
- **Automation System**: Import and export items automatically with configurable filters
- **Furnace Integration**: Specialized exporter GUI for targeting fuel and input slots separately
- **Security Controls**: Manage network access with Security Terminals
![Autocrafting.png](media%2FAutocrafting.png)

### Crafting & Recipes
- **Alternative Recipes**: Craft disks using shapeless recipes (Housing + Platter) in 2x2 or 3x3 grids
- **Recipe Book Integration**: All recipes can be discovered and visible in the vanilla recipe book

### Performance & Configuration
- **Block Marker Cache**: Intelligent caching system reduces database queries with 5-second TTL
- **Click Rate Limiting**: GUI interactions are rate-limited to prevent database spam
- **Database Backend**: Uses HikariCP connection pooling with SQLite for performance and reliability
- **Comprehensive Configuration**: Customizable via config.yml, lang.yml, and recipes.yml files

---

## Quick Setup Tutorial

### Basic Network Setup
1. **Craft a Storage Server** - The central hub of your storage network
2. **Place a Drive Bay** - Adjacent to the Storage Server (holds up to 7 storage disks)
3. **Add a Terminal** - Adjacent to the Storage Server or Drive Bay for item access
4. **Craft Storage Disks** - Start with 1k disks and insert them into the Drive Bay
5. **Start Storing** - Right-click the Terminal to access your network storage
![BasicSetup.png](media%2FBasicSetup.png)

### Network Components
- **Storage Server**: Must be present in every network, acts as the controller
- **Drive Bay**: Stores your storage disks, can have multiple per network
- **Terminal**: Access point to view and manage stored items
- **Network Cable**: Extends connections beyond adjacent placement (max 800 cables per network)
![DriveBayGUI.png](media%2FDriveBayGUI.png)

---

## Importers and Exporters

### How They Work
**Importers** automatically pull items from connected containers (chests, furnaces, etc.) into your storage network.
**Exporters** automatically push items from your storage network into connected containers.

### Setup Process
1. **Craft an Importer or Exporter**
2. **Place it adjacent to a container** (chest, furnace, hopper, etc.)
3. **Connect it to your network** using Network Cables
4. **Right-click to configure** filters and enable/disable
5. **Set filters** by dragging items into filter slots (empty = import/export everything)

### Special Container Support
- **Furnaces**: Dedicated GUI with separate filter sections for fuel (left) and input material (right) slots
  - XP Bottling Feature - Bottle XP generated from smelting
  - 18 filter slots for fuel items
  - 18 filter slots for material items
  - Intelligent slot targeting for automated smelting setups
  ![FurnaceImporterBottleXP.png](media%2FFurnaceImporterBottleXP.png)


- **Brewing Stands**: Specialized interfaces for ingredient and bottle slots
- **All Containers**: Works with chests, barrels, hoppers, dispensers, and more
![FurnaceAutomation.png](media%2FFurnaceAutomation.png)
![PotionsAutomation.png](media%2FPotionsAutomation.png)


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
![TerminalSortingFeature.png](media%2FTerminalSortingFeature.png)


---

## Items and Crafting Recipes

### Network Blocks

#### Server
The core controller of every storage network.
```
NRN    N = Netherite Ingot
RAR    R = Redstone  
NRN    A = Amethyst Shard
```

#### Drive Bay  
Houses up to 7 removable storage disks.
```
BOB    B = Copper Block
CCC    C = Chest
BOB    O = Crying Obsidian
```

#### Terminal
Access point for viewing and managing stored items.
```
TTT    T = Tinted Glass
QEQ    Q = Quartz
DRD    D = Diamond, R = Redstone, E = Emerald
```

#### Security Terminal
Controls network access and permissions.
```
IEI    I = Iron Bars
ITI    E = Ender Pearl
III    T = MSS Terminal
```

### Network Infrastructure

#### Network Cable (yields 4)
Extends network connections over distances.
```
GGG    G = Glass Pane
CRC    C = Copper Ingot
GGG    R = Redstone
```

#### Exporter
Automatically exports items from network to containers.
```
R1R    R = Comparator
csc    1 = 1k Disk Platter
RhR    c = Network Cable, s = Sticky Piston, h = Hopper
```

#### Importer  
Automatically imports items from containers to network.
```
R1R    R = Comparator
cpc    1 = 1k Disk Platter  
RhR    c = Network Cable, p = Piston, h = Hopper
```

### Storage Components

#### Storage Disk Housing
Base component for all storage disks.
```
TTT    T = Tinted Glass
I I    I = Iron Ingot
IWI    W = Wind Charge
```

#### 1k Disk Platter
```
RGR    R = Redstone
GRG    G = Gold Ingot
RGR
```

#### 4k Disk Platter
```
BGB    B = Blaze Rod, G = Gold Ingot
111    1 = 1k Disk Platter
ERE    E = Ender Pearl, R = Resin Brick
```

#### 16k Disk Platter  
```
BDB    B = Breeze Rod, D = Diamond
444    4 = 4k Disk Platter
PRP    P = Ender Pearl, R = Resin Brick
```

#### 64k Disk Platter
```
SES    S = Shulker Shell, E = Ender Eye
666    6 = 16k Disk Platter  
PRP    P = Ender Pearl, R = Resin Brick
```

### Storage Disks

#### Standard Recipes (3x3 Crafting Table)
All storage disks follow this pattern:
```
TTT    T = Tinted Glass
IPI    I = Iron Ingot, P = Disk Platter (tier)
IWI    W = Wind Charge
```

#### Alternative Shapeless Recipes
- **1k Disk**: Storage Disk Housing + 1k Disk Platter
- **4k Disk**: Storage Disk Housing + 4k Disk Platter  
- **16k Disk**: Storage Disk Housing + 16k Disk Platter
- **64k Disk**: Storage Disk Housing + 64k Disk Platter

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
| `/mss reload [config\|recipes\|all]` | Reload configuration files                     | `modularstoragesystem.admin`    |

### Available Items for `/mss give`
- `storage_server`, `drive_bay`, `mss_terminal`, `security_terminal`
- `network_cable`, `exporter`, `importer`
- `storage_disk_housing`
- `disk_platter_1k`, `disk_platter_4k`, `disk_platter_16k`, `disk_platter_64k`
- `storage_disk_1k`, `storage_disk_4k`, `storage_disk_16k`, `storage_disk_64k`

---

## Configuration Files

The plugin includes three main configuration files:

- **config.yml**: Network limits, database settings, permissions, and performance options
- **lang/en_US.yml**: All plugin messages and text (supports localization)
- **recipes.yml**: Complete recipe definitions with enable/disable options for each recipe

The plugin uses **HikariCP** with SQLite for efficient database operations and connection pooling, ensuring optimal performance even with large storage networks.

---

## Requirements
- **Server**: Spigot/Paper 1.21+  
- **Java**: OpenJDK 21 or higher

## Installation
1. Download the latest release
2. Place the .jar file in your server's plugins folder  
3. Restart your server
4. Configure the plugin via the generated config files as needed

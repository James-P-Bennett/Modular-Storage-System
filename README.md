# Mass Storage Server

Mass Storage Server is a comprehensive Minecraft storage plugin inspired by Applied Energistics 2 and Refined Storage. It provides a multi-tiered network-based storage system with craftable, expandable storage disks and an intuitive terminal interface. The plugin features automation capabilities through importers and exporters, making it perfect for advanced storage and logistics systems.

## Features

- **Network-Based Storage**: Connect Storage Servers, Drive Bays, and Terminals with Network Cables
- **Multi-Tier Storage Disks**: 1K, 4K, 16K, and 64K capacity tiers with hot-swappable functionality  
- **Advanced Terminals**: Search, sort, and manage your items with persistent per-terminal settings
- **Automation System**: Import and export items automatically with configurable filters
- **Security Controls**: Manage network access with Security Terminals
- **Disk Recycling**: Dismantle empty disks to recover components (Shift + Right-click)
- **Alternative Recipes**: Craft disks using shapeless recipes (Housing + Platter) in 2x2 or 3x3 grids
- **Recipe Book Integration**: All recipes are unlocked and visible in the vanilla recipe book
- **Database Backend**: Uses HikariCP connection pooling with SQLite for performance and reliability
- **Comprehensive Configuration**: Customizable via config.yml, lang.yml, and recipes.yml files

---

## Quick Setup Tutorial

### Basic Network Setup
1. **Craft a Storage Server** - The central hub of your storage network
2. **Place a Drive Bay** - Adjacent to the Storage Server (holds up to 7 storage disks)
3. **Add a Terminal** - Adjacent to the Storage Server or Drive Bay for item access
4. **Craft Storage Disks** - Start with 1K disks and insert them into the Drive Bay
5. **Start Storing** - Right-click the Terminal to access your network storage

### Network Components
- **Storage Server**: Must be present in every network, acts as the controller
- **Drive Bay**: Stores your storage disks, can have multiple per network
- **Terminal**: Access point to view and manage stored items
- **Network Cable**: Extends connections beyond adjacent placement (max 800 cables per network)

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
- **Furnaces**: Exporters can target fuel and input slots separately
- **Brewing Stands**: Specialized interfaces for ingredient and bottle slots
- **All Containers**: Works with chests, barrels, hoppers, dispensers, and more

---

## Items and Crafting Recipes

### Network Blocks

#### Storage Server
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
csc    1 = 1K Disk Platter
RhR    c = Network Cable, s = Sticky Piston, h = Hopper
```

#### Importer  
Automatically imports items from containers to network.
```
R1R    R = Comparator
cpc    1 = 1K Disk Platter  
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

#### 1K Disk Platter
```
RGR    R = Redstone
GRG    G = Gold Ingot
RGR
```

#### 4K Disk Platter
```
BGB    B = Blaze Rod, G = Gold Ingot
111    1 = 1K Disk Platter
ERE    E = Ender Pearl, R = Resin Brick
```

#### 16K Disk Platter  
```
BDB    B = Breeze Rod, D = Diamond
444    4 = 4K Disk Platter
PRP    P = Ender Pearl, R = Resin Brick
```

#### 64K Disk Platter
```
SES    S = Shulker Shell, E = Ender Eye
666    6 = 16K Disk Platter  
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

#### Alternative Shapeless Recipes (Works in 2x2 Survival Grid)
- **1K Disk**: Storage Disk Housing + 1K Disk Platter
- **4K Disk**: Storage Disk Housing + 4K Disk Platter  
- **16K Disk**: Storage Disk Housing + 16K Disk Platter
- **64K Disk**: Storage Disk Housing + 64K Disk Platter

### Storage Capacities
- **1K Disk**: 8,128 items total (127 items per cell × 64 cells)
- **4K Disk**: 32,512 items total (508 items per cell × 64 cells)
- **16K Disk**: 130,048 items total (2,032 items per cell × 64 cells)  
- **64K Disk**: 520,192 items total (8,128 items per cell × 64 cells)

---

## Commands

The plugin provides several administrative and utility commands:

| Command | Description | Permission |
|---------|-------------|------------|
| `/mss help` | Show help menu with all available commands | Default |
| `/mss give <item> [player]` | Give MSS items to yourself or another player | `massstorageserver.admin` |
| `/mss recovery <disk_id>` | Recover a lost storage disk by its ID | `massstorageserver.recovery` |
| `/mss info` | Show plugin statistics and network information | `massstorageserver.admin` |
| `/mss recipes` | List all available recipes and their status | `massstorageserver.admin` |
| `/mss reload [config\|recipes\|all]` | Reload configuration files | `massstorageserver.admin` |

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
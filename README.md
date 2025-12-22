# Zone Cleaner
### The Ultimate Lag-Free World Editor

[![MCreaHub](https://img.shields.io/badge/MCreaHub-Partner-blueviolet?style=for-the-badge)](https://mcreahub.pages.dev/)
[![Modrinth](https://img.shields.io/badge/Modrinth-Available-green?style=for-the-badge&logo=modrinth)](https://modrinth.com/mod/zone-cleaner)
[![CurseForge](https://img.shields.io/badge/CurseForge-Available-orange?style=for-the-badge&logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/zone-cleaner)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.4-blue?style=for-the-badge)](https://neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-1.21.x-cream?style=for-the-badge&logo=fabric)](https://fabricmc.net/)
[![Forge](https://img.shields.io/badge/Forge-1.19--1.20-red?style=for-the-badge)](https://files.minecraftforge.net/)
[![GitHub](https://img.shields.io/badge/GitHub-Source-black?style=for-the-badge&logo=github)](https://github.com/Dreyka-Oas/ZoneCleaner)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](https://opensource.org/licenses/MIT)

**Delete millions of blocks without dropping a single TPS.**

Zone Cleaner is a server-side administration tool designed to perform massive world editing operations safely. Unlike WorldEdit or vanilla commands which can freeze your server, Zone Cleaner uses a **Time-Slicing Engine** and **Physics Suppression** to clean the world while you keep playing.

[Report a Bug](https://github.com/Dreyka-Oas/ZoneCleaner/issues)

---

### ⚡ The "Ninja" Engine (New in 3.1.0)

The mod has been completely re-architected. It now bypasses standard block updates using **Flag 18**.
- **No Light Recalculation**: Removing a mountain won't freeze the server calculating shadows.
- **No Physics**: Sand and Gravel won't fall, water won't flow, and observers won't trigger.
- **Zero Lag**: The operation is sliced into tiny micro-tasks spread across server ticks.

---

### 🎚️ Aggression Levels (1 to 5)

You are in control. Choose how much CPU power the task is allowed to use by adding a number at the end of the command.

| Level | Name | Description | Use Case |
| :---: | :--- | :--- | :--- |
| **1** | **Ghost** | **0.5ms / tick**. Invisible background task. | Playing while cleaning. Zero impact. |
| **2** | **Safe** | **5ms / tick**. Smooth and fluid. | Default balanced usage. |
| **3** | **Normal** | **20ms / tick**. Standard speed. | Admin maintenance. |
| **4** | **Fast** | **50ms / tick**. Uses the full tick budget. | When you want it done quickly. |
| **5** | **NUCLEAR** | **500ms / tick**. **Forces Lag.** | AFK massive destruction. 10x faster than normal. |

---

### 🛠️ Commands
*Requires Operator Level 4.*

#### 1. Define the Zone
For massive areas, use these commands to mark your corners (like WorldEdit).
- `/pos_start`: Sets the first corner at your feet.
- `/pos_end`: Sets the second corner at your feet.

#### 2. Remove Blocks
**Syntax:** `/remove block <coords> <block> [level]`

*Clean all **Sand** in a 5000-block radius from Bedrock to Sky (Level 4 - Fast):*
```mcfunction
/remove block ~ min ~ ~5000 max ~5000 minecraft:sand 4
```

*Clean a specific zone defined by pos_start/end (Level 1 - Silent):*
```mcfunction
/remove block pos_start pos_end minecraft:stone 1
```

*Manual coordinates (Level 5 - Nuclear):*
```mcfunction
/remove block 0 60 0 100 100 100 minecraft:dirt 5
```

#### 3. Remove Entities
Instantly wipe entities in the selected area.
- `/remove entity pos_start pos_end all` (Kills all mobs/items, keeps players)
- `/remove entity ~ -64 ~ ~100 320 ~100 minecraft:zombie`

#### 4. Utilities
- `/remove stop`: **Emergency Stop.** Instantly kills the current background task.
- `/remove undo`: Reverts the last operation block-by-block using the safe scheduler.

---

### 📉 Compatibility
- **Server-Side Only**: Clients do not need to install the mod to join.
- **Language**: Messages are automatically translated based on the player's client language (English & French included).

---

<details>
<summary><strong>📚 Documentation for versions older than 3.1.0</strong></summary>

### Performance
**Zone Cleaner** was engineered from the ground up to respect your server's performance. While other tools execute commands instantly and cause massive lag spikes, Zone Cleaner breaks down every large task into tiny, manageable pieces.

It intelligently monitors your server's Mean-Spike-per-Tick (MSPT) and dynamically allocates a "work budget" for each tick. If the server is busy, it does less. If the server is idle, it does more. The result? Your server stays at a perfect **20 TPS**, even while clearing millions of blocks.

### Features
- **Dynamic Anti-Lag Engine**: A scheduler that processes operations progressively based on real-time server performance.
- **Smart Undo System**: Reverts the last 5 block removal operations using the same lag-free system.
- **Action Bar Progress Updates**: Get real-time percentage feedback on your action bar.
- **Flexible Selections**: Use absolute coordinates, relative positions (`~`), or world limits (`min`/`max`). For larger areas, set points with `/pos_start` and `/pos_end`.
- **Total Control**: Each player can only run one task at a time. Stop your current operation instantly with `/remove stop`.

### Commands
All commands are server-side and require **operator permission**.

**Position Selection**
For large areas, it's easier to set start and end points first.
- `/pos_start`: Saves your current position as the first corner.
- `/pos_end`: Saves your current position as the second corner.

**Block Removal**
The core block removal command.

*To clear a 100x100 area of stone at y=64:*
```mcfunction
/remove block 0 64 0 100 64 100 minecraft:stone
```

*To remove all sand in a 50-block radius from your position, from world bottom to top:*
```mcfunction
/remove block ~-50 min ~-50 ~50 max ~50 minecraft:sand
```

*To drain an ocean using saved positions:*
```mcfunction
# Go to one corner of the ocean and run /pos_start
# Go to the opposite corner and run /pos_end
/remove block pos_start pos_end minecraft:water
```

**Entity Removal**
Quickly and safely remove entities from a zone. This command is instantaneous and does not use the anti-lag scheduler.

*To remove all zombies in a large area:*
```mcfunction
/remove entity 0 0 0 1000 255 1000 minecraft:zombie
```

*To clear all hostile mobs from a previously saved zone:*
```mcfunction
# Set your /pos_start and /pos_end first
/remove entity pos_start pos_end all
```
*(This will not remove players or non-hostile entities unless specified.)*

**Utilities**
- `/remove undo`: Restores blocks from your last removal operation using the lag-free engine. Can be used up to 5 times.
- `/remove stop`: Immediately cancels your currently running task.

### Technical Details
Zone Cleaner's performance is not magic; it's engineering.

- **Server-Side Only**: No client installation is required.
- **Tick-Based Scheduler**: Hooks into the `ServerTickEvent.Post` to perform work after the main game logic has finished for the tick.
- **Dynamic Workload Balancing**:
  1. Measures server MSPT in real-time.
  2. Maintains a rolling average to smooth out momentary spikes.
  3. Calculates a nanosecond "work budget" for each tick based on available performance.
  4. The task yields if it exceeds its budget, resuming automatically on the next tick.
- **Safe & Synchronous**: All block operations occur on the main server thread, guaranteeing world save integrity and preventing concurrency issues.
- **Efficient Undo**: Uses a memory-efficient `BlockSnapshot` record and a `LinkedList` for fast history tracking of the last 5 operations.

This architecture ensures that Zone Cleaner is a powerful and **safe** tool for any production server.
</details>

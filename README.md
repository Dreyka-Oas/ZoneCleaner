# Zone Cleaner
### The Lag-Free World Editor

[![Modrinth](https://img.shields.io/badge/Modrinth-Available-green?style=for-the-badge&logo=modrinth)](https://modrinth.com/mod/zone-cleaner)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.8-blue?style=for-the-badge)](https://neoforged.net/)
[![GitHub](https://img.shields.io/badge/GitHub-Source-black?style=for-the-badge&logo=github)](https://github.com/Dreyka-Oas/ZoneCleaner)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](https://opensource.org/licenses/MIT)

**Tired of server-crashing lag from large-scale edits? Zone Cleaner is the solution.**  
Perform massive block and entity clearing operations with **zero lag**. This isn't just a promise—it's a guarantee, built on a powerful, server-aware task scheduler.

[Report a Bug or Suggest a Feature](https://github.com/Dreyka-Oas/ZoneCleaner/issues)

---

### Performance
**Zone Cleaner** was engineered from the ground up to respect your server's performance. While other tools execute commands instantly and cause massive lag spikes, Zone Cleaner breaks down every large task into tiny, manageable pieces.

It intelligently monitors your server's Mean-Spike-per-Tick (MSPT) and dynamically allocates a "work budget" for each tick. If the server is busy, it does less. If the server is idle, it does more. The result? Your server stays at a perfect **20 TPS**, even while clearing millions of blocks.

---

### Features
- **Dynamic Anti-Lag Engine**: A scheduler that processes operations progressively based on real-time server performance.
- **Smart Undo System**: Reverts the last 5 block removal operations using the same lag-free system.
- **Action Bar Progress Updates**: Get real-time percentage feedback on your action bar.
- **Flexible Selections**: Use absolute coordinates, relative positions (`~`), or world limits (`min`/`max`). For larger areas, set points with `/pos_start` and `/pos_end`.
- **Total Control**: Each player can only run one task at a time. Stop your current operation instantly with `/remove stop`.

---

### Commands
All commands are server-side and require **operator permission**.

<details>
<summary>Position Selection</summary>

For large areas, it's easier to set start and end points first.
- `/pos_start`: Saves your current position as the first corner.
- `/pos_end`: Saves your current position as the second corner.
</details>

<details>
<summary>Block Removal</summary>

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
</details>

<details>
<summary>Entity Removal</summary>

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
</details>

<details>
<summary>Utilities</summary>

- `/remove undo`: Restores blocks from your last removal operation using the lag-free engine. Can be used up to 5 times.
- `/remove stop`: Immediately cancels your currently running task.
</details>
<br>

---

<details>
<summary>Technical Details</summary>

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

---

Thanks to **[MCreaHub](https://mcreahub.pages.dev/)** for facilitating the initial project setup.
The mod has since been completely re-architected for exceptional performance.

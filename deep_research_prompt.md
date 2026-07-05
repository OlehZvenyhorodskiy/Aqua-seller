# Prompt for Deep Research AI

Copy the text below and paste it into your Deep Research AI (e.g., Gemini Advanced, ChatGPT Deep Research, or Claude) to analyze the repository and generate a comprehensive diagnostic report.

---

```text
Act as an expert Minecraft Spigot/Paper Plugin Developer and Java Code Auditor. Your goal is to analyze the source code of the "Aqua-seller" plugin and produce a detailed report outlining hidden bugs, architectural flaws, performance bottlenecks, and recommendations for code optimization to make the plugin run extremely smoothly.

Repository Link: https://github.com/OlehZvenyhorodskiy/Aqua-seller

### 1. Context & Known Bugs to Investigate
Here are several critical bugs and issues that have already been identified in the codebase. Please analyze them in detail and provide structural solutions for them:

- **Lack of Level/Progress Administration Commands:**
  There is currently no command (e.g., `/aquaseller setlevel <player> <profession> <level>` or `/aquaseller addprogress`) to programmatically manage a player's profession level or progress. This makes it impossible for external quest plugins (like BeautyQuests, ODailyQuests, etc.) or administrators to manually level up a player or integrate quest milestones.
  *File reference:* `src/main/java/org/yarchez/aquaseller/util/AquaSellerAdminCommand.java`

- **player-data.yml File Overwrite / Race Condition:**
  `DataStore.java` loads configuration data once and keeps it in-memory. However, every time a player sells an item or kills a mob, the plugin calls `save()`, which serializes the entire in-memory representation back to the file. If any external system or quest plugin attempts to modify `player-data.yml` on disk to level up a player, their changes are instantly overwritten and deleted by the next in-memory save operation.
  *File reference:* `src/main/java/org/yarchez/aquaseller/util/DataStore.java`

- **Case-Sensitivity Inventory Refresh Bug:**
  In `SellerGui.java` under `refreshOpenProfessionMenu(String profession, Player p)`, the category refresh logic uses `categories.get(categoryId)`. The `categoryId` is parsed from strings like `"cat:RUDY"`, `"cat:KILLER"`, `"cat:FISHMAN"`, yielding `"RUDY"`, `"KILLER"`, and `"FISHMAN"`. However, the `categories` map stores keys in lowercase (e.g., `"rudy"`, `"killer"`, `"fishman"`). As a result, the lookup returns `null`, and the open GUI never refreshes when the player levels up.
  *File reference:* `src/main/java/org/yarchez/aquaseller/gui/SellerGui.java#L1714-L1728`

- **Missing Open Menu Refresh for Killer Profession:**
  In `KillerProgressListener.java`, when a player levels up, the code reloads the templates (`plugin.sellerGui().reload()`) but never calls the menu refresh for the player. The player remains in the old UI state until they close and reopen the GUI manually.
  *File reference:* `src/main/java/org/yarchez/aquaseller/util/KillerProgressListener.java#L104-L113`

- **Level 1 -> 2 Level Up / Quest Reset Bug (e.g., 50k submission gets stuck):**
  A player trying to level up (e.g., submitting 50,000 items at level 1 to reach level 2) gets stuck, and the level-up is never triggered.
  *Root Cause:* In `DataStore.java` under `getProfessionTotalSold`, there is a self-healing block:
  ```java
  if (progressSum > 0L && stored != progressSum) {
      cfg.set(path, progressSum);
      save();
      return progressSum;
  }
  ```
  If a player's `total_sold` is modified by an external system or doesn't match the sum of tier progresses, the very next sale sets `progress.1` to a small value (like 1). Since `progressSum` (1) > 0 and `stored` (50001) != `progressSum` (1), the plugin instantly overwrites and wipes the player's total progress, resetting `total_sold` to 1. This prevents the player from ever reaching the transition threshold (e.g., 50,000) and halts the level-up.

- **Dead/Duplicated Code:**
  There is a legacy method `handleRudyProgressAndLevelUp` in `SellerGui.java` which contains hardcoded logic/thresholds that differ from `handleProfessionProgressAndLevelUp`. This method is completely unused but remains in the class.

---

### 2. Deep Research & Audit Objectives
Please perform a deep dive into the repository and investigate the following areas:

#### A. Performance & Lag Prevention (TPS Optimization)
1. **Synchronous File I/O:** Analyze `DataStore.java` and `NpcManager.java`. The `save()` method is called synchronously on the main server thread whenever items are sold or progress is made. On a live server with many players, this will cause major TPS drops and lag spikes. How can we implement an asynchronous, thread-safe queuing system or cache for data saving?
2. **Database Migration:** Provide a schema and code structure to migrate player data from YAML (`player-data.yml`) to a SQLite/MySQL database (using HikariCP) to prevent file lockups and data corruption.
3. **PAPI Placeholder Performance:** Analyze the PlaceholderAPI hook (`AquaSellerPlaceholders.java`). Are there any heavy operations (like reading from files or computing averages) happening on every placeholder request, which could lag holograms or tab lists?
4. **Crop Growth Event Overhead:** In `CropAcceleratorListener.java` under the `BlockGrowEvent` handler (`onBlockGrow`), the code runs `getBestAccelerator`, which retrieves all players in the world, calculates locations, and iterates through each player's entire inventory (`player.getInventory().getContents()`) to find the best crop accelerator. Since `BlockGrowEvent` is triggered extremely frequently on active servers, this leads to continuous array allocations, massive garbage collection overhead, and severe performance bottlenecks. How can this be optimized (e.g. using spatial hashing, block data metadata caches, or a radius-based entity lookup)?

#### B. Architectural & OOP Improvements
1. **Duplicated Code:** Identify code duplication between the different professions (`rudy`, `fermer`, `killer`, `fishman`). Suggest an abstract `Profession` class or interface to unify the progress, leveling, and reward actions.
2. **GUI Framework:** The custom GUI implementation uses a raw `InventoryHolder` and manual slot mapping. Recommend a cleaner GUI library pattern or structure to prevent inventory click bypasses (where players steal items from GUI).

#### C. Stability & Citizens Integration
1. **NPC Duplication & Safety:** Review `NpcManager.java` for Citizens NPC management. Check if the lookup, duplicate cleaning, and skin/equipment update logic is safe, or if it can cause memory leaks/unhandled exceptions when Citizens is not fully loaded.

Please structure your response as a complete, professional code audit document with concrete code refactoring suggestions, Maven dependencies if needed, and clean implementations of the fixes.
```

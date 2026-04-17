@AGENTS.md

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**Microbot** is a RuneScape automation framework built on top of the RuneLite client. It provides a plugin-based architecture where automation scripts run as background threads, interacting with the game through a comprehensive utility system and event-driven cache management.

This is a multi-module Gradle project with Java 11 as the target version. The main automation code lives in `runelite-client/src/main/java/net/runelite/client/plugins/microbot/`.

**Config UI note:** Microbot plugins use the custom `MicrobotConfigPanel` (under `plugins/microbot/ui`) for config rendering. Any Microbot config UI tweaks—buttons, layouts, or controls—belong there rather than RuneLite’s default config panel.

---

## Build and Test Commands

### Building the Project

```bash
# Compile only (fastest, recommended for development)
./gradlew :runelite-client:compileJava

# Full build with tests
./gradlew build

# Build without tests (faster)
./gradlew build -x test

# Clean build
./gradlew clean build
```

### Running the Client

```bash
# Run from Gradle
./gradlew :runelite-client:run

# Or launch the built jar
java -jar runelite-client/build/libs/microbot-<version>.jar
```

### Working Directory

The repository root is `/home/mimosa/IdeaProjects/microbot/GameObjects/`. Plugin code is located at:
```
/home/mimosa/IdeaProjects/microbot/GameObjects/runelite-client/src/main/java/net/runelite/client/plugins/microbot/
```

---

## Core Architecture

### Plugin-Script Pattern

Every automation plugin follows this structure:

1. **Plugin class** (`@PluginDescriptor`): RuneLite lifecycle management
2. **Script class** (extends `Script`): Background thread executing automation logic
3. **Config class** (extends `Config`): Plugin configuration UI

**File References:**
- `example/ExamplePlugin.java` - Plugin template
- `example/ExampleScript.java` - Script template

**Key Pattern:**
```java
@PluginDescriptor(name = "My Bot", description = "...")
public class MyBotPlugin extends Plugin {
    @Inject MyBotScript script;

    @Override
    protected void startUp() {
        script.run();  // Starts background thread
    }

    @Override
    protected void shutDown() {
        script.shutdown();
    }
}

public class MyBotScript extends Script {
    public boolean run() {
        mainThreadLoop = true;
        mainThread = new Thread(() -> {
            while (mainThreadLoop) {
                // Automation logic here
                sleep(600);  // ~1 game tick (600ms)
            }
        });
        mainThread.start();
        return true;
    }
}
```

### Threading Model

**CRITICAL**: There are three thread contexts to be aware of:

1. **Client Thread** (RuneLite's main thread)
    - **NEVER** block this thread with `sleep()` or long operations
    - Use `Microbot.getClientThread().runOnClientThread()` for safe client access
    - Check if on client thread: `Microbot.getClient().isClientThread()`

2. **Script Thread** (Background automation thread)
    - Where your `mainThreadLoop` executes
    - Safe to use `sleep()`, `sleepUntil()` methods
    - Most automation logic runs here

3. **Executor Services** (Scheduled/async tasks)
    - Use `scheduledExecutorService.scheduleWithFixedDelay()` for periodic tasks
    - Example in `ExampleScript.java:32`

**Threading Best Practice:**
```java
// WRONG - blocks client thread
if (Microbot.getClient().isClientThread()) {
    sleep(1000); // BAD!
}

// RIGHT - execute on client thread when needed
Microbot.getClientThread().runOnClientThread(() -> {
    // Safe client access, NO sleeps here
    return someValue;
});

// RIGHT - from script thread
sleep(100, 300); // Random delay
sleepUntil(() -> Rs2Bank.isOpen(), 5000);
```

---

## Utility System (util/ folder)

The utility system provides static facade classes prefixed with `Rs2*` that abstract RuneLite API interactions.

### Main Utility Categories

| Utility | Purpose | Location |
|---------|---------|----------|
| `Rs2Player` | Player state, movement, skills | `util/player/` |
| `Rs2Inventory` | Inventory management | `util/inventory/` |
| `Rs2Bank` | Banking operations | `util/bank/` |
| `Rs2Npc` | NPC interactions | `util/npc/` |
| `Rs2GameObject` | World objects | `util/gameobject/` |
| `Rs2GroundItem` | Items on ground | `util/grounditem/` |
| `Rs2Walker` | Pathfinding & walking | `util/walker/` |
| `Rs2Combat` | Combat utilities | `util/combat/` |
| `Rs2Magic` | Spell casting | `util/magic/` |
| `Rs2Prayer` | Prayer management | `util/prayer/` |
| `Rs2Equipment` | Worn items | `util/equipment/` |
| `Rs2Widget` | UI interaction | `util/widget/` |
| `Rs2Antiban` | Human-like behavior | `util/antiban/` |

### Login Screen Overlay Framework

**NEW**: Reusable framework for creating login screen overlays without boilerplate.

**File References:**
- `util/loginscreen/LoginScreenOverlay.java` - Abstract base class
- `util/loginscreen/LoginScreenOverlayManager.java` - Manager utility
- `util/loginscreen/README.md` - Full documentation
- `util/loginscreen/QUICK_START.md` - Quick reference

**Quick Example:**
```java
// 1. Create your overlay
public class MyOverlay extends LoginScreenOverlay {
    public MyOverlay(Client client) {
        super(client);
    }

    @Override
    protected void paint(Graphics2D g) {
        g.drawString("My Overlay", 300, 200);
    }
}

// 2. Use in your plugin
@Override
protected void startUp() {
    MyOverlay overlay = new MyOverlay(client);
    overlayManager = new LoginScreenOverlayManager(client, clientUI, eventBus, overlay);
    overlayManager.enable();
}

@Override
protected void shutDown() {
    if (overlayManager != null) overlayManager.disable();
}
```

The framework handles all Swing setup, game state events, repaint timers, and cleanup automatically.

### Common Utility Patterns

**File References:**
- `util/inventory/Rs2Inventory.java`
- `util/bank/Rs2Bank.java` (3523 lines)
- `util/player/Rs2Player.java`

**Query and Interaction Pattern:**
```java
// Check state
if (Rs2Inventory.isFull()) { ... }
if (Rs2Bank.isOpen()) { ... }
if (Rs2Player.isAnimating()) { ... }

// Find items/objects
Rs2ItemModel item = Rs2Inventory.get("Coins");
boolean hasFood = Rs2Inventory.contains("Lobster");
int count = Rs2Inventory.count("Rune essence");

// Interact with game entities
Rs2Bank.openBank();
Rs2Bank.depositAll("Oak logs");
Rs2Inventory.interact("Lobster", "Eat");
Rs2GameObject.interact(objectId, "Mine");

// Wait for conditions
sleepUntil(() -> Rs2Bank.isOpen(), 5000);
sleepUntil(() -> !Rs2Player.isAnimating());
```

### Sleep and Timing Utilities

**File Reference:** `util/Global.java`

> ⚠️ **Non-negotiable rule:** Never use static sleeps like `sleep(12000)` to wait for game state. Always use conditional dynamic sleeps: `sleepUntil(BooleanSupplier awaitedCondition)` (optionally with a timeout as a safety net). Static delays are race-prone; conditional waits self-document the awaited state and are robust to latency/animation variation. Short fixed `sleep(600)` calls for tick pacing or `sleep(100, 300)` for anti-ban jitter are fine — the rule is about waiting on state, not adding small randomized delays.

```java
// Basic sleep with random variation
sleep(600);              // ~1 game tick
sleep(100, 300);         // Random 100-300ms

// Wait for condition (returns true if condition met before timeout)
sleepUntil(() -> condition);                    // Default 5s timeout
sleepUntil(() -> condition, 10000);            // Custom timeout
sleepUntilTrue(() -> condition, 100, 5000);    // Check every 100ms

// Wait for value (returns value when not null)
TileObject obj = sleepUntilNotNull(() -> Rs2GameObject.findObjectById(id), 5000);

// Execute action while waiting
sleepUntil(() -> Rs2Bank.isOpen(), () -> {
    // Action to perform while waiting
    Rs2GameObject.interact("Bank booth", "Bank");
}, 5000, 100);
```

---

## Cache System Architecture

The cache system is a sophisticated event-driven data access layer that maintains efficient, thread-safe caches of game entities.

### Cache Design Philosophy

**File References:**
- `util/cache/Rs2Cache.java` (1357 lines) - Base cache implementation
- `util/cache/Rs2CacheManager.java` (1185 lines) - Central manager
- `util/cache/Rs2NpcCache.java` - NPC cache
- `util/cache/Rs2ObjectCache.java` - GameObject cache

**Key Concepts:**
- **Strategy Pattern**: Pluggable update/query strategies
- **Event-Driven**: Updates triggered by RuneLite events
- **Thread-Safe**: Concurrent access with minimal locks
- **Persistence**: Optional save/load for game state caches

### Cache Types and Modes

| Cache | Mode | Persisted | Purpose |
|-------|------|-----------|---------|
| `Rs2NpcCache` | EVENT_DRIVEN_ONLY | No | NPCs in scene |
| `Rs2ObjectCache` | EVENT_DRIVEN_ONLY | No | GameObjects/TileObjects |
| `Rs2GroundItemCache` | EVENT_DRIVEN_ONLY | No | Items on ground |
| `Rs2VarbitCache` | AUTOMATIC_INVALIDATION | Yes | Game state varbits |
| `Rs2SkillCache` | AUTOMATIC_INVALIDATION | Yes | Skill levels/XP |
| `Rs2QuestCache` | AUTOMATIC_INVALIDATION | Yes | Quest states |

### Cache & Queryable API (Recommended)

**📚 Complete Documentation:** See `api/QUERYABLE_API.md` for comprehensive guide

#### Singleton Architecture

All caches are `@Singleton` classes injected via Guice and exposed through `Microbot` static getters:

```java
@Singleton
public final class Rs2NpcCache {
    @Inject
    public Rs2NpcCache(Client client, ClientThread clientThread) { ... }

    public Stream<Rs2NpcModel> getStream() { ... }
    public Rs2NpcQueryable query() { return new Rs2NpcQueryable(); }
}
```

Each cache provides:
- `getStream()` - Direct access to the underlying stream
- `query()` - Returns a fluent queryable interface for filtering

#### Usage Rules

**CRITICAL:** Never instantiate caches or queryables directly. Always access via `Microbot.getRs2XxxCache()`:

**Available Caches:**
| Cache | Accessor | Methods |
|-------|----------|---------|
| `Rs2NpcCache` | `Microbot.getRs2NpcCache()` | `.query()`, `.getStream()` |
| `Rs2PlayerCache` | `Microbot.getRs2PlayerCache()` | `.query()`, `.getStream()` |
| `Rs2TileItemCache` | `Microbot.getRs2TileItemCache()` | `.query()`, `.getStream()` |
| `Rs2TileObjectCache` | `Microbot.getRs2TileObjectCache()` | `.query()`, `.getStream()` |
| `Rs2BoatCache` | `Microbot.getRs2BoatCache()` | `.getLocalBoat()`, `.getBoat(player)` |

**Correct Usage Pattern:**
```java
// ✅ CORRECT - Use cache.query() method
Rs2NpcModel banker = Microbot.getRs2NpcCache().query()
    .withName("Banker")
    .where(npc -> !npc.isInteracting())
    .nearest(10);

// ✅ CORRECT - Ground items via cache
Rs2TileItemModel loot = Microbot.getRs2TileItemCache().query()
    .withName("Dragon bones")
    .where(item -> item.getValue() > 1000)
    .nearest();

// ✅ CORRECT - Tile objects via cache
Rs2TileObjectModel tree = Microbot.getRs2TileObjectCache().query()
    .withName("Oak tree")
    .nearest();
tree.click("Chop down");

// ✅ CORRECT - Direct stream access when needed
Rs2NpcModel firstNpc = Microbot.getRs2NpcCache().getStream()
    .filter(npc -> npc.getName() != null)
    .findFirst()
    .orElse(null);

// ✅ CORRECT - Boat cache (direct methods, no queryable)
Rs2BoatModel boat = Microbot.getRs2BoatCache().getLocalBoat();
if (boat != null) {
    boat.trimSails();
    boat.navigate();
}
```

**❌ WRONG - Never do this:**
```java
// ❌ WRONG - Don't instantiate caches
Rs2NpcCache cache = new Rs2NpcCache();

// ❌ WRONG - Don't instantiate queryables directly
Rs2NpcModel npc = new Rs2NpcQueryable().withName("Banker").nearest();

// ❌ WRONG - Don't use deprecated static methods
Rs2NpcModel npc = Rs2NpcCache.getNpcsStream().findFirst().orElse(null);
```

#### Boat World View Support

When on a boat, objects exist in a different world view. Use `.fromWorldView()` to query the player's current world view:

```java
// Query objects in the boat's world view
var sails = Microbot.getRs2TileObjectCache().query()
    .fromWorldView()
    .withName("Sails")
    .nearest();

// Reachability is automatically handled for same world view
if (sails != null && sails.isReachable()) {
    sails.click("Trim");
}
```

### Legacy Direct Cache Access (Deprecated)

```java
// ⚠️ DEPRECATED - Static methods are deprecated and will be removed in future versions
// Use Microbot.getRs2XxxCache().getStream() instead
Rs2NpcCache.getNpcsStream()           // deprecated → Microbot.getRs2NpcCache().getStream()
Rs2PlayerCache.getPlayersStream()     // deprecated → Microbot.getRs2PlayerCache().getStream()
Rs2TileItemCache.getTileItemsStream() // deprecated → Microbot.getRs2TileItemCache().getStream()
Rs2TileObjectCache.getObjectsStream() // deprecated → Microbot.getRs2TileObjectCache().getStream()
```

---

## Practical Example: Simple Mining Bot

```java
@Slf4j
public class MinerScript extends Script {
    private static final String ORE_NAME = "Copper ore";
    private static final int ROCK_ID = 11936;

    @Override
    public boolean run() {
        mainThreadLoop = true;
        mainThread = new Thread(() -> {
            while (mainThreadLoop) {
                try {
                    if (!Microbot.isLoggedIn()) {
                        sleep(1000);
                        continue;
                    }

                    // Bank when inventory is full
                    if (Rs2Inventory.isFull()) {
                        Rs2Bank.openBank();
                        sleepUntil(() -> Rs2Bank.isOpen());
                        Rs2Bank.depositAll();
                        sleepUntil(() -> Rs2Inventory.isEmpty());
                        Rs2Bank.closeBank();
                        sleep(600);
                        continue;
                    }

                    // Mine when not animating
                    if (!Rs2Player.isAnimating()) {
                        TileObject rock = Rs2GameObject.findObjectById(ROCK_ID);
                        if (rock != null) {
                            Rs2GameObject.interact(rock, "Mine");
                            sleepUntil(() -> Rs2Player.isAnimating(), 2000);
                        }
                    }

                    sleep(600); // ~1 game tick

                } catch (Exception e) {
                    log.error("Error in miner: ", e);
                }
            }
        });
        mainThread.start();
        return true;
    }
}
```

---

## Code Style and Conventions

### Java Style

- **Java version**: Target Java 11 (configured in pom.xml)
- **Indentation**: Tabs (not spaces)
- **Line length**: Max 120 characters
- **Naming**:
    - Classes: `UpperCamelCase`
    - Methods/fields: `lowerCamelCase`
    - Constants: `UPPER_SNAKE_CASE`
- **Lombok**: Used extensively (`@Slf4j`, `@Getter`, `@Setter`, etc.)
- **Annotations**: Place on separate lines before declarations

### Plugin Naming

- Plugin class: `[Name]Plugin.java`
- Script class: `[Name]Script.java`
- Config class: `[Name]Config.java`
- Config fields: Prefix with plugin name for clarity

**Example:**
```java
@ConfigItem(
    keyName = "exampleSetting",
    name = "Example Setting",
    description = "Description here"
)
default boolean exampleSetting() { return true; }
```

---

## Critical Best Practices

### 1. Threading Rules ⚠️ CRITICAL

**The Client Thread is Sacred - Never Block It!**

- **NEVER** block the client thread with `sleep()` or long operations
- **ALWAYS** use `sleepUntil()` / `sleep()` only from script threads
- **CHECK** if on client thread before sleeping: `if (!client.isClientThread())`
- **WRAP** client calls in `runOnClientThread()` when accessing from script thread

**Threading Context Guide:**

```java
// WRONG - blocks client thread ❌
if (Microbot.getClient().isClientThread()) {
    sleep(1000); // WILL FREEZE THE GAME!
}

// RIGHT - safe client access ✅
Microbot.getClientThread().runOnClientThread(() -> {
    // Safe client access, NO sleeps here
    client.getLocalPlayer().getWorldLocation();
    return true;
});

// RIGHT - script thread sleep ✅
public boolean run() {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        if (!Microbot.isLoggedIn()) return;
        
        // Safe to sleep here - we're on a script thread
        sleep(600);
        sleepUntil(() -> Rs2Bank.isOpen(), 5000);
    }, 0, 600, TimeUnit.MILLISECONDS);
    return true;
}
```

### 2. Interaction Patterns

**Always Wait for Actions to Complete**

```java
// WRONG - no waiting ❌
Rs2Bank.openBank();
Rs2Bank.depositAll(); // May fail if bank isn't open yet

// RIGHT - wait for condition ✅
Rs2Bank.openBank();
sleepUntil(() -> Rs2Bank.isOpen(), 5000);
Rs2Bank.depositAll();
sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);

// RIGHT - wait with action ✅
sleepUntil(() -> Rs2Bank.isOpen(), () -> {
    // Action to perform while waiting
    Rs2GameObject.interact("Bank booth", "Bank");
}, 5000, 100);
```

**Check Action Completion**

```java
// Check animations
if (!Rs2Player.isAnimating()) {
    Rs2GameObject.interact(rock, "Mine");
    sleepUntil(() -> Rs2Player.isAnimating(), 2000);
}

// Verify interaction success
boolean success = Rs2Npc.interact("Banker", "Bank");
if (!success) {
    log.warn("Failed to click banker");
    // Fallback logic here
}
```

**Handle Failures Gracefully**

```java
// Example with retry logic
int attempts = 0;
while (!Rs2Bank.isOpen() && attempts < 3) {
    Rs2Bank.openBank();
    if (sleepUntil(() -> Rs2Bank.isOpen(), 3000)) {
        break;
    }
    attempts++;
    sleep(300, 600);
}
```

### 3. API Usage: Cache Queryable API

**ALWAYS use cache.query() - NEVER instantiate queryables directly**

```java
// ❌ LEGACY - Old pattern (deprecated)
NPC npc = Rs2Npc.getNpc("Banker");
TileObject tree = Rs2GameObject.findObject("Tree");

// ❌ WRONG - Don't instantiate queryables directly
Rs2NpcModel banker = new Rs2NpcQueryable().withName("Banker").nearest();

// ✅ CORRECT - Use cache.query() method
Rs2NpcModel banker = Microbot.getRs2NpcCache().query()
    .withName("Banker")
    .nearest();

Rs2TileObjectModel tree = Microbot.getRs2TileObjectCache().query()
    .withName("Tree")
    .nearest();

Rs2NpcModel guard = Microbot.getRs2NpcCache().query()
    .withName("Guard")
    .where(npc -> !npc.isInteracting())
    .nearest(15);
```

**Benefits of Cache Queryable API:**
- Singleton caches ensure consistent state
- Fluent interface for complex filters
- Tick-based caching for performance
- Type-safe operations

### 4. Error Handling

**Always Wrap Script Logic in Try-Catch**

```java
public boolean run() {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
            if (!Microbot.isLoggedIn()) return;
            
            // Your automation logic here
            
        } catch (Exception e) {
            log.error("Error in script: ", e);
            // Don't let exceptions crash the thread
        }
    }, 0, 600, TimeUnit.MILLISECONDS);
    return true;
}
```

**Handle Game State Changes**

```java
// Check if logged in before every action
if (!Microbot.isLoggedIn()) {
    sleep(1000);
    return;
}

// Check for blocking events
if (Microbot.getBlockingEventManager().shouldBlockAndProcess()) {
    return; // Let the blocking event execute
}

// Respect global pause
if (Microbot.pauseAllScripts.get()) {
    sleep(1000);
    return;
}
```

### 5. Anti-Ban Considerations

**File Reference:** `util/antiban/Rs2Antiban.java`

**Random Delays**

```java
// Use random delays between actions
sleep(100, 300);  // Random 100-300ms
sleepGaussian(600, 100);  // Gaussian distribution around 600ms

// After interactions
Rs2GameObject.interact(rock, "Mine");
sleep(50, 150);  // Small random delay
sleepUntil(() -> Rs2Player.isAnimating());
```

**Natural Mouse Movement**

```java
// Enable natural mouse in plugin config or code
Rs2AntibanSettings.naturalMouse = true;

// Use antiban cooldown between actions
Rs2Antiban.actionCooldown();
sleep(100, 300);
```

**Break Handler Integration**

```java
// Integrate with BreakHandler plugin
// It will automatically pause your script during breaks
// Just check for paused state:
if (Microbot.pauseAllScripts.get()) {
    sleep(1000);
    return;
}
```

**Random Behaviors**

```java
// Random camera movements
if (Math.random() < 0.1) {  // 10% chance
    Rs2Camera.turnTo(Rs2Random.between(0, 360));
}

// Random tab checks
if (Math.random() < 0.05) {  // 5% chance
    Rs2Tab.switchToSkillsTab();
    sleep(500, 1500);
    Rs2Tab.switchToInventoryTab();
}

// Random mouse movements
if (Math.random() < 0.15) {  // 15% chance
    Rs2Antiban.moveMouseRandomly();
}
```

### 6. Performance Optimization

**Batch Operations**

```java
// WRONG - Individual operations ❌
for (String item : items) {
    Rs2Bank.deposit(item, 1);
    sleep(100);
}

// RIGHT - Batch operation ✅
Rs2Bank.depositAll();
// or
Rs2Bank.depositAllExcept("Coins", "Stamina potion");
```

**Cache Values Instead of Re-Querying**

```java
// WRONG - Query every iteration ❌
while (true) {
    if (Rs2Inventory.count("Logs") < 28) {
        // ... cut trees
    }
}

// RIGHT - Cache the value ✅
int logCount = Rs2Inventory.count("Logs");
if (logCount < 28) {
    // ... cut trees
}
```

**Minimize Client Thread Invocations**

```java
// WRONG - Multiple client thread calls ❌
boolean hasFood = Rs2Inventory.contains("Lobster");
boolean hasPotions = Rs2Inventory.contains("Stamina potion");
boolean hasCoins = Rs2Inventory.contains("Coins");

// RIGHT - Single query, process in script thread ✅
List<Rs2ItemModel> items = Rs2Inventory.items();
boolean hasFood = items.stream().anyMatch(i -> i.getName().equals("Lobster"));
boolean hasPotions = items.stream().anyMatch(i -> i.getName().equals("Stamina potion"));
boolean hasCoins = items.stream().anyMatch(i -> i.getName().equals("Coins"));
```

**Use Scheduled Executors for Periodic Tasks**

```java
// WRONG - Tight loop ❌
while (mainThreadLoop) {
    doSomething();
    sleep(600);
}

// RIGHT - Scheduled executor ✅
mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
    doSomething();
}, 0, 600, TimeUnit.MILLISECONDS);
```

### 7. Blocking Events System

**Understanding Blocking Events**

Blocking events are high-priority interruptions that pause your script to handle important game events:
- Death handling
- Tutorial island popups
- Bank tutorials
- Welcome screens
- Level up interfaces

**How to Use:**

```java
// In your script's run() method, check for blocking events:
if (Microbot.getBlockingEventManager().shouldBlockAndProcess()) {
    return; // Let the blocking event execute
}

// Add custom blocking events:
Microbot.getBlockingEventManager().add(new MyCustomBlockingEvent());
```

**Creating Custom Blocking Events:**

```java
public class MyCustomBlockingEvent extends BlockingEvent {
    @Override
    public boolean validate() {
        // Return true when this event should trigger
        return Rs2Widget.getWidget(123, 456) != null;
    }

    @Override
    public void execute() {
        // Handle the event
        Rs2Widget.clickWidget(123, 456);
    }

    @Override
    public BlockingEventPriority priority() {
        return BlockingEventPriority.MEDIUM;
    }
}
```

### 8. Memory Management

**Clean Up Resources in shutdown()**

```java
@Override
public void shutdown() {
    super.shutdown();  // ALWAYS call super.shutdown()
    
    // Clean up any resources
    if (scheduledFuture != null && !scheduledFuture.isDone()) {
        scheduledFuture.cancel(true);
    }
    
    // Reset any static state
    MyStaticCache.clear();
}
```

### 9. Logging Best Practices

**Use Lombok @Slf4j**

```java
@Slf4j
public class MyScript extends Script {
    public boolean run() {
        log.info("Script started");
        log.debug("Debug info: {}", someValue);
        log.warn("Warning: low health");
        log.error("Error occurred: ", exception);
    }
}
```

**Meaningful Log Messages**

```java
// WRONG ❌
log.info("Error");
log.info("Done");

// RIGHT ✅
log.info("Failed to open bank after 3 attempts");
log.info("Successfully banked 28 logs in {} ms", duration);
log.debug("Current state: {}, inventory: {}", state, itemCount);
```

### 10. Configuration Best Practices

**Prefix Config Keys**

```java
@ConfigItem(
    keyName = "myPluginEnableFeature",  // Prefixed with plugin name
    name = "Enable Feature",
    description = "Enables the feature"
)
default boolean enableFeature() {
    return true;
}
```

**Provide Sensible Defaults**

```java
@ConfigItem(
    keyName = "eatHealthPercent",
    name = "Eat at Health %",
    description = "Eat food when health drops below this percentage"
)
@Range(min = 1, max = 99)
default int eatHealthPercent() { return 50; }  // Sensible default
```

### 11. Common Pitfalls to Avoid

❌ **Static sleeps for game state** - `sleep(12000)` is race-prone; use `sleepUntil(BooleanSupplier)` instead
❌ **Sleeping on client thread** - Will freeze the game
❌ **Not waiting after interactions** - Actions will fail
❌ **Ignoring return values** - Can't detect failures
❌ **Hardcoding IDs without constants** - Hard to maintain
❌ **Not handling logged out state** - Script will crash
❌ **Tight loops without sleep** - CPU usage spikes
❌ **Not cleaning up resources** - Memory leaks
❌ **Using blocking I/O in main loop** - Performance issues
❌ **Accessing client data without null checks** - NullPointerException
❌ **Not respecting pause state** - Script won't stop when user pauses

---

## Common Script Patterns

### State Machine Pattern

```java
public enum BotState {
    BANKING,
    WALKING_TO_AREA,
    MINING,
    DROPPING
}

private BotState state = BotState.BANKING;

public boolean run() {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
            if (!Microbot.isLoggedIn()) return;
            
            switch (state) {
                case BANKING:
                    handleBanking();
                    break;
                case WALKING_TO_AREA:
                    handleWalking();
                    break;
                case MINING:
                    handleMining();
                    break;
                case DROPPING:
                    handleDropping();
                    break;
            }
            
        } catch (Exception e) {
            log.error("Error: ", e);
        }
    }, 0, 600, TimeUnit.MILLISECONDS);
    return true;
}

private void handleBanking() {
    if (!Rs2Bank.isOpen()) {
        Rs2Bank.openBank();
        sleepUntil(() -> Rs2Bank.isOpen(), 5000);
        return;
    }
    
    Rs2Bank.depositAll();
    sleepUntil(() -> Rs2Inventory.isEmpty());
    Rs2Bank.closeBank();
    state = BotState.WALKING_TO_AREA;
}
```

### Combat Script Pattern

```java
public boolean run() {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
            if (!Microbot.isLoggedIn()) return;
            
            // Check health
            if (Rs2Player.getHealthPercentage() < config.eatAt()) {
                Rs2Inventory.interact(config.foodName(), "Eat");
                sleep(600);
                return;
            }
            
            // Check prayer
            if (config.usePrayer() && Rs2Prayer.getPrayerPoints() < 10) {
                Rs2Inventory.interact("Prayer potion", "Drink");
                sleep(600);
                return;
            }
            
            // Loot if not in combat
            if (!Rs2Player.isInCombat()) {
                Rs2LootEngine.loot(lootingParameters);
                sleep(300);
            }
            
            // Attack NPC
            if (!Rs2Player.isInCombat()) {
                Rs2NpcModel target = new Rs2NpcQueryable()
                    .withName(config.npcName())
                    .where(npc -> !npc.isInteracting())
                    .nearest(10);
                
                if (target != null) {
                    target.click("Attack");
                    sleepUntil(() -> Rs2Player.isInCombat(), 2000);
                }
            }
            
        } catch (Exception e) {
            log.error("Error: ", e);
        }
    }, 0, 600, TimeUnit.MILLISECONDS);
    return true;
}
```

### Skill Training Pattern (Progressive)

```java
public boolean run() {
    mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
        try {
            if (!Microbot.isLoggedIn()) return;
            
            // Progressive training based on level
            int currentLevel = Rs2Player.getSkillLevel(Skill.WOODCUTTING);
            
            String treeToChop;
            if (currentLevel < 15) {
                treeToChop = "Tree";
            } else if (currentLevel < 30) {
                treeToChop = "Oak tree";
            } else if (currentLevel < 45) {
                treeToChop = "Willow tree";
            } else {
                treeToChop = "Maple tree";
            }
            
            // Bank if inventory full
            if (Rs2Inventory.isFull()) {
                Rs2Walker.walkTo(bankLocation);
                sleepUntil(() -> Rs2Player.isNearLocation(bankLocation, 5));
                Rs2Bank.openBank();
                sleepUntil(() -> Rs2Bank.isOpen());
                Rs2Bank.depositAll();
                sleepUntil(() -> Rs2Inventory.isEmpty());
                Rs2Bank.closeBank();
            }
            
            // Chop tree
            if (!Rs2Player.isAnimating()) {
                Rs2TileObjectModel tree = Rs2TileObjectApi.getNearest(obj ->
                    obj.getName() != null && obj.getName().equals(treeToChop)
                );
                
                if (tree != null) {
                    tree.click("Chop down");
                    sleepUntil(() -> Rs2Player.isAnimating(), 3000);
                } else {
                    log.warn("No {} found nearby", treeToChop);
                }
            }
            
        } catch (Exception e) {
            log.error("Error: ", e);
        }
    }, 0, 600, TimeUnit.MILLISECONDS);
    return true;
}
```

### Grand Exchange Pattern

```java
private void buySupplies() {
    if (!Rs2GrandExchange.isOpen()) {
        Rs2GameObject.interact("Grand Exchange booth", "Exchange");
        sleepUntil(() -> Rs2GrandExchange.isOpen(), 5000);
        return;
    }
    
    // Buy supplies
    if (!Rs2GrandExchange.hasPendingOffers()) {
        Rs2GrandExchange.buyItem("Fire rune", 1000, 5);
        sleep(600, 1000);
        Rs2GrandExchange.buyItem("Air rune", 1000, 5);
        sleep(600, 1000);
    }
    
    // Wait for completion
    if (Rs2GrandExchange.hasPendingOffers()) {
        sleepUntil(() -> !Rs2GrandExchange.hasPendingOffers(), 30000);
    }
    
    // Collect
    Rs2GrandExchange.collectAll();
    sleepUntil(() -> !Rs2GrandExchange.hasCollectableItems());
    Rs2GrandExchange.close();
}
```

### Quest Helper Integration Pattern

```java
// Use quest helper for complex pathing
if (config.useQuestHelper()) {
    Rs2Walker.setTarget(targetLocation);
    sleepUntil(() -> Rs2Player.isNearLocation(targetLocation, 3), 60000);
} else {
    // Manual walking
    Rs2Walker.walkTo(targetLocation);
}
```

---

## Dependency Injection

Access core RuneLite services via `Microbot` singleton:

```java
// Client access
Client client = Microbot.getClient();
if (Microbot.isLoggedIn()) { ... }

// Thread management
ClientThread clientThread = Microbot.getClientThread();
clientThread.runOnClientThread(() -> { ... });

// Other services
EventBus eventBus = Microbot.getEventBus();
ItemManager itemManager = Microbot.getItemManager();
```

---

## Plugin Architecture

### Plugin Structure

Every Microbot plugin consists of 3-4 main components:

```
myplugin/
├── MyPlugin.java          # Main plugin class (extends Plugin)
├── MyScript.java          # Script logic (extends Script)
├── MyConfig.java          # Configuration interface (extends Config)
└── MyOverlay.java         # [Optional] Visual overlay (extends OverlayPanel)
```

### 1. Plugin Class (MyPlugin.java)

The plugin is the entry point and manages lifecycle:

```java
@PluginDescriptor(
    name = "My Plugin",
    description = "Does something useful",
    tags = {"automation", "skill"},
    enabledByDefault = false
)
@Slf4j
public class MyPlugin extends Plugin {
    
    @Inject
    private MyConfig config;
    
    @Inject
    private Client client;
    
    @Provides
    MyConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MyConfig.class);
    }
    
    private MyScript script;
    
    @Override
    protected void startUp() throws Exception {
        if (script == null) {
            script = new MyScript();
        }
        script.run(config);
        log.info("Plugin started");
    }
    
    @Override
    protected void shutDown() throws Exception {
        script.shutdown();
        log.info("Plugin stopped");
    }
}
```

### 2. Script Class (MyScript.java)

The script contains your automation logic:

```java
@Slf4j
public class MyScript extends Script {
    
    public boolean run(MyConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                
                // Your automation logic here
                log.info("Script loop running");
                
            } catch (Exception e) {
                log.error("Error in script: ", e);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }
    
    @Override
    public void shutdown() {
        super.shutdown();
        log.info("Script shutdown");
    }
}
```

### 3. Config Class (MyConfig.java)

Configuration exposes settings to the user:

```java
@ConfigGroup("myPluginConfig")
public interface MyConfig extends Config {
    
    @ConfigSection(
        name = "General Settings",
        description = "General plugin settings",
        position = 0
    )
    String generalSection = "general";
    
    @ConfigItem(
        keyName = "enableFeature",
        name = "Enable Feature",
        description = "Enables the main feature",
        position = 0,
        section = generalSection
    )
    default boolean enableFeature() {
        return true;
    }
    
    @ConfigItem(
        keyName = "npcName",
        name = "NPC Name",
        description = "Name of NPC to interact with",
        position = 1,
        section = generalSection
    )
    default String npcName() {
        return "Guard";
    }
    
    @Range(min = 1, max = 99)
    @ConfigItem(
        keyName = "eatHealthPercent",
        name = "Eat at Health %",
        description = "Eat food when health drops below this percentage",
        position = 2,
        section = generalSection
    )
    default int eatHealthPercent() {
        return 50;
    }
}
```

### 4. Overlay Class [Optional] (MyOverlay.java)

Visual feedback on the game screen:

```java
@Slf4j
public class MyOverlay extends OverlayPanel {
    
    private final MyPlugin plugin;
    private final MyConfig config;
    
    @Inject
    MyOverlay(MyPlugin plugin, MyConfig config) {
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }
    
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.getChildren().clear();
            panelComponent.getChildren().add(TitleComponent.builder()
                .text("My Plugin")
                .color(Color.GREEN)
                .build());
            
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Status:")
                .right(plugin.isRunning() ? "RUNNING" : "STOPPED")
                .build());
            
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Runtime:")
                .right(formatDuration(plugin.getRuntime()))
                .build());
                
        } catch (Exception e) {
            log.error("Error in overlay: ", e);
        }
        return super.render(graphics);
    }
    
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
```

### Plugin Lifecycle

1. **Startup Sequence:**
    - Plugin instantiated by RuneLite
    - `@Inject` fields populated
    - `provideConfig()` called
    - `startUp()` called
    - Script's `run()` method called

2. **Running State:**
    - Script's scheduled executor runs at fixed intervals
    - Overlay renders each frame (if present)
    - Config changes propagate automatically

3. **Shutdown Sequence:**
    - `shutDown()` called (user disables plugin)
    - Script's `shutdown()` called
    - Scheduled executors cancelled
    - Resources cleaned up

### Plugin Communication

**Accessing Other Plugins:**

```java
// Get another plugin's instance
@Inject
private PluginManager pluginManager;

MyOtherPlugin otherPlugin = (MyOtherPlugin) pluginManager
    .getPlugins()
    .stream()
    .filter(p -> p instanceof MyOtherPlugin)
    .findFirst()
    .orElse(null);
```

**Shared State via Microbot:**

```java
// Store shared state
Microbot.getClientThread().scheduledFuture = someFuture;

// Access from other plugins
if (Microbot.getClientThread().scheduledFuture != null) {
    // Do something
}
```

### Event Handling

**Subscribe to RuneLite Events:**

```java
@Subscribe
public void onGameTick(GameTick event) {
    // Called every game tick (~600ms)
}

@Subscribe
public void onChatMessage(ChatMessage event) {
    if (event.getMessage().contains("You catch a fish")) {
        fishCaught++;
    }
}

@Subscribe
public void onAnimationChanged(AnimationChanged event) {
    if (event.getActor() == client.getLocalPlayer()) {
        log.debug("Player animation changed");
    }
}
```

**Important:** Events run on client thread - no sleeping allowed!

---

## Testing

**File References:**
- Tests mirror package structure in `runelite-client/src/test/java`
- Use JUnit 4 (`junit:4.12`)
- Mockito for mocking (`mockito-core:3.1.0`)

**Test Pattern:**
```java
public class MyUtilityTest {
    @Test
    public void testSomeFeature() {
        // Arrange
        // Act
        // Assert
    }
}
```

**Running tests:**
```bash
mvn -pl runelite-client test
```

---

## Troubleshooting Guide

### Game Freezes or Client Becomes Unresponsive

**Cause:** Blocking the client thread with `sleep()` or long operations.

**Solution:**
```java
// Check if you're sleeping on client thread
if (Microbot.getClient().isClientThread()) {
    // NEVER sleep here!
    log.error("Attempting to sleep on client thread!");
    return;
}
```

### Script Doesn't Start or Stops Immediately

**Common Causes:**

1. **Not logged in check:**
```java
if (!Microbot.isLoggedIn()) {
    log.warn("Not logged in, waiting...");
    return;
}
```

2. **Blocking event preventing execution:**
```java
if (Microbot.getBlockingEventManager().shouldBlockAndProcess()) {
    return; // Let blocking event complete
}
```

3. **Script paused:**
```java
if (Microbot.pauseAllScripts.get()) {
    return;
}
```

4. **Exception in run method:**
```java
// Always wrap in try-catch
try {
    // Your code
} catch (Exception e) {
    log.error("Script error: ", e);
}
```

### Interactions Failing (Clicks Not Working)

**Cause:** Not waiting for previous action to complete or checking for animation.

**Solution:**
```java
// Wait for animation to finish before next action
if (!Rs2Player.isAnimating()) {
    Rs2GameObject.interact(rock, "Mine");
    sleepUntil(() -> Rs2Player.isAnimating(), 2000);
}

// Check return value
boolean success = Rs2Npc.interact("Banker", "Bank");
if (!success) {
    log.warn("Failed to interact with banker");
}
```

### Bank Operations Not Working

**Common Issues:**

1. **Bank not open:**
```java
Rs2Bank.openBank();
sleepUntil(() -> Rs2Bank.isOpen(), 5000);
// Now safe to use bank operations
```

2. **Inventory not empty after deposit:**
```java
Rs2Bank.depositAll();
sleepUntil(() -> Rs2Inventory.isEmpty(), 3000);
```

3. **Item names incorrect:**
```java
// Use exact item names
Rs2Bank.withdraw("Coins", 1000); // Not "Gold" or "gp"
```

### NPCs or Objects Not Found

**Cause:** Entity not in loaded area or cache not updated.

**Solution:**
```java
// Check if entity exists with timeout
Rs2NpcModel npc = sleepUntilNotNull(() -> 
    new Rs2NpcQueryable().withName("Banker").nearest(),
    5000, 600
);

if (npc == null) {
    log.warn("NPC not found after timeout");
    // Fallback logic
}

// Verify distance
Rs2NpcModel npc = new Rs2NpcQueryable()
    .withName("Guard")
    .nearest(20); // Increase search radius
```

### Walking or Pathfinding Issues

**Common Problems:**

1. **Can't reach location:**
```java
// Check if location is reachable
if (!Rs2Walker.canReach(targetLocation)) {
    log.warn("Location unreachable");
}

// Use web walker for complex paths
Rs2Walker.walkTo(targetLocation);
sleepUntil(() -> Rs2Player.isNearLocation(targetLocation, 5), 60000);
```

2. **Player stuck:**
```java
// Add timeout for walking
WorldPoint startPos = Rs2Player.getWorldLocation();
Rs2Walker.walkTo(targetLocation);
sleep(5000);

if (Rs2Player.getWorldLocation().equals(startPos)) {
    log.warn("Player hasn't moved, might be stuck");
    // Try alternative path
}
```

### Memory Leaks or Performance Degradation

**Cause:** Not cleaning up resources in `shutdown()`.

**Solution:**
```java
@Override
public void shutdown() {
    super.shutdown(); // ALWAYS call this first
    
    // Cancel scheduled futures
    if (mainScheduledFuture != null) {
        mainScheduledFuture.cancel(true);
    }
    
    // Clear caches
    if (myCache != null) {
        myCache.clear();
    }
    
    // Remove event listeners
    eventBus.unregister(this);
}
```

### Compilation Errors

**Common Issues:**

1. **Missing imports:**
```java
import net.runelite.client.plugins.microbot.Microbot;
import static net.runelite.client.plugins.microbot.util.Global.*;
```

2. **Wrong API version:**
```java
// Use new queryable API
new Rs2NpcQueryable().withName("name").nearest()
// Not: Rs2Npc.getNpc("name") - legacy
```

3. **Lombok not working:**
```java
// Add @Slf4j annotation
@Slf4j
public class MyScript extends Script {
    // Now log.info() works
}
```

### Widget Interactions Not Working

**Cause:** Widget not visible or wrong widget ID.

**Solution:**
```java
// Check if widget exists
Widget widget = Rs2Widget.getWidget(123, 456);
if (widget == null) {
    log.warn("Widget not found");
    return;
}

// Check if widget is visible
if (!widget.isVisible() || widget.isHidden()) {
    log.warn("Widget not visible");
    return;
}

// Then interact
Rs2Widget.clickWidget(123, 456);
```

### Debugging Tips

1. **Enable debug logging:**
```java
log.debug("Current state: {}", state);
log.debug("Inventory count: {}", Rs2Inventory.count());
log.debug("Player location: {}", Rs2Player.getWorldLocation());
```

2. **Add breakpoints in IDE:**
    - Place breakpoint in your script's main loop
    - Inspect variables and game state
    - Step through execution

3. **Use overlay for debugging:**
```java
@Override
public Dimension render(Graphics2D graphics) {
    graphics.drawString("State: " + currentState, 10, 20);
    graphics.drawString("Inventory: " + Rs2Inventory.count(), 10, 40);
    graphics.drawString("Player location: " + Rs2Player.getWorldLocation(), 10, 60);
    return null;
}
```

4. **Test in isolation:**
```java
// Test specific functionality
public static void main(String[] args) {
    // Test your logic without full client
}
```

---

## Commit Guidelines

Use conventional commits:
```
type(scope): summary (max 72 chars)

Longer description if needed.

Fixes #123
```

**Types:** `feat`, `fix`, `refactor`, `test`, `docs`, `chore`

**Example:**
```
feat(combat): add prayer flicking support

Implements prayer flicking for combat scripts using
Rs2Prayer.toggleQuickPrayer() with precise game tick timing.

Closes #456
```

### Versioning

Whenever changes are made to the codebase, bump `microbot.version` in `gradle.properties` (at the repo root) as part of the same commit. Use a standalone `chore(gradle): update microbot version to X.Y.Z` commit when the version bump is the only intent, or include it alongside the functional change when tightly coupled. Follow semver: patch for fixes, minor for backwards-compatible features, major for breaking changes. Do NOT merge or release without this bump — downstream clients rely on the version string embedded in `runelite.properties` to detect new releases.

---

## Microbot Project Structure

### Core Directory Layout

```
microbot/                                    # Main automation framework
├── Core Classes
│   ├── Microbot.java                       # Singleton service access, client state
│   ├── Script.java                         # Base class for all automation scripts
│   ├── IScript.java                        # Script interface
│   ├── MicrobotPlugin.java                 # Core plugin entry point
│   ├── MicrobotOverlay.java                # Main overlay renderer
│   ├── MicrobotConfig.java                 # Global configuration
│   ├── BlockingEventManager.java           # Event-driven interruption handler
│   └── BlockingEvent.java                  # Base class for blocking events
│
├── Documentation
│   ├── AGENTS.md                           # Repository guidelines
│   └── CLAUDE.md                           # This file (AI assistant guide)
│
├── api/                                     # NEW QUERYABLE API (Recommended)
│   ├── IEntityQueryable.java               # Generic query interface
│   ├── AbstractEntityQueryable.java        # Base implementation
│   ├── IEntity.java                        # Base entity interface
│   ├── npc/                                # NPC queries
│   │   └── Rs2NpcQueryable.java
│   ├── tileitem/                           # Ground item queries
│   │   └── GroundItemQueryable.java
│   ├── tileobject/                         # TileObject queries
│   │   └── Rs2TileObjectCache.java
│   ├── player/                             # Player state cache
│   │   ├── Rs2PlayerCache.java
│   │   └── PlayerApiExample.java
│   └── actor/                              # Actor utilities
│
├── util/                                    # UTILITY SYSTEM (Core API)
│   ├── Global.java                         # Sleep/timing/wait utilities
│   ├── ActorModel.java                     # Base actor wrapper
│   │
│   ├── player/                             # Player utilities
│   │   ├── Rs2Player.java                  # Movement, skills, state
│   │   ├── Rs2PlayerModel.java             # Player data model
│   │   └── Rs2Pvp.java                     # PvP utilities
│   │
│   ├── inventory/                          # Inventory management
│   │   ├── Rs2Inventory.java               # Main inventory API
│   │   ├── Rs2ItemModel.java               # Item wrapper model
│   │   ├── Rs2RunePouch.java               # Rune pouch support
│   │   ├── Rs2Gembag.java                  # Gem bag support
│   │   └── Rs2LogBasket.java               # Log basket support
│   │
│   ├── bank/                               # Banking
│   │   ├── Rs2Bank.java                    # Bank operations (3500+ lines)
│   │   └── Rs2BankData.java                # Bank data models
│   │
│   ├── depositbox/                         # Deposit box
│   │   └── Rs2DepositBox.java
│   │
│   ├── equipment/                          # Worn items
│   │   ├── Rs2Equipment.java               # Equipment API
│   │   └── JewelleryLocationEnum.java      # Teleport jewellery
│   │
│   ├── npc/                                # NPC interactions
│   │   ├── Rs2Npc.java                     # NPC utilities (LEGACY)
│   │   ├── Rs2NpcManager.java              # NPC tracking
│   │   ├── Rs2NpcModel.java                # NPC wrapper
│   │   └── Rs2NpcStats.java                # NPC combat stats
│   │
│   ├── gameobject/                         # World objects
│   │   ├── Rs2GameObject.java              # GameObject utilities (LEGACY)
│   │   ├── Rs2ObjectModel.java             # Object wrapper
│   │   ├── Rs2Cannon.java                  # Cannon utilities
│   │   └── ObjectID.java                   # Object ID constants
│   │
│   ├── tileobject/                         # NEW tile object API
│   │   └── Rs2TileObjectApi.java
│   │
│   ├── grounditem/                         # Ground items
│   │   ├── Rs2GroundItem.java              # Ground item utilities (LEGACY)
│   │   ├── Rs2LootEngine.java              # Advanced looting
│   │   └── LootingParameters.java          # Loot filters
│   │
│   ├── walker/                             # Pathfinding & walking
│   │   ├── Rs2Walker.java                  # Main walking API
│   │   ├── Rs2MiniMap.java                 # Minimap interactions
│   │   └── WalkerState.java                # Walking state machine
│   │
│   ├── combat/                             # Combat utilities
│   │   ├── Rs2Combat.java                  # Combat API
│   │   └── weapons/                        # Weapon-specific logic
│   │
│   ├── magic/                              # Magic & spells
│   │   ├── Rs2Magic.java                   # Spell casting API
│   │   ├── Rs2Spells.java                  # Spell definitions
│   │   ├── Rs2CombatSpells.java            # Combat spells
│   │   ├── Rs2Spellbook.java               # Spellbook switching
│   │   ├── Rs2Staff.java                   # Staff utilities
│   │   ├── Runes.java                      # Rune definitions
│   │   └── thralls/                        # Thrall utilities
│   │
│   ├── prayer/                             # Prayer management
│   │   ├── Rs2Prayer.java                  # Prayer API
│   │   └── Rs2PrayerEnum.java              # Prayer definitions
│   │
│   ├── camera/                             # Camera control
│   │   ├── Rs2Camera.java                  # Camera API
│   │   └── NpcTracker.java                 # Camera NPC tracking
│   │
│   ├── widget/                             # UI interactions
│   │   └── Rs2Widget.java                  # Widget utilities
│   │
│   ├── tabs/                               # Game tabs
│   │   └── Rs2Tab.java                     # Tab switching
│   │
│   ├── dialogues/                          # Dialogue handling
│   │   └── Rs2Dialogue.java
│   │
│   ├── grandexchange/                      # Grand Exchange
│   │   ├── Rs2GrandExchange.java           # GE API
│   │   └── models/                         # GE data models
│   │
│   ├── shop/                               # Shop interactions
│   │   └── Rs2Shop.java
│   │
│   ├── loginscreen/                        # Login screen overlays
│   │   ├── LoginScreenOverlay.java         # Base overlay class
│   │   ├── LoginScreenOverlayManager.java  # Manager utility
│   │   ├── README.md                       # Full documentation
│   │   └── QUICK_START.md                  # Quick reference
│   │
│   ├── antiban/                            # Anti-ban measures
│   │   ├── Rs2Antiban.java                 # Antiban API
│   │   ├── Rs2AntibanSettings.java         # Antiban configuration
│   │   ├── AntibanPlugin.java              # Antiban plugin
│   │   └── ui/                             # Antiban UI panels
│   │
│   ├── mouse/                              # Mouse control
│   │   ├── VirtualMouse.java               # Virtual mouse
│   │   └── naturalmouse/                   # Natural mouse movement
│   │
│   ├── keyboard/                           # Keyboard control
│   │   └── Rs2Keyboard.java
│   │
│   ├── menu/                               # Right-click menus
│   │   └── NewMenuEntry.java
│   │
│   ├── tile/                               # Tile utilities
│   │   └── Rs2Tile.java
│   │
│   ├── coords/                             # Coordinate utilities
│   │
│   ├── math/                               # Math utilities
│   │   └── Rs2Random.java
│   │
│   ├── misc/                               # Miscellaneous utilities
│   │   ├── Rs2Food.java                    # Food definitions
│   │   ├── Rs2Potion.java                  # Potion utilities
│   │   └── Rs2UiHelper.java                # UI helpers
│   │
│   ├── skills/                             # Skill-specific utilities
│   │   ├── fletching/
│   │   └── slayer/
│   │
│   ├── farming/                            # Farming utilities
│   ├── poh/                                # Player-owned house
│   ├── world/                              # World utilities
│   ├── security/                           # Security utilities
│   ├── reflection/                         # Reflection utilities
│   └── events/                             # Event handling
│
├── example/                                 # Example plugin template
│   ├── ExamplePlugin.java                  # Plugin template
│   ├── ExampleScript.java                  # Script template
│   └── ExampleConfig.java                  # Config template
│
├── breakhandler/                           # Break system
│   ├── BreakHandlerPlugin.java             # Break plugin
│   ├── BreakHandlerScript.java             # Break logic
│   ├── BreakHandlerConfig.java             # Break settings
│   └── BreakHandlerOverlay.java            # Break overlay
│
├── accountselector/                        # Account management
├── loginscreenoverlay/                     # Login overlay plugin
├── shortestpath/                           # Pathfinding plugin
├── questhelper/                            # Quest helper plugin
├── inventorysetups/                        # Inventory setups
└── ui/                                     # UI components
```

### File Size Reference (Major Files)

- `Rs2Bank.java` - ~3,500 lines (comprehensive banking)
- `Rs2Walker.java` - ~2,000 lines (pathfinding)
- `Rs2Inventory.java` - ~1,500 lines (inventory management)
- `Microbot.java` - ~1,200 lines (core singleton)
- `Rs2Player.java` - ~1,000 lines (player utilities)
- `Rs2Npc.java` - ~800 lines (NPC utilities - legacy)
- `Rs2GameObject.java` - ~700 lines (object utilities - legacy)

---

## Additional Resources

- **Queryable API Guide**: See `api/QUERYABLE_API.md` for complete queryable API documentation
- **Discord**: https://discord.gg/zaGrfqFEWE
- **Website**: https://themicrobot.com
- **Example Scripts**: Browse plugins in the microbot package for real-world examples
- **API Examples**: Check `api/*/ApiExample.java` files for usage patterns

---

## Migration: Old vs New Patterns

When updating existing code, prefer the new queryable API:

```java
// OLD - direct utility calls
NPC npc = Rs2Npc.getNpc("Banker");
TileObject tree = Rs2GameObject.findObject("Tree");
List<NPC> guards = Rs2Npc.getNpcs().stream()
    .filter(n -> n.getName().equals("Guard"))
    .filter(n -> !n.isInteracting())
    .collect(Collectors.toList());

// NEW - queryable API (recommended)
Rs2NpcModel banker = new Rs2NpcQueryable()
    .withName("Banker")
    .nearest();

Rs2TileObjectModel tree = Rs2TileObjectApi.getNearest(obj ->
    obj.getName() != null && obj.getName().contains("Tree")
);

Rs2NpcModel guard = new Rs2NpcQueryable()
    .withName("Guard")
    .where(npc -> !npc.isInteracting())
    .nearest(15);
```

---

## Login Screen Overlays

When creating overlays that appear on the login screen, **DO NOT** use the standard `Overlay` system (it only works when `GameState == LOGGED_IN`).

Instead, use the **Login Screen Overlay Framework** located in `util/loginscreen/`:

```java
// 1. Extend LoginScreenOverlay
public class MyOverlay extends LoginScreenOverlay {
    public MyOverlay(Client client) {
        super(client);
    }

    @Override
    protected void paint(Graphics2D g) {
        // Your drawing code here
        g.drawString("Status: Active", 300, 200);
    }
}

// 2. Use LoginScreenOverlayManager in your plugin
@Inject private Client client;
@Inject private ClientUI clientUI;
@Inject private EventBus eventBus;

private LoginScreenOverlayManager overlayManager;

@Override
protected void startUp() {
    MyOverlay overlay = new MyOverlay(client);
    overlayManager = new LoginScreenOverlayManager(client, clientUI, eventBus, overlay);
    overlayManager.enable();
}

@Override
protected void shutDown() {
    if (overlayManager != null) overlayManager.disable();
}
```

The framework provides:
- Helper methods: `drawCenteredText()`, `drawTextWithShadow()`, `drawPanel()`
- Automatic show/hide on game state changes
- Repaint timer management
- Thread-safe Swing operations
- Automatic cleanup

See `util/loginscreen/README.md` for full API documentation and examples.

---

## Quick Reference Card

### Essential Imports

```java
// Core imports for most scripts
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable;
import net.runelite.api.Skill;
import lombok.extern.slf4j.Slf4j;
import static net.runelite.client.plugins.microbot.util.Global.*;

// For plugins
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import javax.inject.Inject;

// For configs
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
```

### Most Used Methods

```java
// ===== PLAYER =====
Rs2Player.isAnimating()                     // Is player performing animation?
Rs2Player.isMoving()                        // Is player moving?
Rs2Player.isInCombat()                      // Is player in combat?
Rs2Player.getHealthPercentage()             // Get health as percentage (0-100)
Rs2Player.getSkillLevel(Skill.ATTACK)       // Get skill level
Rs2Player.toggleRunEnergy(true)             // Enable/disable run
Rs2Player.getWorldLocation()                // Get player's position
Rs2Player.isNearLocation(location, distance) // Check if near location

// ===== INVENTORY =====
Rs2Inventory.contains("item name")          // Check if inventory has item
Rs2Inventory.count("item name")             // Count items in inventory
Rs2Inventory.isFull()                       // Is inventory full?
Rs2Inventory.isEmpty()                      // Is inventory empty?
Rs2Inventory.interact("item", "action")     // Interact with item
Rs2Inventory.drop("item")                   // Drop item
Rs2Inventory.dropAll("item")                // Drop all of item
Rs2Inventory.use("item1")                   // Use item (then click target)

// ===== BANK =====
Rs2Bank.openBank()                          // Open nearest bank
Rs2Bank.isOpen()                            // Is bank open?
Rs2Bank.depositAll()                        // Deposit all items
Rs2Bank.depositAll("item")                  // Deposit all of item
Rs2Bank.depositAllExcept("item1", "item2")  // Deposit all except
Rs2Bank.withdraw("item", quantity)          // Withdraw item
Rs2Bank.withdrawAll("item")                 // Withdraw all of item
Rs2Bank.closeBank()                         // Close bank
Rs2Bank.hasItem("item")                     // Check if bank has item

// ===== NPCs (New API) =====
new Rs2NpcQueryable()
    .withName("name")                       // Filter by name
    .withId(id)                             // Filter by ID
    .where(npc -> condition)                // Custom filter
    .nearest()                              // Get nearest
    .nearest(maxDistance)                   // Get nearest within distance

npc.click("Attack")                         // Click NPC with action
npc.isInteracting()                         // Is NPC interacting with something?
npc.getName()                               // Get NPC name
npc.getHealthRatio()                        // Get NPC health ratio

// ===== GAME OBJECTS (New API) =====
Rs2TileObjectApi.getNearest(obj -> condition)  // Get nearest matching object
Rs2GameObject.interact(object, "action")    // Interact with object
Rs2GameObject.interact(objectId, "action")  // Interact by ID

// ===== GROUND ITEMS =====
new GroundItemQueryable()
    .withName("item")
    .nearest()

Rs2GroundItem.loot("item")                  // Loot specific item
Rs2LootEngine.loot(parameters)              // Advanced looting

// ===== WALKING =====
Rs2Walker.walkTo(worldPoint)                // Walk to location
Rs2Walker.walkFastCanvas(worldPoint)        // Fast canvas walking
Rs2Walker.canReach(worldPoint)              // Check if reachable
Rs2Walker.setTarget(worldPoint)             // Set walker target

// ===== COMBAT =====
Rs2Combat.attack(npc)                       // Attack NPC
Rs2Combat.inCombat()                        // Is player in combat?
Rs2Combat.getWildernessLevelFrom(worldPoint) // Get wilderness level

// ===== PRAYER =====
Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_FROM_MELEE) // Toggle prayer
Rs2Prayer.isActive(Rs2PrayerEnum.PIETY)     // Check if prayer active
Rs2Prayer.getPrayerPoints()                 // Get prayer points

// ===== MAGIC =====
Rs2Magic.cast(MagicAction.FIRE_BOLT)        // Cast spell
Rs2Magic.canCast(MagicAction.FIRE_BOLT)     // Check if can cast
Rs2Magic.alch("item")                       // High alch item

// ===== EQUIPMENT =====
Rs2Equipment.isWearing("item")              // Check if wearing item
Rs2Equipment.equip("item")                  // Equip item from inventory

// ===== WIDGETS =====
Rs2Widget.getWidget(groupId, childId)       // Get widget
Rs2Widget.clickWidget(groupId, childId)     // Click widget
Rs2Widget.hasWidget(groupId, childId)       // Check if widget exists

// ===== DIALOGUES =====
Rs2Dialogue.isInDialogue()                  // Is dialogue open?
Rs2Dialogue.clickContinue()                 // Click continue
Rs2Dialogue.clickOption("option text")      // Click dialogue option

// ===== TABS =====
Rs2Tab.switchToInventoryTab()               // Switch to inventory
Rs2Tab.switchToMagicTab()                   // Switch to magic
Rs2Tab.getCurrentTab()                      // Get current tab

// ===== TIMING/SLEEP =====
sleep(600)                                  // Sleep 600ms (~1 game tick)
sleep(100, 300)                             // Random sleep 100-300ms
sleepUntil(() -> condition)                 // Wait until condition (5s timeout)
sleepUntil(() -> condition, timeout)        // Wait with custom timeout
sleepUntilTrue(() -> condition, check, timeout) // Wait checking periodically

// ===== GAME STATE =====
Microbot.isLoggedIn()                       // Is player logged in?
Microbot.pauseAllScripts.get()              // Are scripts paused?
Microbot.getClient()                        // Get game client
```

### Common Code Snippets

**Basic Script Loop:**
```java
mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
    try {
        if (!Microbot.isLoggedIn()) return;
        if (!super.run()) return;
        
        // Your logic here
        
    } catch (Exception e) {
        log.error("Error: ", e);
    }
}, 0, 600, TimeUnit.MILLISECONDS);
```

**Bank and Withdraw:**
```java
Rs2Bank.openBank();
sleepUntil(() -> Rs2Bank.isOpen(), 5000);
Rs2Bank.depositAll();
sleepUntil(() -> Rs2Inventory.isEmpty());
Rs2Bank.withdraw("Pure essence", 28);
sleepUntil(() -> Rs2Inventory.isFull());
Rs2Bank.closeBank();
```

**Find and Attack NPC:**
```java
if (!Rs2Player.isInCombat()) {
    Rs2NpcModel target = new Rs2NpcQueryable()
        .withName("Goblin")
        .where(npc -> !npc.isInteracting())
        .nearest(10);
    
    if (target != null) {
        target.click("Attack");
        sleepUntil(() -> Rs2Player.isInCombat(), 2000);
    }
}
```

**Interact with Object:**
```java
if (!Rs2Player.isAnimating()) {
    Rs2TileObjectModel tree = Rs2TileObjectApi.getNearest(obj ->
        obj.getName() != null && obj.getName().equals("Tree")
    );
    
    if (tree != null) {
        tree.click("Chop down");
        sleepUntil(() -> Rs2Player.isAnimating(), 3000);
    }
}
```

**Health Check and Eat:**
```java
if (Rs2Player.getHealthPercentage() < 50) {
    Rs2Inventory.interact("Lobster", "Eat");
    sleep(600, 800);
}
```

**Walk to Location:**
```java
WorldPoint location = new WorldPoint(3100, 3500, 0);
Rs2Walker.walkTo(location);
sleepUntil(() -> Rs2Player.isNearLocation(location, 5), 30000);
```

### Configuration Template

```java
@ConfigGroup("myPluginConfig")
public interface MyConfig extends Config {
    
    @ConfigItem(
        keyName = "npcName",
        name = "NPC Name",
        description = "Name of NPC to target"
    )
    default String npcName() {
        return "Goblin";
    }
    
    @Range(min = 1, max = 99)
    @ConfigItem(
        keyName = "eatAt",
        name = "Eat at HP %",
        description = "Eat food when health drops below this"
    )
    default int eatAt() {
        return 50;
    }
    
    @ConfigItem(
        keyName = "foodName",
        name = "Food Name",
        description = "Name of food to eat"
    )
    default String foodName() {
        return "Lobster";
    }
}
```

### Keyboard Shortcuts While Developing

- `Ctrl + Shift + F`: Format code
- `Ctrl + Space`: Auto-complete
- `Ctrl + Click`: Navigate to definition
- `Shift + F10`: Run last configuration
- `Ctrl + F9`: Build project

---

## Summary: Key Takeaways

✅ **DO:**
- Always check `Microbot.isLoggedIn()` before actions
- Use `sleepUntil()` to wait for conditions
- Wrap logic in try-catch blocks
- Clean up resources in `shutdown()`
- Use new queryable API for NPCs/objects
- Add random delays for anti-ban
- Call `super.run()` in script loop

❌ **DON'T:**
- Never use static sleeps like `sleep(12000)` to wait for game state — use `sleepUntil(BooleanSupplier)` instead
- Never sleep on client thread
- Don't ignore return values from interactions
- Don't use tight loops without sleep
- Don't hardcode values without config
- Don't forget to null-check objects
- Don't block on I/O in main loop

---

**Last Updated:** November 18, 2025  
**Project:** Microbot - RuneScape Automation Framework  
**Target:** AI Code Assistants (Claude, GitHub Copilot, etc.)

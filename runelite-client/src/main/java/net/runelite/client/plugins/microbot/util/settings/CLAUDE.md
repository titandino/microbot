@AGENTS.md

# Settings & Widget Debugging

## Widget Debugging Workflow

When a settings toggle/dropdown stops working, **always verify the current widget type first**. Game updates frequently change widgets from toggles to dropdowns (or vice versa). Never assume the old param0/param1/action values are still correct.

### Step 1: Research the Setting Online

Check the OSRS Wiki for recent changes:
- https://oldschool.runescape.wiki/w/Settings — lists all settings with their types
- Search for recent game updates that may have changed the setting

Determine: is this a **toggle** (on/off), a **dropdown** (multiple options), or a **slider**?

### Step 2: Use the Microbot CLI to Inspect Widgets at Runtime

The client must be running and logged in with the Agent Server plugin enabled.

```bash
# 1. Open the settings interface in-game first, then:

# 2. Search for the widget by keywords
./microbot-cli widgets search "level-up,notification,pop-up"

# 3. Describe the widget tree to see children, actions, and types
./microbot-cli widgets describe 134 20 --depth 3   # clickable children
./microbot-cli widgets describe 134 19 --depth 3   # text/label children

# 4. Click a specific widget to test
./microbot-cli widgets click 134 42

# 5. Invoke a specific menu action on a dynamic child
./microbot-cli widgets invoke 134 20 --param0 40 --action Select

# 6. Read a varbit to check if the setting changed
./microbot-cli varbit 20157
```

### Using the In-Game Settings Search Bar

The OSRS settings interface has a **search bar** at the top. Instead of navigating tabs manually, type a keyword to filter settings. This is the most reliable approach because:
- It finds the setting regardless of which tab it's on
- It brings the matching setting **into the viewport** automatically (no scrolling needed)
- It avoids hardcoding tab indices that can shift on game updates

Use `widgets search "search"` via the CLI to find the search box widget, then send keyboard input with the setting name.

### Viewport Rule

**Always verify a widget is in the viewport before interacting with it.** The game ignores menu actions on off-screen (scrolled-out) widgets. Before invoking any Toggle/Select:
1. Check the widget's bounds via `widgets invoke` (the response includes `bounds`)
2. If `y` is outside the visible area (~200-600px for the settings panel), scroll first
3. After scrolling, re-check bounds before invoking

### Step 3: Map Widget Structure

The OSRS settings interface (group 134) uses two sibling widgets:
- **Child 19 (8781843)**: Text/label layer — shows setting names, descriptions, current values
- **Child 20 (8781844)**: Clickable layer — has Toggle/Select actions with `param0` as the child index
- **Child 24 (8781848)**: Tab categories — used to switch between settings tabs

When the game updates change a setting from a toggle to a dropdown:
- The `Toggle` action at the old `param0` stops working
- A new `Select` action appears at a different `param0`
- The text widget will show the current value (e.g., "Disabled") as a separate child

### Common Pitfalls

- **Widget IDs vary by tab**: Some tabs use child 19 (8781843), others use child 20 (8781844) for their clickable area. Always check the comment in the captured MenuEntry vs the constant.
- **param0 shifts on game updates**: When Jagex adds/removes settings, all `param0` indices after the change shift. Never hardcode without verifying.
- **Off-screen widgets need scrolling**: Settings panel is scrollable. Widgets at high param0 indices (30+) may be below the viewport (y > 600px). The game ignores menu actions on off-screen widgets. Use `scrollSettingsChildIntoView()` before invoking.
- **Thread safety**: `widget.isHidden()` must be called on the client thread. Use `Microbot.getClientThread().runOnClientThreadOptional()` for widget inspection from script threads.
- **Don't conflate concurrent log output**: Multiple threads (blocking events, scripts, auto-login) write to the same console. A MenuEntry logged during your test may come from a completely unrelated thread. Use `varbit` and `widgets describe` CLI checks to verify state, not log analysis.
- **Verify each step via CLI**: When a method returns false, use the CLI to check widget visibility, varbit values, and settings state BEFORE making assumptions about what failed.

### Known Setting Type Changes

| Setting | Old Type | New Type | Changed |
|---------|----------|----------|---------|
| Level-up pop-up notifications | Toggle | 3-option dropdown (Disabled / Show level / Show level & unlocks) | March 11, 2026 |

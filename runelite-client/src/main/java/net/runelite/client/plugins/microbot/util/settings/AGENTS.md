# Settings & Widget Debugging

Settings UI (group 134) changes often — tab indices, `param0` values, and widget types all shift across game updates. Never hardcode; always verify at runtime.

## Workflow
1. Check OSRS Wiki for the setting's current type (toggle / dropdown / slider).
2. With the client running + logged in, use the CLI:
   - `./microbot-cli widgets search "<keywords>"` to locate the widget
   - `./microbot-cli widgets describe <group> <child> --depth 3` to inspect structure
   - `./microbot-cli widgets invoke <group> <child> --param0 N --action <Toggle|Select>`
   - `./microbot-cli varbit <id>` to confirm the change landed
3. Prefer the **in-game settings search bar** over tab navigation — it scrolls the match into view and is stable across updates.

## Settings group 134 layout
- Child 19 — text/label layer
- Child 20 — clickable layer (Toggle/Select, `param0` = row index)
- Child 24 — tab categories

## Gotchas
- Widgets off-screen ignore menu actions. Check bounds from `widgets invoke` response and scroll into view first (`scrollSettingsChildIntoView()`).
- `widget.isHidden()` must run on the client thread (`Microbot.getClientThread().runOnClientThreadOptional(...)`).
- `param0` indices shift whenever Jagex adds/removes settings — re-verify, don't cache.
- Don't diagnose via log output; concurrent threads pollute it. Verify state via `varbit` and `widgets describe`.
- When a setting flips from Toggle → dropdown (or vice versa), old action names stop working silently.

# Clean Screenshots

A RuneLite plugin that pauses the plugins you choose while a screenshot is taken, so their
overlays stay out of the picture, then starts them again.

## What it does

- **Clean screenshot button and hotkey.** Adds a second camera button to the title bar and an
  optional hotkey. Either one pauses your chosen plugins, takes a screenshot, and resumes them.
  The picture is saved exactly as the built-in Screenshot plugin would save it: same folder, and
  the same frame, date, notification and clipboard settings.
- **Clean pop-up screenshots.** When a pop-up the built-in Screenshot plugin will photograph
  opens (collection log, combat task, league task), your chosen plugins are paused before it
  appears and resumed just after the picture is taken.
- **Clean all other screenshots.** Every other picture the built-in Screenshot plugin takes
  (level-ups, quests, kills, pets, drops, deaths, chests, clues, its own button and hotkey and so
  on) is retaken a moment later with your chosen plugins paused, and the clean picture replaces
  the original in the same file.

Only screenshots the built-in Screenshot plugin is set to take are affected, and it keeps working
exactly as before.

## Setup

1. Open the plugin's settings and list the plugins to pause in **Plugins to pause**, separated
   by commas, using the names shown in the plugin list. For example:
   `Ground Markers, NPC Indicators, Tile Indicators`
   A chat message then lists which names were found and which were not.
2. Optionally set a **Hotkey**.
3. Choose which screenshots to take in the built-in Screenshot plugin as usual.

## Good to know

- Paused plugins restart fresh, so anything they were tracking (timers, counters) starts again.
- A retake is taken a moment after the original (the **Delay before picture** setting), so it can
  show a slightly later moment. It only replaces pictures in the Screenshot plugin's own folders,
  and only if the clean picture is the same size, so resizing the window in between keeps the
  original.
- Other plugins that react to new screenshots (for example ones that post them elsewhere) still
  receive the original picture of an automatic screenshot. Pictures from this plugin's own button
  and hotkey are clean from the start.
- The Screenshot plugin itself is never paused.
- Plugins are only stopped and started. Their settings are never changed, and anything still
  paused when the client closes is back as normal on the next launch.
- The plugin makes no network requests.

## Development note

This plugin was written with AI assistance (Claude) and tested in-game by the author.

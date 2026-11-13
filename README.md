# Clean Screenshots

A RuneLite plugin that pauses the plugins you choose while a screenshot is taken, so their
overlays stay out of the picture, then starts them again.

## What it does

- **Clean screenshot button and hotkey.** Adds a second camera button to the title bar and an
  optional hotkey. Either one pauses your chosen plugins, takes a screenshot, and resumes them.
  The picture is saved exactly as the built-in Screenshot plugin would save it (same folder, and
  the same frame, notification and clipboard settings).
- **Clean pop-up screenshots.** When a pop-up notification opens (collection log, combat
  achievement and so on), your chosen plugins are paused until just after the built-in Screenshot
  plugin has taken its picture of it.

The built-in Screenshot plugin, its button and its hotkey are left untouched.

## Setup

1. Open the plugin's settings and list the plugins to pause in **Plugins to pause**, separated
   by commas, using the names shown in the plugin list. For example:
   `Ground Markers, NPC Indicators, Tile Indicators`
2. Optionally set a **Clean screenshot hotkey**.
3. For clean collection log pictures, turn on collection log screenshots in the built-in
   Screenshot plugin and set the in-game collection log notification to include the pop-up.

## Limits

- Automatic screenshots that are not tied to a pop-up (level-ups, pets, deaths, kills, valuable
  drops) are captured by the Screenshot plugin the moment they happen, so they are not cleaned.
- Plugins are only stopped and started. Their settings are never changed, and anything still
  paused when the client closes is back as normal on the next launch.
- The plugin makes no network requests.

## Development note

This plugin was written with AI assistance (Claude) and tested in-game by the author.

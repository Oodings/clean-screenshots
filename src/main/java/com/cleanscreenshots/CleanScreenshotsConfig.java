package com.cleanscreenshots;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("cleanscreenshots")
public interface CleanScreenshotsConfig extends Config
{
	@ConfigSection(
		name = "Pausing",
		description = "Which plugins are paused while a picture is taken",
		position = 0
	)
	String pausingSection = "pausing";

	@ConfigSection(
		name = "Clean screenshot button",
		description = "The extra camera button and hotkey",
		position = 1
	)
	String buttonSection = "button";

	@ConfigSection(
		name = "Automatic screenshots",
		description = "Pictures taken by the built-in Screenshot plugin",
		position = 2
	)
	String automaticSection = "automatic";

	@ConfigItem(
		keyName = "pauseList",
		name = "Plugins to pause",
		description = "Plugins to pause while a picture is taken, separated by commas,<br>"
			+ "using the names shown in the plugin list. After you change this, a chat<br>"
			+ "message lists which names were found.<br>"
			+ "Paused plugins restart fresh, so anything they were tracking (e.g. timers) resets.",
		section = pausingSection,
		position = 0
	)
	default String pauseList()
	{
		return "";
	}

	@Range(max = 2000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "delayMs",
		name = "Delay before picture",
		description = "How long to wait after pausing before the picture is taken,<br>"
			+ "so overlays have time to disappear",
		section = pausingSection,
		position = 1
	)
	default int delayMs()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "hotkey",
		name = "Hotkey",
		description = "Press to take a clean screenshot. Saved like the built-in Screenshot plugin's own<br>"
			+ "(same folder, frame, date, notification and clipboard settings).",
		section = buttonSection,
		position = 0
	)
	default Keybind hotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "showButton",
		name = "Show title bar button",
		description = "Show the clean screenshot camera in the title bar",
		section = buttonSection,
		position = 1
	)
	default boolean showButton()
	{
		return true;
	}

	@ConfigItem(
		keyName = "popupScreenshots",
		name = "Clean pop-up screenshots",
		description = "Pause before a pop-up the Screenshot plugin will photograph<br>"
			+ "(collection log, combat task, league task), so its picture is clean.",
		section = automaticSection,
		position = 0
	)
	default boolean popupScreenshots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "retakeAutomatic",
		name = "Clean all other screenshots",
		description = "When the Screenshot plugin saves any other picture (level-ups, kills, pets, drops,<br>"
			+ "deaths, chests, clues, its own hotkey...), pause and save a clean copy next to it a moment later,<br>"
			+ "named like the original with \"clean\" added. The original is kept.",
		section = automaticSection,
		position = 1
	)
	default boolean retakeAutomatic()
	{
		return true;
	}
}

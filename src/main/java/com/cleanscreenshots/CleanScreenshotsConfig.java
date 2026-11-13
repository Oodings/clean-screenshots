package com.cleanscreenshots;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Units;

@ConfigGroup("cleanscreenshots")
public interface CleanScreenshotsConfig extends Config
{
	@ConfigItem(
		keyName = "pauseList",
		name = "Plugins to pause",
		description = "Plugins to pause while a screenshot is taken. Comma separated, names exactly as shown in the plugin list.",
		position = 1
	)
	default String pauseList()
	{
		return "";
	}

	@ConfigItem(
		keyName = "popupScreenshots",
		name = "Clean pop-up screenshots",
		description = "Pause the plugins while a pop-up notification (collection log, combat achievement and so on) opens, so the Screenshot plugin's own picture of it is clean.",
		position = 2
	)
	default boolean popupScreenshots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hotkey",
		name = "Clean screenshot hotkey",
		description = "Press this to take a clean screenshot. It uses the Screenshot plugin's own frame, notification and clipboard settings.",
		position = 3
	)
	default Keybind hotkey()
	{
		return Keybind.NOT_SET;
	}

	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "delayMs",
		name = "Delay before picture",
		description = "How long to wait after pausing the plugins before a clean screenshot is taken, so their overlays have time to disappear.",
		position = 4
	)
	default int delayMs()
	{
		return 300;
	}
}

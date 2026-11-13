package com.cleanscreenshots;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CleanScreenshotsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CleanScreenshotsPlugin.class);
		RuneLite.main(args);
	}
}

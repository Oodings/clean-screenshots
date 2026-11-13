package com.cleanscreenshots;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ScreenshotTaken;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.screenshot.ScreenshotConfig;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Clean Screenshots",
	description = "Pauses chosen plugins while a screenshot is taken, so their overlays stay out of the picture",
	tags = {"screenshot", "clean", "overlay", "collection", "log"}
)
public class CleanScreenshotsPlugin extends Plugin
{
	private static final String CLOG_MESSAGE = "New item added to your collection log:";
	// Once a pop-up is fully open the Screenshot plugin captures the next frame; wait this long before resuming
	private static final int AFTER_POPUP_MS = 600;
	// If nothing else resumes the plugins (no pop-up, no picture), do it after this long
	private static final int RESUME_TIMEOUT_MS = 10000;

	@Inject
	private Client client;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CleanScreenshotsConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientToolbar clientToolbar;

	// Read only, so a clean screenshot is saved the same way as a normal one
	private ScreenshotConfig screenshotConfig;
	private NavigationButton cleanButton;

	// Everything below is only touched on the Swing thread.
	// Paused plugins stay enabled in the settings and are only stopped, so they come back
	// by themselves if the client closes before they are resumed.
	private final Set<Plugin> paused = new LinkedHashSet<>();
	private boolean hidden;
	private boolean manual;
	private int generation;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			SwingUtilities.invokeLater(() -> manualScreenshot());
		}
	};

	@Provides
	CleanScreenshotsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CleanScreenshotsConfig.class);
	}

	@Override
	protected void startUp()
	{
		screenshotConfig = configManager.getConfig(ScreenshotConfig.class);
		keyManager.registerKeyListener(hotkeyListener);

		cleanButton = NavigationButton.builder()
			.tooltip("Take clean screenshot")
			.icon(cameraIcon())
			.onClick(() -> SwingUtilities.invokeLater(this::manualScreenshot))
			.build();
		clientToolbar.addNavigation(cleanButton);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(hotkeyListener);
		clientToolbar.removeNavigation(cleanButton);
		cleanButton = null;
		resume(generation);
	}

	// ---- Pop-up screenshots: pause while the pop-up opens, let the Screenshot plugin take its picture ----

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.popupScreenshots() || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (!Text.removeTags(event.getMessage()).startsWith(CLOG_MESSAGE))
		{
			return;
		}

		// 1 = chat message only, so no pop-up will follow
		if (client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) == 1)
		{
			return;
		}

		// The chat line arrives before the pop-up, so this gives the overlays a head start
		SwingUtilities.invokeLater(this::pauseAll);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (!config.popupScreenshots())
		{
			return;
		}

		switch (event.getScriptId())
		{
			case ScriptID.NOTIFICATION_START:
				// A pop-up has started to open
				SwingUtilities.invokeLater(this::pauseAll);
				break;
			case ScriptID.NOTIFICATION_DELAY:
				// The pop-up is fully open: this is when the Screenshot plugin takes its picture
				SwingUtilities.invokeLater(() ->
				{
					if (hidden && !manual)
					{
						resumeLater(generation, AFTER_POPUP_MS);
					}
				});
				break;
		}
	}

	// ---- Clean screenshots: pause, take the picture, resume ----

	private void manualScreenshot()
	{
		if (hidden)
		{
			// Already paused for a pop-up; just take the picture
			takePicture();
			return;
		}

		pauseAll();
		manual = true;
		executor.schedule(this::takePicture, Math.max(0, config.delayMs()), TimeUnit.MILLISECONDS);
	}

	private void takePicture()
	{
		// Same call and settings the Screenshot plugin uses for its own hotkey
		imageCapture.takeScreenshot(null, "",
			screenshotConfig.includeFrame(),
			screenshotConfig.notifyWhenTaken(),
			screenshotConfig.copyToClipboard());
	}

	@Subscribe
	public void onScreenshotTaken(ScreenshotTaken event)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (hidden && manual)
			{
				resume(generation);
			}
		});
	}

	// ---- Pausing and resuming plugins (Swing thread only) ----

	private void pauseAll()
	{
		if (hidden)
		{
			return;
		}

		hidden = true;
		manual = false;

		Set<String> names = parse(config.pauseList());
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin == this)
			{
				continue;
			}

			PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor == null || descriptor.hidden() || !names.contains(descriptor.name().toLowerCase()))
			{
				continue;
			}

			try
			{
				// Returns false if the plugin was not running, in which case we leave it alone
				if (pluginManager.stopPlugin(plugin))
				{
					paused.add(plugin);
				}
			}
			catch (PluginInstantiationException e)
			{
				log.warn("Could not pause plugin {}", plugin.getName(), e);
			}
		}

		resumeLater(++generation, RESUME_TIMEOUT_MS);
	}

	private void resumeLater(int gen, int delayMs)
	{
		executor.schedule(() -> SwingUtilities.invokeLater(() -> resume(gen)), delayMs, TimeUnit.MILLISECONDS);
	}

	private void resume(int gen)
	{
		if (!hidden || gen != generation)
		{
			return; // already resumed, or belongs to an earlier picture
		}

		Set<Plugin> toResume = new LinkedHashSet<>(paused);
		paused.clear();
		for (Plugin plugin : toResume)
		{
			try
			{
				pluginManager.startPlugin(plugin);
			}
			catch (PluginInstantiationException e)
			{
				log.warn("Could not resume plugin {}", plugin.getName(), e);
			}
		}

		hidden = false;
		manual = false;
	}

	// A small blue camera with a sparkle, so it can be told apart from the Screenshot plugin's button
	private static BufferedImage cameraIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(120, 200, 255));
		g.drawRect(1, 5, 13, 8);
		g.drawRect(5, 3, 4, 2);
		g.drawOval(5, 6, 5, 5);
		g.setColor(Color.WHITE);
		g.drawLine(12, 1, 14, 1);
		g.drawLine(13, 0, 13, 2);
		g.dispose();
		return image;
	}

	private static Set<String> parse(String list)
	{
		return Arrays.stream(list.split(","))
			.map(s -> s.trim().toLowerCase())
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}
}

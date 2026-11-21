package com.cleanscreenshots;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import static net.runelite.client.RuneLite.SCREENSHOT_DIR;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ScreenshotTaken;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.screenshot.ScreenshotConfig;
import net.runelite.client.plugins.screenshot.ScreenshotPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Clean Screenshots",
	description = "Pauses chosen plugins while screenshots are taken, so their overlays stay out of every picture",
	tags = {"screenshot", "clean", "overlay", "collection", "log"}
)
public class CleanScreenshotsPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "cleanscreenshots";
	private static final String CLOG_MESSAGE = "New item added to your collection log:";
	private static final String COMBAT_TASK_TEXT = "combat task";

	/** After a pop-up is fully open the Screenshot plugin captures the next frame; resume this long after. */
	static final int AFTER_POPUP_MS = 600;

	/** Safety net: any pause is lifted after this long, whatever happens. */
	static final int HOLD_TIMEOUT_MS = 10000;

	/** The pause must have been in place this long before a pop-up picture for that picture to count as clean. */
	static final long POPUP_SETTLE_MS = 200;

	/** How long after a clean pop-up picture its ScreenshotTaken event may arrive (saving takes a moment). */
	static final long POPUP_CLEAN_WINDOW_MS = 5000;

	/** The built-in Screenshot plugin's own folders. Retakes never touch anything else. */
	static final Set<String> CORE_FOLDERS = ImmutableSet.of(
		"Levels", "Quests", "BA High Gambles", "Kingdom Rewards", "Boss Kills", "Clue Scroll Rewards",
		"Friends Chat Kicks", "Pets", "Chest Loot", "Valuable Drops", "Untradeable Drops", "Duels",
		"Collection Log", "PvP Kills", "Deaths", "Combat Achievements", "Wilderness Loot Chest", "League Tasks");

	/** The date and time RuneLite adds to every screenshot name, with any "(1)" duplicate number. */
	private static final Pattern NAME_TIMESTAMP = Pattern.compile("(^| )\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(\\(\\d+\\))?\\.png$");

	/** Folders of the pop-up pictures that are made clean by pausing beforehand. */
	static final Set<String> POPUP_FOLDERS = ImmutableSet.of("Collection Log", "Combat Achievements", "League Tasks");

	/** Interfaces where the Screenshot plugin's "Display date" writes the date on the Report button. */
	private static final Set<Integer> REPORT_BUTTON_TLIS = ImmutableSet.of(
		InterfaceID.TOPLEVEL, InterfaceID.TOPLEVEL_OSRS_STRETCH, InterfaceID.TOPLEVEL_PRE_EOC);

	/** Same format the Screenshot plugin uses for the date. Only used on the client thread. */
	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MMM. dd, yyyy");

	/** Recognises a date left on the Report button, e.g. "Nov. 21, 2026". */
	private static final Pattern DATE_TEXT = Pattern.compile("\\p{L}{2,}\\.? \\d{1,2}, \\d{4}");

	/** For this many game ticks after a dated picture, make sure the date does not stay on the Report button. */
	private static final int REPORT_WATCH_TICKS = 10;

	/** Marks our own saves, so our own ScreenshotTaken events are not mistaken for the Screenshot plugin's. */
	private static final ThreadLocal<Boolean> SAVING_OWN = ThreadLocal.withInitial(() -> false);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

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
	private DrawManager drawManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatMessageManager chatMessageManager;

	/** Read only, so clean pictures are saved the same way as the Screenshot plugin's own. */
	private ScreenshotConfig screenshotConfig;
	private Plugin screenshotPlugin;
	private NavigationButton cleanButton;

	// ---- Swing thread only ----
	// Each reason to pause (the button, a pop-up, a retake) holds the pause separately; plugins resume when
	// every hold is released. Paused plugins stay enabled in the settings and are only stopped, so they come
	// back by themselves if the client closes while they are paused.
	private final Set<Plugin> paused = new LinkedHashSet<>();
	private final Map<Object, Integer> holds = new HashMap<>();
	private final Object popupHold = new Object();
	private Object retakeHold;
	private int holdSeq;

	// ---- Shared between threads ----
	/** Bumped when the plugin is turned off, so callbacks from before are ignored. */
	private volatile int epoch;
	/** When the pop-up pause took effect, or 0 if not held. */
	private volatile long popupHeldSinceMs;
	/** A clean pop-up picture is expected from the Screenshot plugin until this time. */
	private volatile long popupCleanUntilMs;
	private final List<RetakeTarget> pendingRetakes = new ArrayList<>();
	private final Queue<Capture> captureQueue = new ConcurrentLinkedQueue<>();

	// ---- Client thread only ----
	private String savedReportText;
	private String lastReportText;
	private int datedCaptures;
	private int reportWatchTicks;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			onEdt(CleanScreenshotsPlugin.this::manualScreenshot);
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
		screenshotPlugin = null;
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof ScreenshotPlugin)
			{
				screenshotPlugin = plugin;
			}
		}

		keyManager.registerKeyListener(hotkeyListener);
		cleanButton = NavigationButton.builder()
			.tooltip("Take clean screenshot")
			.icon(cameraIcon())
			.onClick(() -> onEdt(this::manualScreenshot))
			.build();
		if (config.showButton())
		{
			clientToolbar.addNavigation(cleanButton);
		}
	}

	@Override
	protected void shutDown()
	{
		epoch++;
		keyManager.unregisterKeyListener(hotkeyListener);
		clientToolbar.removeNavigation(cleanButton);
		cleanButton = null;

		holds.clear();
		retakeHold = null;
		popupHeldSinceMs = 0;
		popupCleanUntilMs = 0;
		synchronized (pendingRetakes)
		{
			pendingRetakes.clear();
		}
		captureQueue.clear();
		resumeAll();
		clientThread.invokeLater(this::restoreReportText);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("showButton".equals(event.getKey()))
		{
			onEdt(() ->
			{
				if (cleanButton == null)
				{
					return;
				}
				clientToolbar.removeNavigation(cleanButton);
				if (config.showButton())
				{
					clientToolbar.addNavigation(cleanButton);
				}
			});
		}
		else if ("pauseList".equals(event.getKey()))
		{
			onEdt(this::reportPauseList);
		}
	}

	// ---- Pop-ups: pause before a pop-up the Screenshot plugin will photograph ----

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.popupScreenshots()
			|| (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
			|| !screenshotPluginOn())
		{
			return;
		}

		// These chat lines arrive before their pop-up, which gives the overlays a head start.
		String message = Text.removeTags(event.getMessage());
		if (message.startsWith(CLOG_MESSAGE)
			&& screenshotConfig.screenshotCollectionLogEntries()
			&& client.getVarbitValue(VarbitID.OPTION_COLLECTION_NEW_ITEM) != 1) // 1 = chat only, no pop-up
		{
			onEdt(this::holdForPopup);
		}
		else if (message.contains(COMBAT_TASK_TEXT)
			&& screenshotConfig.screenshotCombatAchievements()
			&& client.getVarbitValue(VarbitID.CA_TASK_POPUP) == 0) // 0 = pop-up
		{
			onEdt(this::holdForPopup);
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		switch (event.getScriptId())
		{
			case ScriptID.NOTIFICATION_START:
				if (config.popupScreenshots() && screenshotPluginOn()
					&& willBePhotographed(client.getVarcStrValue(VarClientID.NOTIFICATION_TITLE)))
				{
					onEdt(this::holdForPopup);
				}
				break;
			case ScriptID.NOTIFICATION_DELAY:
				// The pop-up is fully open: the Screenshot plugin takes its picture now.
				long since = popupHeldSinceMs;
				if (since > 0 && now() - since >= POPUP_SETTLE_MS)
				{
					popupCleanUntilMs = now() + POPUP_CLEAN_WINDOW_MS;
				}
				// Queued after any pending holdForPopup, so a pause that has not taken effect yet is still released.
				onEdt(() ->
				{
					Integer gen = holds.get(popupHold);
					if (gen != null)
					{
						scheduleRelease(popupHold, gen, AFTER_POPUP_MS);
					}
				});
				break;
		}
	}

	/** Mirrors the Screenshot plugin's own rules for which pop-ups it photographs. */
	private boolean willBePhotographed(String title)
	{
		if (title == null)
		{
			return false;
		}
		return (title.equalsIgnoreCase("Collection log") && screenshotConfig.screenshotCollectionLogEntries())
			|| (title.equalsIgnoreCase("Combat Task Completed!") && screenshotConfig.screenshotCombatAchievements()
				&& client.getVarbitValue(VarbitID.CA_TASK_POPUP) == 0)
			|| (title.equalsIgnoreCase("League Task Complete!") && screenshotConfig.screenshotLeagueTasks());
	}

	private void holdForPopup()
	{
		if (holds.containsKey(popupHold))
		{
			// Another pop-up while still paused: keep the pause going for this one too.
			int gen = ++holdSeq;
			holds.put(popupHold, gen);
			scheduleRelease(popupHold, gen, HOLD_TIMEOUT_MS);
			return;
		}
		acquire(popupHold);
		popupHeldSinceMs = now();
	}

	// ---- The clean screenshot button and hotkey ----

	private void manualScreenshot()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Object hold = new Object();
		acquire(hold);
		scheduleCapture(new Capture(hold, epoch, false));
	}

	// ---- Everything else: retake the Screenshot plugin's picture cleanly ----

	@Subscribe
	public void onScreenshotTaken(ScreenshotTaken event)
	{
		if (SAVING_OWN.get() || !config.retakeAutomatic())
		{
			return;
		}

		RetakeTarget target = RetakeTarget.of(String.valueOf(event.getPath()), String.valueOf(SCREENSHOT_DIR));
		if (target == null || event.getScreenshot() == null)
		{
			return;
		}

		if (target.subDir != null && POPUP_FOLDERS.contains(target.subDir) && now() <= popupCleanUntilMs)
		{
			return; // taken while paused for the pop-up: already clean
		}

		synchronized (pendingRetakes)
		{
			pendingRetakes.add(target);
		}
		onEdt(this::startRetakeRound);
	}

	private void startRetakeRound()
	{
		if (retakeHold != null)
		{
			return; // a round is already under way and will include this picture
		}
		synchronized (pendingRetakes)
		{
			if (pendingRetakes.isEmpty())
			{
				return;
			}
		}

		Object hold = new Object();
		retakeHold = hold;
		acquire(hold);
		scheduleCapture(new Capture(hold, epoch, true));
	}

	// ---- Capturing a frame (shared by the button and retakes) ----

	private void scheduleCapture(Capture capture)
	{
		executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (capture.epoch == epoch)
			{
				captureQueue.add(capture);
			}
		}), Math.max(0, Math.min(2000, config.delayMs())), TimeUnit.MILLISECONDS);
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		if (captureQueue.isEmpty())
		{
			return;
		}

		// Same as the Screenshot plugin: show the date on the Report button for this frame if "Display date" is on.
		boolean dated = false;
		if (screenshotConfig.displayDate() && REPORT_BUTTON_TLIS.contains(client.getTopLevelInterfaceId()))
		{
			Widget report = client.getWidget(InterfaceID.Chatbox.REPORTABUSE_TEXT1);
			if (report != null)
			{
				if (savedReportText == null)
				{
					// The Screenshot plugin may have put the date there for its own picture this same frame.
					String text = report.getText();
					savedReportText = text != null && DATE_TEXT.matcher(text).matches() ? lastReportText : text;
					if (savedReportText != null)
					{
						lastReportText = savedReportText;
					}
				}
				report.setText(DATE_FORMAT.format(new Date()));
				dated = true;
			}
		}

		Capture capture;
		while ((capture = captureQueue.poll()) != null)
		{
			Capture c = capture;
			boolean d = dated;
			if (d)
			{
				datedCaptures++;
				reportWatchTicks = REPORT_WATCH_TICKS;
			}
			drawManager.requestNextFrameListener(image -> onFrame(c, image, d));
		}
	}

	/** Called with the captured frame (on the client thread). */
	private void onFrame(Capture capture, Image image, boolean dated)
	{
		if (dated && --datedCaptures <= 0)
		{
			datedCaptures = 0;
			restoreReportText();
		}

		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		List<RetakeTarget> targets = null;
		if (capture.retake)
		{
			synchronized (pendingRetakes)
			{
				targets = new ArrayList<>(pendingRetakes);
				pendingRetakes.clear();
			}
		}

		// The picture is captured: the plugins can come back straight away.
		onEdt(() -> captureDone(capture));

		if (capture.epoch != epoch || !loggedIn)
		{
			return; // turned off meanwhile, or no longer in game (never save the login screen)
		}

		List<RetakeTarget> retakeTargets = targets;
		executor.submit(() -> save(capture, image, retakeTargets));
	}

	private void captureDone(Capture capture)
	{
		release(capture.hold, -1);
		if (capture.retake)
		{
			startRetakeRound(); // pictures that arrived after this frame was taken
		}
	}

	private void save(Capture capture, Image image, List<RetakeTarget> targets)
	{
		BufferedImage picture = screenshotConfig.includeFrame()
			? imageCapture.addClientFrame(image)
			: ImageUtil.bufferedImageFromImage(image);

		if (!capture.retake)
		{
			SAVING_OWN.set(true);
			try
			{
				imageCapture.saveScreenshot(picture, "", null,
					screenshotConfig.notifyWhenTaken(), screenshotConfig.copyToClipboard());
			}
			finally
			{
				SAVING_OWN.set(false);
			}
			return;
		}

		// Saved through RuneLite as a second picture next to the original, which is left as it is.
		SAVING_OWN.set(true);
		try
		{
			for (RetakeTarget target : targets)
			{
				imageCapture.saveScreenshot(picture, target.cleanName(), target.subDir,
					false, screenshotConfig.copyToClipboard());
			}
		}
		finally
		{
			SAVING_OWN.set(false);
		}
	}

	private void restoreReportText()
	{
		if (savedReportText == null)
		{
			return;
		}
		Widget report = client.getWidget(InterfaceID.Chatbox.REPORTABUSE_TEXT1);
		if (report != null)
		{
			report.setText(savedReportText);
		}
		savedReportText = null;
	}

	/**
	 * If both this plugin and the Screenshot plugin put the date on the Report button in the same frame, the
	 * Screenshot plugin may remember the date as the button's text and put it back later. Undo that.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (reportWatchTicks <= 0)
		{
			return;
		}
		reportWatchTicks--;

		Widget report = client.getWidget(InterfaceID.Chatbox.REPORTABUSE_TEXT1);
		if (datedCaptures == 0 && savedReportText == null && lastReportText != null && report != null
			&& report.getText() != null && DATE_TEXT.matcher(report.getText()).matches())
		{
			report.setText(lastReportText);
		}
	}

	// ---- Holding and releasing the pause (Swing thread only) ----

	private void acquire(Object hold)
	{
		boolean first = holds.isEmpty();
		int gen = ++holdSeq;
		holds.put(hold, gen);
		if (first)
		{
			pauseAll();
		}
		scheduleRelease(hold, gen, HOLD_TIMEOUT_MS);
	}

	/** Releases a hold. gen -1 releases whatever generation is held; otherwise only that generation. */
	private void release(Object hold, int gen)
	{
		Integer held = holds.get(hold);
		if (held == null || (gen != -1 && held != gen))
		{
			return;
		}
		holds.remove(hold);
		if (hold == popupHold)
		{
			popupHeldSinceMs = 0;
		}
		if (hold == retakeHold)
		{
			retakeHold = null;
		}
		if (holds.isEmpty())
		{
			resumeAll();
		}
	}

	private void scheduleRelease(Object hold, int gen, int delayMs)
	{
		int s = epoch;
		executor.schedule(() -> onEdt(() ->
		{
			if (s == epoch)
			{
				release(hold, gen);
			}
		}), delayMs, TimeUnit.MILLISECONDS);
	}

	private void pauseAll()
	{
		Set<String> names = pauseNames();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
			if (!canPause(plugin, descriptor) || !names.contains(descriptor.name().toLowerCase()))
			{
				continue;
			}

			try
			{
				// Returns false if the plugin was not running, in which case it is left alone.
				if (pluginManager.stopPlugin(plugin))
				{
					paused.add(plugin);
				}
			}
			catch (PluginInstantiationException e)
			{
				log.warn("Could not pause plugin {}", descriptor.name(), e);
			}
		}
	}

	private void resumeAll()
	{
		List<Plugin> toResume = new ArrayList<>(paused);
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
	}

	// ---- Settings feedback ----

	/** After the list changes, say in chat which names matched a plugin and which didn't. */
	private void reportPauseList()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Map<String, String> known = new TreeMap<>();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
			if (canPause(plugin, descriptor))
			{
				known.put(descriptor.name().toLowerCase(), descriptor.name());
			}
		}

		List<String> found = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String entry : config.pauseList().split(","))
		{
			String name = entry.trim();
			if (name.isEmpty())
			{
				continue;
			}
			String match = known.get(name.toLowerCase());
			if (match != null)
			{
				found.add(match);
			}
			else
			{
				missing.add(name);
			}
		}

		String text = found.isEmpty()
			? "Clean Screenshots: no plugins will be paused."
			: "Clean Screenshots will pause: " + String.join(", ", found) + ".";
		if (!missing.isEmpty())
		{
			text += " Not found: " + String.join(", ", missing) + ".";
		}
		if (names(missing).contains("screenshot"))
		{
			text += " (The Screenshot plugin takes the pictures, so it is never paused.)";
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(text)
			.build());
	}

	// ---- Helpers ----

	private boolean screenshotPluginOn()
	{
		return screenshotPlugin != null && pluginManager.isPluginActive(screenshotPlugin);
	}

	/** Never pause this plugin, the Screenshot plugin (it takes the pictures) or hidden core plugins. */
	private boolean canPause(Plugin plugin, PluginDescriptor descriptor)
	{
		return plugin != this && !(plugin instanceof ScreenshotPlugin) && descriptor != null && !descriptor.hidden();
	}

	private static Set<String> names(List<String> list)
	{
		Set<String> lower = new LinkedHashSet<>();
		for (String name : list)
		{
			lower.add(name.toLowerCase());
		}
		return lower;
	}

	private Set<String> pauseNames()
	{
		Set<String> names = new LinkedHashSet<>();
		for (String entry : Arrays.asList(config.pauseList().split(",")))
		{
			String name = entry.trim().toLowerCase();
			if (!name.isEmpty())
			{
				names.add(name);
			}
		}
		return names;
	}

	/** Runs on the Swing thread, where plugins must be started and stopped. */
	void onEdt(Runnable runnable)
	{
		SwingUtilities.invokeLater(runnable);
	}

	long now()
	{
		return System.currentTimeMillis();
	}

	/** A small blue camera with a sparkle, so it can be told apart from the Screenshot plugin's button. */
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

	/** One pending frame capture. */
	private static final class Capture
	{
		final Object hold;
		final int epoch;
		final boolean retake;

		Capture(Object hold, int epoch, boolean retake)
		{
			this.hold = hold;
			this.epoch = epoch;
			this.retake = retake;
		}
	}

	/** A Screenshot plugin picture waiting for its clean copy. */
	static final class RetakeTarget
	{
		/** The Screenshot plugin folder, or null for a hotkey or button picture in the player folder. */
		final String subDir;
		/** The picture's name without RuneLite's date and time, e.g. "Zulrah(100)"; empty for hotkey pictures. */
		final String name;

		private RetakeTarget(String subDir, String name)
		{
			this.subDir = subDir;
			this.name = name;
		}

		String cleanName()
		{
			return name.isEmpty() ? "Clean" : name + " clean";
		}

		/**
		 * Reads a saved screenshot's path: screenshots/player/[folder/]name date.png. Returns null unless it is
		 * one of the Screenshot plugin's own pictures. Only the text of the path is used.
		 */
		static RetakeTarget of(String path, String screenshotDir)
		{
			if (path == null || screenshotDir == null || !path.startsWith(screenshotDir)
				|| path.length() <= screenshotDir.length() + 1)
			{
				return null;
			}
			char separator = path.charAt(screenshotDir.length());
			if (separator != '/' && separator != '\\')
			{
				return null; // a different folder whose name starts the same
			}
			String[] parts = path.substring(screenshotDir.length() + 1).split("[/\\\\]");
			String fileName = parts[parts.length - 1];
			Matcher stamp = NAME_TIMESTAMP.matcher(fileName);
			if (!stamp.find())
			{
				return null;
			}
			String name = fileName.substring(0, stamp.start());

			if (parts.length == 2)
			{
				// Player folder: only the Screenshot plugin's hotkey and button pictures, which are just a date.
				return name.isEmpty() ? new RetakeTarget(null, "") : null;
			}
			if (parts.length == 3 && CORE_FOLDERS.contains(parts[1]))
			{
				return new RetakeTarget(parts[1], name);
			}
			return null;
		}
	}
}

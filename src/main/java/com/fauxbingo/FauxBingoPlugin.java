package com.fauxbingo;

import com.fauxbingo.handlers.CollectionLogHandler;
import com.fauxbingo.handlers.DeathHandler;
import com.fauxbingo.handlers.LootEventHandler;
import com.fauxbingo.handlers.ManualScreenshotHandler;
import com.fauxbingo.handlers.PetChatHandler;
import com.fauxbingo.handlers.RaidLootHandler;
import com.fauxbingo.handlers.ValuableDropHandler;
import com.fauxbingo.overlay.TeamOverlay;
import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.EventEnvelopeSink;
import com.fauxbingo.services.EventsApiService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.MeService;
import com.fauxbingo.services.PresenceService;
import com.fauxbingo.services.TeamIconService;
import com.fauxbingo.trackers.XpTracker;
import com.google.inject.Provides;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Faux Bingo",
	description = "Helper plugin for Bingo events ran in the Faux Clan/Community",
	tags = {"faux", "bingo"}
)
public class FauxBingoPlugin extends Plugin
{
	/** Guice binding name for the bingo API base URL. */
	public static final String API_BASE_URL_KEY = "fauxBingoApiBaseUrl";

	/** Base URL for the v1 bingo API (me, teams, seen, events). Not configurable, the player token is. */
	private static final String BINGO_API_BASE_URL = "https://fauxbingo.com";

	private static final String LOOT_TRACKER_PLUGIN_NAME = "Loot Tracker";

	@Inject
	private Client client;

	@Inject
	private FauxBingoConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TeamOverlay teamOverlay;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private MeService meService;

	@Inject
	private PresenceService presenceService;

	@Inject
	private EventsApiService eventsApiService;

	@Inject
	private InteractionTrackingService interactionTrackingService;

	@Inject
	private DropCorrelationService dropCorrelationService;

	@Inject
	private LootEventHandler lootEventHandler;

	@Inject
	private PetChatHandler petChatHandler;

	@Inject
	private CollectionLogHandler collectionLogHandler;

	@Inject
	private ValuableDropHandler valuableDropHandler;

	@Inject
	private RaidLootHandler raidLootHandler;

	@Inject
	private ManualScreenshotHandler manualScreenshotHandler;

	@Inject
	private DeathHandler deathHandler;

	@Inject
	private TeamIconService teamIconService;

	@Inject
	private XpTracker xpTracker;

	private boolean loginTriggered = false;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Faux Bingo started!");

		meService.start();
		presenceService.start();
		eventsApiService.start();
		teamIconService.start();
		dropCorrelationService.start();

		for (Object subscriber : eventSubscribers())
		{
			eventBus.register(subscriber);
		}

		manualScreenshotHandler.register();
		overlayManager.add(teamOverlay);

		warnIfLootTrackerDisabled();

		// Check if already logged in
		checkLogin();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Faux Bingo stopped!");

		for (Object subscriber : eventSubscribers())
		{
			eventBus.unregister(subscriber);
		}

		manualScreenshotHandler.unregister();
		overlayManager.remove(teamOverlay);

		meService.shutdown();
		presenceService.shutdown();
		eventsApiService.shutdown();
		teamIconService.shutdown();
		dropCorrelationService.shutdown();
		xpTracker.reset();

		resetState();
	}

	/**
	 * Objects carrying their own {@code @Subscribe} methods. Registered in startUp, unregistered in
	 * shutDown. Built on demand so both paths cannot drift apart.
	 */
	private List<Object> eventSubscribers()
	{
		return Arrays.asList(
			lootEventHandler,
			petChatHandler,
			collectionLogHandler,
			valuableDropHandler,
			raidLootHandler,
			deathHandler,
			interactionTrackingService,
			teamIconService);
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged event)
	{
		resetState();
		checkLogin();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("fauxbingo"))
		{
			return;
		}

		if (event.getKey().equals("enableBingoApi") && config.enableBingoApi())
		{
			loginTriggered = false;
			checkLogin();
			meService.start();
			presenceService.start();
			eventsApiService.start();
			teamIconService.start();
			Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null && !local.getName().isEmpty())
			{
				meService.refresh(local.getName());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.LOGGING_IN)
		{
			meService.onLogout();
			resetState();
		}

		if (event.getGameState() == GameState.LOGGED_IN)
		{
			checkLogin();
		}

		xpTracker.onGameStateChanged(event);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		xpTracker.onStatChanged(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		checkLogin();
		xpTracker.onGameTick();
	}

	private void resetState()
	{
		loginTriggered = false;
		collectionLogHandler.resetState();
		raidLootHandler.resetState();
		eventsApiService.resetSession();
	}

	private void warnIfLootTrackerDisabled()
	{
		try
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (!LOOT_TRACKER_PLUGIN_NAME.equals(plugin.getName()))
				{
					continue;
				}

				if (!pluginManager.isPluginEnabled(plugin))
				{
					log.warn("Loot Tracker is disabled. Loot from Tempoross, Wintertodt, clue caskets, "
						+ "chests and other non-combat sources will not be reported.");
				}
				return;
			}

			log.warn("Loot Tracker not found. Non-combat loot will not be reported.");
		}
		catch (Exception e)
		{
			log.debug("Could not determine Loot Tracker state", e);
		}
	}

	private void checkLogin()
	{
		if (loginTriggered || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local != null)
		{
			String name = local.getName();
			if (name != null && !name.isEmpty())
			{
				meService.onLogin(name);
				presenceService.onLogin(name);
				loginTriggered = true;
			}
		}
	}

	@Provides
	FauxBingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FauxBingoConfig.class);
	}

	@Provides
	@Named(API_BASE_URL_KEY)
	String provideApiBaseUrl()
	{
		return BINGO_API_BASE_URL;
	}

	@Provides
	EventEnvelopeSink provideEventEnvelopeSink(EventsApiService sink)
	{
		return sink;
	}
}

package com.fauxbingo;

import com.fauxbingo.handlers.CollectionLogHandler;
import com.fauxbingo.handlers.DeathHandler;
import com.fauxbingo.handlers.LootEventHandler;
import com.fauxbingo.handlers.ManualScreenshotHandler;
import com.fauxbingo.handlers.PetChatHandler;
import com.fauxbingo.handlers.RaidLootHandler;
import com.fauxbingo.handlers.ValuableDropHandler;
import com.fauxbingo.overlay.TeamOverlay;
import com.fauxbingo.services.BingoConfigService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.WiseOldManService;
import com.fauxbingo.services.TeamIconService;
import net.runelite.client.callback.ClientThread;
import com.fauxbingo.trackers.XpTracker;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "Faux Bingo",
	description = "Helper plugin for Bingo events ran in the Faux Clan/Community",
	tags = {"faux", "bingo"}
)
public class FauxBingoPlugin extends Plugin
{
	/** Base URL for the bingo API (logs, deaths, bingo-config). Not configurable. */
	private static final String BINGO_API_BASE_URL = "https://faux-api.thatohio.me";

	@Inject
	private Client client;

	@Inject
	private FauxBingoConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TeamOverlay teamOverlay;

	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	private EventProcessor eventProcessor;
	private BingoConfigService bingoConfigService;
	private InteractionTrackingService interactionTrackingService;
	private WebhookService webhookService;
	private ScreenshotService screenshotService;
	private WiseOldManService wiseOldManService;
	private LogService logService;
	private LootEventHandler lootEventHandler;
	private PetChatHandler petChatHandler;
	private CollectionLogHandler collectionLogHandler;
	private ValuableDropHandler valuableDropHandler;
	private RaidLootHandler raidLootHandler;
	private ManualScreenshotHandler manualScreenshotHandler;
	private DeathHandler deathHandler;
	private TeamIconService teamIconService;
	private XpTracker xpTracker;
	private boolean loginTriggered = false;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Faux Bingo started!");

		// Initialize services
		bingoConfigService = new BingoConfigService(client, config, configManager, BINGO_API_BASE_URL, okHttpClient, gson, executor);
		bingoConfigService.start();
		teamIconService = new TeamIconService(client, config, BINGO_API_BASE_URL, okHttpClient, gson, executor, clientThread);
		teamIconService.start();
		interactionTrackingService = new InteractionTrackingService(client);
		webhookService = new WebhookService(client, okHttpClient, executor, config, bingoConfigService);
		screenshotService = new ScreenshotService(client, clientThread, drawManager, config);
		wiseOldManService = new WiseOldManService(client, config, okHttpClient, gson);
		logService = new LogService(client, config, BINGO_API_BASE_URL, okHttpClient, gson, executor);
		eventProcessor = new EventProcessor();

		// Initialize trackers
		xpTracker = new XpTracker(client, config, wiseOldManService);

		// Initialize handlers
		lootEventHandler = new LootEventHandler(client, config, bingoConfigService, interactionTrackingService, itemManager, webhookService, logService, screenshotService, executor);
		petChatHandler = new PetChatHandler(client, config, webhookService, logService, screenshotService, executor);
		collectionLogHandler = new CollectionLogHandler(client, config, webhookService, logService, screenshotService, executor);
		valuableDropHandler = new ValuableDropHandler(client, config, webhookService, logService, screenshotService, executor);
		raidLootHandler = new RaidLootHandler(client, config, bingoConfigService, webhookService, logService, screenshotService, executor, itemManager);
		manualScreenshotHandler = new ManualScreenshotHandler(client, config, webhookService, screenshotService, executor, keyManager);
		deathHandler = new DeathHandler(client, logService, interactionTrackingService);

		// Register event handlers
		eventProcessor.registerHandler(lootEventHandler.createNpcLootHandler());
		eventProcessor.registerHandler(lootEventHandler.createPlayerLootHandler());
		eventProcessor.registerHandler(petChatHandler);
		eventProcessor.registerHandler(collectionLogHandler.createChatHandler());
		eventProcessor.registerHandler(collectionLogHandler.createScriptHandler());
		eventProcessor.registerHandler(valuableDropHandler);
		eventProcessor.registerHandler(raidLootHandler.createChatHandler());
		eventProcessor.registerHandler(raidLootHandler.createWidgetHandler());
		eventProcessor.registerHandler(raidLootHandler.createItemContainerHandler());
		eventProcessor.registerHandler(deathHandler.createActorDeathHandler());
		eventProcessor.registerHandler(interactionTrackingService.createInteractingChangedHandler());
		eventProcessor.registerHandler(teamIconService.createChatHandler());

		// Register manual screenshot hotkey
		manualScreenshotHandler.register();

		// Register overlay
		overlayManager.add(teamOverlay);

		log.info("Event processor initialized with all handlers");

		// Check if already logged in
		checkLogin();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Faux Bingo stopped!");

		// Unregister overlay
		overlayManager.remove(teamOverlay);

		if (bingoConfigService != null)
		{
			bingoConfigService.shutdown();
		}

		if (teamIconService != null)
		{
			teamIconService.shutdown();
		}

		// Unregister manual screenshot hotkey
		if (manualScreenshotHandler != null)
		{
			manualScreenshotHandler.unregister();
		}

		// Clean up event processor
		if (eventProcessor != null)
		{
			eventProcessor.clearHandlers();
		}

		// Reset XP tracker
		if (xpTracker != null)
		{
			xpTracker.reset();
		}

		// Reset state
		resetState();
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		eventProcessor.processEvent(event);
	}
	
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		eventProcessor.processEvent(event);
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
			bingoConfigService.start();
			if (teamIconService != null)
			{
				teamIconService.start();
			}
			net.runelite.api.Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null && !local.getName().isEmpty())
			{
				bingoConfigService.refresh(local.getName());
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.LOGGING_IN)
		{
			if (bingoConfigService != null)
			{
				bingoConfigService.onLogout();
			}
			resetState();
		}

		if (event.getGameState() == GameState.LOGGED_IN)
		{
			checkLogin();
		}

		// Pass event to XP tracker
		if (xpTracker != null)
		{
			xpTracker.onGameStateChanged(event);
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Pass event to XP tracker
		if (xpTracker != null)
		{
			xpTracker.onStatChanged(event);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		checkLogin();
		// Pass event to XP tracker
		if (xpTracker != null)
		{
			xpTracker.onGameTick();
		}
	}

	private void resetState()
	{
		loginTriggered = false;
		if (collectionLogHandler != null)
		{
			collectionLogHandler.resetState();
		}

		if (raidLootHandler != null)
		{
			raidLootHandler.resetState();
		}

		if (deathHandler != null)
		{
			deathHandler.resetState();
		}
	}

	private void checkLogin()
	{
		if (loginTriggered || client.getGameState() != GameState.LOGGED_IN || bingoConfigService == null)
		{
			return;
		}

		net.runelite.api.Player local = client.getLocalPlayer();
		if (local != null)
		{
			String name = local.getName();
			if (name != null && !name.isEmpty())
			{
				bingoConfigService.onLogin(name);
				loginTriggered = true;
			}
		}
	}

	@Provides
	FauxBingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FauxBingoConfig.class);
	}
}

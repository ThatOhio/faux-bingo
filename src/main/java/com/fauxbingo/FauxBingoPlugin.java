package com.fauxbingo;

import com.fauxbingo.handlers.CollectionLogHandler;
import com.fauxbingo.handlers.LootEventHandler;
import com.fauxbingo.handlers.ManualScreenshotHandler;
import com.fauxbingo.handlers.PetChatHandler;
import com.fauxbingo.handlers.RaidLootHandler;
import com.fauxbingo.handlers.ValuableDropHandler;
import com.fauxbingo.overlay.TeamOverlay;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.WiseOldManService;
import com.fauxbingo.trackers.XpTracker;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
	private ScheduledExecutorService executor;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TeamOverlay teamOverlay;

	@Inject
	private Gson gson;

	private EventProcessor eventProcessor;
	private WebhookService webhookService;
	private WiseOldManService wiseOldManService;
	private LootEventHandler lootEventHandler;
	private PetChatHandler petChatHandler;
	private CollectionLogHandler collectionLogHandler;
	private ValuableDropHandler valuableDropHandler;
	private RaidLootHandler raidLootHandler;
	private ManualScreenshotHandler manualScreenshotHandler;
	private XpTracker xpTracker;

	private boolean shouldSendMessage = false;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Faux Bingo started!");

		// Initialize services
		webhookService = new WebhookService(okHttpClient);
		wiseOldManService = new WiseOldManService(client, config, okHttpClient, gson);
		eventProcessor = new EventProcessor();

		// Initialize trackers
		xpTracker = new XpTracker(client, config, wiseOldManService);

		// Initialize handlers
		lootEventHandler = new LootEventHandler(config, itemManager, webhookService, drawManager, executor);
		petChatHandler = new PetChatHandler(client, config, webhookService, drawManager, executor);
		collectionLogHandler = new CollectionLogHandler(client, config, webhookService, drawManager, executor);
		valuableDropHandler = new ValuableDropHandler(client, config, webhookService, drawManager, executor);
		raidLootHandler = new RaidLootHandler(client, config, webhookService, drawManager, executor);
		manualScreenshotHandler = new ManualScreenshotHandler(client, config, webhookService, drawManager, executor, keyManager);

		// Register event handlers
		eventProcessor.registerHandler(lootEventHandler.createNpcLootHandler());
		eventProcessor.registerHandler(lootEventHandler.createPlayerLootHandler());
		eventProcessor.registerHandler(petChatHandler);
		eventProcessor.registerHandler(collectionLogHandler.createChatHandler());
		eventProcessor.registerHandler(collectionLogHandler.createScriptHandler());
		eventProcessor.registerHandler(valuableDropHandler);
		eventProcessor.registerHandler(raidLootHandler.createChatHandler());
		eventProcessor.registerHandler(raidLootHandler.createWidgetHandler());

		// Register manual screenshot hotkey
		manualScreenshotHandler.register();

		// Register overlay
		overlayManager.add(teamOverlay);

		log.info("Event processor initialized with all handlers");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Faux Bingo stopped!");

		// Unregister overlay
		overlayManager.remove(teamOverlay);

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
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!shouldSendMessage) return;
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		if (!shouldSendMessage) return;
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!shouldSendMessage) return;
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (!shouldSendMessage) return;
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!shouldSendMessage) return;
		eventProcessor.processEvent(event);
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged event)
	{
		resetState();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			resetState();
		}
		else
		{
			shouldSendMessage = true;
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
		// Pass event to XP tracker
		if (xpTracker != null)
		{
			xpTracker.onGameTick();
		}
	}

	private void resetState()
	{
		shouldSendMessage = false;

		if (collectionLogHandler != null)
		{
			collectionLogHandler.resetState();
		}

		if (raidLootHandler != null)
		{
			raidLootHandler.resetState();
		}
	}

	@Provides
	FauxBingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FauxBingoConfig.class);
	}
}

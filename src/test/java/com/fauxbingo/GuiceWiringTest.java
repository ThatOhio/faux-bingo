package com.fauxbingo;

import com.fauxbingo.handlers.CollectionLogHandler;
import com.fauxbingo.handlers.DeathHandler;
import com.fauxbingo.handlers.LootEventHandler;
import com.fauxbingo.handlers.ManualScreenshotHandler;
import com.fauxbingo.handlers.PetChatHandler;
import com.fauxbingo.handlers.RaidLootHandler;
import com.fauxbingo.handlers.ValuableDropHandler;
import com.fauxbingo.services.BingoConfigService;
import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.EventEnvelopeSink;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.LoggingEventEnvelopeSink;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.TeamIconService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.WiseOldManService;
import com.fauxbingo.trackers.XpTracker;
import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.DrawManager;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * The plugin gets its collaborators from Guice, so a missing binding only shows up when RuneLite
 * loads the plugin. This stands in for that injector using the bindings RuneLite provides.
 */
public class GuiceWiringTest
{
	private static final Class<?>[] INJECTABLES = {
		BingoConfigService.class,
		InteractionTrackingService.class,
		LogService.class,
		WebhookService.class,
		DropCorrelationService.class,
		ScreenshotService.class,
		WiseOldManService.class,
		TeamIconService.class,
		XpTracker.class,
		LootEventHandler.class,
		PetChatHandler.class,
		CollectionLogHandler.class,
		ValuableDropHandler.class,
		RaidLootHandler.class,
		ManualScreenshotHandler.class,
		DeathHandler.class,
	};

	@Test
	public void everyServiceAndHandlerIsConstructable()
	{
		Injector injector = Guice.createInjector(binder -> {
			binder.bind(Client.class).toInstance(mock(Client.class));
			binder.bind(FauxBingoConfig.class).toInstance(mock(FauxBingoConfig.class));
			binder.bind(ItemManager.class).toInstance(mock(ItemManager.class));
			binder.bind(ClientThread.class).toInstance(mock(ClientThread.class));
			binder.bind(DrawManager.class).toInstance(mock(DrawManager.class));
			binder.bind(KeyManager.class).toInstance(mock(KeyManager.class));
			binder.bind(ConfigManager.class).toInstance(mock(ConfigManager.class));
			binder.bind(ScheduledExecutorService.class).toInstance(mock(ScheduledExecutorService.class));
			binder.bind(OkHttpClient.class).toInstance(new OkHttpClient());
			binder.bind(Gson.class).toInstance(new Gson());
			binder.bind(EventEnvelopeSink.class).to(LoggingEventEnvelopeSink.class);
			binder.bind(String.class)
				.annotatedWith(Names.named(FauxBingoPlugin.API_BASE_URL_KEY))
				.toInstance("http://api");
		});

		for (Class<?> type : INJECTABLES)
		{
			assertNotNull(type.getName(), injector.getInstance(type));
		}
	}

	/** Singleton scoping matters: the plugin and the handlers must share one LogService queue. */
	@Test
	public void servicesAreSingletons()
	{
		Injector injector = Guice.createInjector(binder -> {
			binder.bind(Client.class).toInstance(mock(Client.class));
			binder.bind(FauxBingoConfig.class).toInstance(mock(FauxBingoConfig.class));
			binder.bind(ScheduledExecutorService.class).toInstance(mock(ScheduledExecutorService.class));
			binder.bind(ConfigManager.class).toInstance(mock(ConfigManager.class));
			binder.bind(OkHttpClient.class).toInstance(new OkHttpClient());
			binder.bind(Gson.class).toInstance(new Gson());
			binder.bind(EventEnvelopeSink.class).to(LoggingEventEnvelopeSink.class);
			binder.bind(String.class)
				.annotatedWith(Names.named(FauxBingoPlugin.API_BASE_URL_KEY))
				.toInstance("http://api");
		});

		assertSame(injector.getInstance(LogService.class), injector.getInstance(LogService.class));
		assertSame(injector.getInstance(BingoConfigService.class), injector.getInstance(BingoConfigService.class));
		assertSame(injector.getInstance(DropCorrelationService.class), injector.getInstance(DropCorrelationService.class));
	}
}

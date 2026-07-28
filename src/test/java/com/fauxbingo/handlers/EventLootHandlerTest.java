package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.BingoConfigService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tempoross rewards come from the reward pool, not a kill, so they only ever arrive as a
 * LootReceived from the Loot Tracker. Covers the gap that made every soaked page after the
 * first collection log unlock invisible.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class EventLootHandlerTest
{
	private static final int SOAKED_PAGE_ID = 25578;
	private static final int SOAKED_PAGE_PRICE = 2815;
	private static final String TEMPOROSS_EVENT = "Reward pool (Tempoross)";

	@Mock
	private Client client;
	@Mock
	private FauxBingoConfig config;
	@Mock
	private ItemManager itemManager;
	@Mock
	private WebhookService webhookService;
	@Mock
	private LogService logService;
	@Mock
	private ScreenshotService screenshotService;
	@Mock
	private ScheduledExecutorService executor;
	@Mock
	private BingoConfigService bingoConfigService;

	private EventHandler<LootReceived> handler;

	@Before
	public void before()
	{
		LootEventHandler lootEventHandler = new LootEventHandler(client, config, bingoConfigService, null,
			itemManager, webhookService, logService, screenshotService, executor);
		handler = lootEventHandler.createEventLootHandler();

		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.minLootValue()).thenReturn(1_000_000);

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).execute(any());

		doAnswer(inv -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = inv.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

		ItemComposition page = mock(ItemComposition.class);
		when(page.getName()).thenReturn("Soaked page");
		when(itemManager.getItemComposition(SOAKED_PAGE_ID)).thenReturn(page);
		when(itemManager.getItemPrice(SOAKED_PAGE_ID)).thenReturn(SOAKED_PAGE_PRICE);
	}

	private LootReceived temporossLoot(int quantity)
	{
		return new LootReceived(TEMPOROSS_EVENT, -1, LootRecordType.EVENT,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, quantity, null)), 1, null);
	}

	private void withTile(String tileName, String tileSource)
	{
		when(bingoConfigService.getCachedConfig()).thenReturn(new BingoConfigService.BingoConfigData(
			Collections.emptyList(),
			Collections.singletonList(new BingoConfigService.BingoConfigItem(tileName, tileSource))));
	}

	@Test
	public void temporossLootIsLoggedToTheApi()
	{
		handler.handle(temporossLoot(1));

		verify(logService).log(eq("LOOT"), any());
	}

	/** The actual event tile: name only, no source. */
	@Test
	public void sourcelessTileFires()
	{
		withTile("soaked page", null);

		handler.handle(temporossLoot(1));

		verify(webhookService).sendWebhook(anyString(), contains("1 x Soaked page"), any(),
			eq("Soaked page"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}

	/** A tile written as the boss name still matches the loot tracker's "Reward pool (Tempoross)". */
	@Test
	public void tileScopedToTemporossFires()
	{
		withTile("soaked page", "Tempoross");

		handler.handle(temporossLoot(2));

		verify(webhookService).sendWebhook(anyString(), contains("2 x Soaked page"), any(),
			eq("Soaked page"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}

	@Test
	public void tileScopedToTheFullEventNameAlsoFires()
	{
		withTile("soaked page", TEMPOROSS_EVENT);

		handler.handle(temporossLoot(1));

		verify(webhookService).sendWebhook(anyString(), anyString(), any(),
			eq("Soaked page"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}

	@Test
	public void tileScopedToAnUnrelatedSourceStaysQuiet()
	{
		withTile("soaked page", "Wintertodt");

		handler.handle(temporossLoot(1));

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}

	/** NPC and player kills reach us via LootManager, taking them here too would double post. */
	@Test
	public void npcAndPlayerTypesAreIgnored()
	{
		withTile("soaked page", null);

		handler.handle(new LootReceived("Vorkath", 732, LootRecordType.NPC,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1, null)), 1, null));
		handler.handle(new LootReceived("Zezima", 126, LootRecordType.PLAYER,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1, null)), 1, null));

		verifyNoInteractions(logService);
		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}

	/** Pickpocketing fires once per steal. Far too noisy to push at the API. */
	@Test
	public void pickpocketIsIgnored()
	{
		handler.handle(new LootReceived("Master Farmer", 70, LootRecordType.PICKPOCKET,
			Collections.singletonList(new ItemStack(SOAKED_PAGE_ID, 1, null)), 1, null));

		verifyNoInteractions(logService);
	}

	@Test
	public void emptyAndNullItemsAreSafe()
	{
		handler.handle(new LootReceived(TEMPOROSS_EVENT, -1, LootRecordType.EVENT, Collections.emptyList(), 1, null));
		handler.handle(new LootReceived(TEMPOROSS_EVENT, -1, LootRecordType.EVENT, null, 1, null));

		verifyNoInteractions(logService);
		verifyNoInteractions(webhookService);
	}

	/** Value gate still applies to the generic path. */
	@Test
	public void valuableEventLootStillPostsOnValueAlone()
	{
		when(config.minLootValue()).thenReturn(1000);

		handler.handle(temporossLoot(1));

		verify(webhookService).sendWebhook(anyString(), contains(TEMPOROSS_EVENT), any(),
			eq("Soaked page"), eq(WebhookService.WebhookCategory.LOOT));
	}
}

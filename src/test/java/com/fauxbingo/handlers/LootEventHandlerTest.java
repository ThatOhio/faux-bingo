package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class LootEventHandlerTest
{
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
	private NPC npc;

	@Mock
	private Player player;

	@Mock
	private ItemComposition itemComposition;

	private LootEventHandler lootEventHandler;

	@Before
	public void before()
	{
		lootEventHandler = new LootEventHandler(client, config, itemManager, webhookService, logService, screenshotService, executor);
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.minLootValue()).thenReturn(1000000);
		when(config.sendScreenshot()).thenReturn(false);

		when(itemManager.getItemComposition(anyInt())).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Dragon bones");
		when(itemManager.getItemPrice(anyInt())).thenReturn(2500);
	}

	@Test
	public void testNpcLoot()
	{
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item = new ItemStack(536, 400, null); // 400 * 2500 = 1,000,000
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item));

		lootEventHandler.createNpcLootHandler().handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Vorkath"), isNull(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
		verify(logService).log(eq("LOOT"), any());
	}

	@Test
	public void testPlayerLoot()
	{
		when(player.getName()).thenReturn("PKedPlayer");
		ItemStack item = new ItemStack(536, 400, null);
		PlayerLootReceived event = new PlayerLootReceived(player, Arrays.asList(item));

		lootEventHandler.createPlayerLootHandler().handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("PKedPlayer"), isNull(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
		verify(logService).log(eq("LOOT"), any());
	}

	@Test
	public void testMultipleItems()
	{
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item1 = new ItemStack(536, 200, null); // 500,000
		ItemStack item2 = new ItemStack(537, 200, null); // 500,000
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item1, item2));

		lootEventHandler.createNpcLootHandler().handle(event);
// The most valuable item should now be used as the itemName bundling key
		verify(webhookService).sendWebhook(anyString(), contains("Vorkath"), isNull(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
		verify(logService).log(eq("LOOT"), any());
	}

	@Test
	public void testBelowThreshold()
	{
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item = new ItemStack(536, 1, null);
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item));

		lootEventHandler.createNpcLootHandler().handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
		verify(logService).log(eq("LOOT"), any()); // Should still log to external API
	}

	@Test
	public void testOtherBingoItem()
	{
		when(config.otherBingoItems()).thenReturn("Dragon bones");
		when(npc.getName()).thenReturn("Vorkath");
		ItemStack item = new ItemStack(536, 1, null); // Only 1 bone, way below 1M threshold
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(item));

		lootEventHandler.createNpcLootHandler().handle(event);

		// Should send bingo notification even though it's below minLootValue
		verify(webhookService).sendWebhook(anyString(), contains("1 x Dragon bones"), any(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}
}

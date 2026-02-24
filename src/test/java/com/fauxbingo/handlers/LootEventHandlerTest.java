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
import org.mockito.ArgumentCaptor;
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
		lootEventHandler = new LootEventHandler(client, config, null, null, itemManager, webhookService, logService, screenshotService, executor);
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.minLootValue()).thenReturn(1000000);

		// Run executor tasks inline
		doAnswer(invocation -> {
			Runnable r = invocation.getArgument(0);
			r.run();
			return null;
		}).when(executor).execute(any());

		// Immediately trigger webhook via screenshot callback
		doAnswer(invocation -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = invocation.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

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

		verify(webhookService).sendWebhook(anyString(), contains("Vorkath"), any(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
		verify(logService).log(eq("LOOT"), any());
	}

	@Test
	public void testPlayerLoot()
	{
		when(player.getName()).thenReturn("PKedPlayer");
		ItemStack item = new ItemStack(536, 400, null);
		PlayerLootReceived event = new PlayerLootReceived(player, Arrays.asList(item));

		lootEventHandler.createPlayerLootHandler().handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("PKedPlayer"), any(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
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
		verify(webhookService).sendWebhook(anyString(), contains("Vorkath"), any(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.LOOT));
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

	@Test
	public void testGroupedLootSummaryAggregatesDuplicateNames()
	{
		// Lower threshold so we don't depend on value math here
		when(config.minLootValue()).thenReturn(0);
		when(npc.getName()).thenReturn("GLWZ");

		// Create compositions with desired names
		ItemComposition sharkComp1 = mock(ItemComposition.class);
		when(sharkComp1.getName()).thenReturn("Shark");
		ItemComposition sharkComp2 = mock(ItemComposition.class);
		when(sharkComp2.getName()).thenReturn("Shark");
		ItemComposition teleComp = mock(ItemComposition.class);
		when(teleComp.getName()).thenReturn("Teleport to house");

		// Map specific IDs to these compositions
		when(itemManager.getItemComposition(100)).thenReturn(sharkComp1);
		when(itemManager.getItemComposition(101)).thenReturn(sharkComp2);
		when(itemManager.getItemComposition(200)).thenReturn(teleComp);

		// Prices don't matter for this test, but ensure non-zero
		when(itemManager.getItemPrice(anyInt())).thenReturn(1);

		ItemStack shark1 = new ItemStack(100, 1, null);
		ItemStack shark2 = new ItemStack(101, 2, null);
		ItemStack tele = new ItemStack(200, 8, null);
		NpcLootReceived event = new NpcLootReceived(npc, Arrays.asList(shark1, shark2, tele));

		lootEventHandler.createNpcLootHandler().handle(event);

		ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
		verify(webhookService).sendWebhook(anyString(), messageCaptor.capture(), any(), anyString(), eq(WebhookService.WebhookCategory.LOOT));
		String message = messageCaptor.getValue();

		// Expect grouped summary preserving first-seen order: Sharks combined into 3, then teleports 8
		org.junit.Assert.assertTrue("Expected grouped loot summary in message", message.contains("3 x Shark, 8 x Teleport to house"));
	}
}

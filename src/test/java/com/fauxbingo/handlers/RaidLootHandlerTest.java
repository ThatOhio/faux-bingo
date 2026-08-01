package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RaidLootHandlerTest
{
	@Mock
	private Client client;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private DropCorrelationService dropCorrelationService;

	@Mock
	private ItemManager itemManager;

	@Mock
	private Player player;

	@Mock
	private ItemComposition itemComposition;

	@Mock
	private ItemContainer itemContainer;

	private RaidLootHandler raidLootHandler;

	@Before
	public void before()
	{
		raidLootHandler = new RaidLootHandler(client, config, screenshotService, executor, itemManager, dropCorrelationService);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(player.getWorldLocation()).thenReturn(WorldPoint.fromRegion(12889, 32, 32, 0));
		when(config.includeRaidLoot()).thenReturn(true);

		doAnswer(invocation -> {
			Runnable r = invocation.getArgument(0);
			r.run();
			return null;
		}).when(executor).execute(any());

		doAnswer(invocation -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = invocation.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());
	}

	private DropSignal captureSignal()
	{
		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		return captor.getValue();
	}

	@Test
	public void testCoxLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		uniqueEvent.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.RAID_CHEST_CONTAINER, signal.getDetectionMethod());
		org.junit.Assert.assertTrue(signal.isAlwaysNotify());
		org.junit.Assert.assertEquals(Integer.valueOf(100), signal.getKillCount());
		org.junit.Assert.assertEquals("COX", signal.getVariant());
		org.junit.Assert.assertEquals(Integer.valueOf(12889), signal.getRegionId());
		String message = signal.getWebhookMessage();
		org.junit.Assert.assertTrue(message.contains("Twisted bow"));
		org.junit.Assert.assertTrue(message.contains("Kill Count: **100**"));
		org.junit.Assert.assertTrue(message.contains("1 x Twisted bow"));
	}

	@Test
	public void testTobLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Theatre of Blood count is: 50.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("TestPlayer found something special: Scythe of vitur (Uncharged)");
		raidLootHandler.onChatMessage(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(22477, 1)});
		when(itemManager.getItemComposition(22477)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Scythe of vitur (Uncharged)");

		ItemContainerChanged containerEvent = new ItemContainerChanged(612, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Scythe of vitur"));
	}

	@Test
	public void testNoUniqueAndNotValuableStillReported()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Container Changed with cheap items
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1234, 100)});
		when(itemManager.getItemComposition(1234)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Pure essence");
		when(itemManager.getItemPrice(1234)).thenReturn(2);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertFalse(signal.isAlwaysNotify());
		org.junit.Assert.assertEquals(200L, signal.getTotalValueGe().longValue());
	}

	@Test
	public void testConsolidatedNotification()
	{
		// Test multiple rare drops in one raid
		// 1. KC
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// 2. Unique 1
		ChatMessage u1 = new ChatMessage();
		u1.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		u1.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(u1);

		// 3. Unique 2 (Dust)
		ChatMessage u2 = new ChatMessage();
		u2.setType(ChatMessageType.GAMEMESSAGE);
		u2.setMessage("Dust recipients: TestPlayer");
		raidLootHandler.onChatMessage(u2);

		// 4. Container
		when(itemContainer.getItems()).thenReturn(new Item[]{
			new Item(20997, 1), // T-bow
			new Item(1234, 100), // Soul runes
			new Item(5678, 1)    // Dust (assuming it's in container)
		});

		ItemComposition tbowComp = mock(ItemComposition.class);
		when(tbowComp.getName()).thenReturn("Twisted bow");
		when(itemManager.getItemComposition(20997)).thenReturn(tbowComp);

		ItemComposition soulComp = mock(ItemComposition.class);
		when(soulComp.getName()).thenReturn("Soul runes");
		when(itemManager.getItemComposition(1234)).thenReturn(soulComp);

		ItemComposition dustComp = mock(ItemComposition.class);
		when(dustComp.getName()).thenReturn("Metamorphic dust");
		when(itemManager.getItemComposition(5678)).thenReturn(dustComp);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(containerEvent);

		// Should report ONE signal with everything folded in
		DropSignal signal = captureSignal();
		String message = signal.getWebhookMessage();
		org.junit.Assert.assertTrue(message.contains("Twisted bow"));
		org.junit.Assert.assertTrue(message.contains("Metamorphic dust"));
		org.junit.Assert.assertTrue(message.contains("100 x Soul runes"));
	}

	@Test
	public void testValuableLootNotification()
	{
		when(itemContainer.getItems()).thenReturn(new Item[]{
			new Item(1, 100), // Dragon arrow
			new Item(2, 1)    // Dexterous prayer scroll
		});

		ItemComposition arrowComp = mock(ItemComposition.class);
		when(arrowComp.getName()).thenReturn("Dragon arrow");
		when(itemManager.getItemComposition(1)).thenReturn(arrowComp);
		when(itemManager.getItemPrice(1)).thenReturn(2000);

		ItemComposition scrollComp = mock(ItemComposition.class);
		when(scrollComp.getName()).thenReturn("Dexterous prayer scroll");
		when(itemManager.getItemComposition(2)).thenReturn(scrollComp);
		when(itemManager.getItemPrice(2)).thenReturn(1200000);

		// KC Message to set context
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.onChatMessage(kcEvent);

		// Container Changed event
		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(1_400_000L, signal.getTotalValueGe().longValue());
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Total value: 1,400,000 gp"));
	}

	@Test
	public void testToaTeammateUniqueIgnored()
	{
		// ToA unique drop message for teammate
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("Loot recipient: Teammate - Tumeken's shadow (uncharged)");
		raidLootHandler.onChatMessage(uniqueEvent);

		// Container change for ToA
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1, 1)}); // Just some loot
		when(itemManager.getItemComposition(1)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Coins");
		when(itemManager.getItemPrice(1)).thenReturn(1);

		ItemContainerChanged event = new ItemContainerChanged(801, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertFalse(signal.isAlwaysNotify());
		org.junit.Assert.assertFalse(signal.getWebhookMessage().contains("received a rare drop"));
	}

	@Test
	public void testMissingKcMessageStillProcesses()
	{
		// NO KC message received

		// Container Changed event
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		// Set it as a rare drop via chat anyway (unlikely if KC missed but possible)
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		uniqueEvent.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.onChatMessage(uniqueEvent);

		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.onItemContainerChanged(event);

		// Should still process and use default raid name
		DropSignal signal = captureSignal();
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Chambers of Xeric"));
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Twisted bow"));
	}
}

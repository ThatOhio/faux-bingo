package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class RaidLootHandlerTest
{
	@Mock
	private Client client;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private WebhookService webhookService;

	@Mock
	private LogService logService;

	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private ItemManager itemManager;

	@Mock
	private Player player;

	@Mock
	private Widget rewardWidget;

	@Mock
	private Widget itemWidget;

	@Mock
	private ItemComposition itemComposition;

	@Mock
	private ItemContainer itemContainer;

	private RaidLootHandler raidLootHandler;

	@Before
	public void before()
	{
		raidLootHandler = new RaidLootHandler(client, config, webhookService, logService, screenshotService, executor, itemManager);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.includeRaidLoot()).thenReturn(true);
		when(config.sendScreenshot()).thenReturn(false);
	}

	@Test
	public void testCoxLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		uniqueEvent.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.createChatHandler().handle(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(20997, 1)});
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			argThat(s -> s.contains("Twisted bow") && s.contains("Kill Count: **100**") && s.contains("1 x Twisted bow")), 
			isNull(), 
			eq("Twisted bow"), 
			eq(WebhookService.WebhookCategory.RAID_LOOT)
		);
		verify(logService, atLeastOnce()).log(eq("RAID_LOOT"), any());
	}

	@Test
	public void testTobLootSequence()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Theatre of Blood count is: 50.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Unique Message
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("TestPlayer found something special: Scythe of vitur (Uncharged)");
		raidLootHandler.createChatHandler().handle(uniqueEvent);

		// 3. Container Changed
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(22477, 1)});
		when(itemManager.getItemComposition(22477)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Scythe of vitur (Uncharged)");

		ItemContainerChanged containerEvent = new ItemContainerChanged(612, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(anyString(), contains("Scythe of vitur"), isNull(), eq("Scythe of vitur (Uncharged)"), eq(WebhookService.WebhookCategory.RAID_LOOT));
	}

	@Test
	public void testNoUniqueAndNotValuable()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Container Changed with cheap items
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1234, 100)});
		when(itemManager.getItemComposition(1234)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Pure essence");
		when(itemManager.getItemPrice(1234)).thenReturn(2);
		when(config.minLootValue()).thenReturn(1000000);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
		// Should still log it
		verify(logService).log(eq("RAID_LOOT"), any());
	}

	@Test
	public void testCoxBingoItem()
	{
		// Config
		when(config.coxBingoItems()).thenReturn("Dynamite, Prayer scroll");
		
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1234, 100)});
		when(itemManager.getItemComposition(1234)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Dynamite");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Container Changed
		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			contains("100 x Dynamite"), 
			isNull(), 
			eq("Dynamite"), 
			eq(WebhookService.WebhookCategory.BINGO_LOOT)
		);
		verify(logService).log(eq("BINGO_LOOT"), any());
	}

	@Test
	public void testTobBingoItem()
	{
		// Config
		when(config.tobBingoItems()).thenReturn("Vial of blood");
		
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(22444, 50)});
		when(itemManager.getItemComposition(22444)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Vial of blood");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Theatre of Blood count is: 50.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Container Changed
		ItemContainerChanged containerEvent = new ItemContainerChanged(612, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			contains("50 x Vial of blood"), 
			isNull(), 
			eq("Vial of blood"), 
			eq(WebhookService.WebhookCategory.BINGO_LOOT)
		);
	}

	@Test
	public void testToaBingoItem()
	{
		// Config
		when(config.toaBingoItems()).thenReturn("Lily of the sands");
		
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(27272, 25)});
		when(itemManager.getItemComposition(27272)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Lily of the sands");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your Tombs of Amascut: Normal Mode completion count is 10.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Container Changed
		ItemContainerChanged containerEvent = new ItemContainerChanged(801, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			argThat(s -> s.contains("25 x Lily of the sands") && s.contains("Tombs of Amascut") && s.contains("Kill Count: **10**")), 
			isNull(), 
			eq("Lily of the sands"), 
			eq(WebhookService.WebhookCategory.BINGO_LOOT)
		);
	}

	@Test
	public void testConsolidatedNotification()
	{
		// Test multiple rare drops and bingo items in one raid
		when(config.coxBingoItems()).thenReturn("Soul rune");
		
		// 1. KC
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Unique 1
		ChatMessage u1 = new ChatMessage();
		u1.setType(ChatMessageType.FRIENDSCHATNOTIFICATION);
		u1.setMessage("TestPlayer - Twisted bow");
		raidLootHandler.createChatHandler().handle(u1);

		// 3. Unique 2 (Dust)
		ChatMessage u2 = new ChatMessage();
		u2.setType(ChatMessageType.GAMEMESSAGE);
		u2.setMessage("Dust recipients: TestPlayer");
		raidLootHandler.createChatHandler().handle(u2);

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
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		// Should send ONE webhook with everything
		verify(webhookService, times(1)).sendWebhook(anyString(), argThat(s -> 
			s.contains("Twisted bow") && s.contains("Metamorphic dust") && s.contains("100 x Soul runes")
		), any(), anyString(), eq(WebhookService.WebhookCategory.RAID_LOOT));
	}

	@Test
	public void testOtherBingoItem()
	{
		// Config has "Dragon bones" in Other Items
		when(config.otherBingoItems()).thenReturn("Dragon bones");
		
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(536, 50)});
		when(itemManager.getItemComposition(536)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Dragon bones");

		// Set raid context
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		ItemContainerChanged containerEvent = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(containerEvent);

		verify(webhookService).sendWebhook(anyString(), contains("50 x Dragon bones"), any(), eq("Dragon bones"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}

	@Test
	public void testValuableLootNotification()
	{
		when(config.minLootValue()).thenReturn(1000000);

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
		raidLootHandler.createChatHandler().handle(kcEvent);

		// Container Changed event
		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(event);

		// Should send a webhook because total value is above threshold
		verify(webhookService).sendWebhook(
			anyString(),
			contains("Total value: 1,400,000 gp"),
			isNull(),
			any(),
			any()
		);
	}

	@Test
	public void testToaTeammateUniqueIgnored()
	{
		// ToA unique drop message for teammate
		ChatMessage uniqueEvent = new ChatMessage();
		uniqueEvent.setType(ChatMessageType.GAMEMESSAGE);
		uniqueEvent.setMessage("Loot recipient: Teammate - Tumeken's shadow (uncharged)");
		raidLootHandler.createChatHandler().handle(uniqueEvent);

		// Container change for ToA
		when(itemContainer.getItems()).thenReturn(new Item[]{new Item(1, 1)}); // Just some loot
		when(itemManager.getItemComposition(1)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Coins");
		when(itemManager.getItemPrice(1)).thenReturn(1);
		when(config.minLootValue()).thenReturn(1000000);

		ItemContainerChanged event = new ItemContainerChanged(801, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), contains("received a rare drop"), any(), anyString(), any());
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
		raidLootHandler.createChatHandler().handle(uniqueEvent);

		ItemContainerChanged event = new ItemContainerChanged(581, itemContainer);
		raidLootHandler.createItemContainerHandler().handle(event);

		// Should still process and use default raid name
		verify(webhookService).sendWebhook(
			anyString(), 
			contains("Chambers of Xeric"), 
			any(), 
			eq("Twisted bow"), 
			any()
		);
	}
}

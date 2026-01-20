package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;
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
	private DrawManager drawManager;

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

	private RaidLootHandler raidLootHandler;

	@Before
	public void before()
	{
		raidLootHandler = new RaidLootHandler(client, config, webhookService, logService, drawManager, executor, itemManager);
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

		// 3. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.RAIDS_REWARDS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			argThat(s -> s.contains("Twisted bow") && s.contains("Kill Count: **100**")), 
			isNull(), 
			eq("Twisted bow"), 
			eq(WebhookService.WebhookCategory.RAID_LOOT)
		);
		verify(logService).log(eq("RAID_LOOT"), any());
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

		// 3. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.TOB_CHESTS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService).sendWebhook(anyString(), contains("Scythe of vitur"), isNull(), eq("Scythe of vitur (Uncharged)"), eq(WebhookService.WebhookCategory.RAID_LOOT));
	}

	@Test
	public void testNoUnique()
	{
		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Widget Loaded (no unique message received)
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.RAIDS_REWARDS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}

	@Test
	public void testCoxBingoItem()
	{
		// Config
		when(config.coxBingoItems()).thenReturn("Dynamite, Prayer scroll");
		
		// Mock widget and items
		when(client.getWidget(eq(InterfaceID.RAIDS_REWARDS), anyInt())).thenReturn(rewardWidget);
		when(rewardWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
		when(itemWidget.getItemId()).thenReturn(1234);
		when(itemWidget.getItemQuantity()).thenReturn(100);
		
		when(itemManager.getItemComposition(1234)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Dynamite");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Chambers of Xeric count is: 100.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.RAIDS_REWARDS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			contains("100 x Dynamite"), 
			isNull(), 
			eq("Dynamite"), 
			eq(WebhookService.WebhookCategory.RAID_LOOT)
		);
		verify(logService).log(eq("BINGO_LOOT"), any());
	}

	@Test
	public void testTobBingoItem()
	{
		// Config
		when(config.tobBingoItems()).thenReturn("Vial of blood");
		
		// Mock widget and items
		when(client.getWidget(eq(InterfaceID.TOB_CHESTS), anyInt())).thenReturn(rewardWidget);
		when(rewardWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
		when(itemWidget.getItemId()).thenReturn(22444);
		when(itemWidget.getItemQuantity()).thenReturn(50);
		
		when(itemManager.getItemComposition(22444)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Vial of blood");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your completed Theatre of Blood count is: 50.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.TOB_CHESTS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			contains("50 x Vial of blood"), 
			isNull(), 
			eq("Vial of blood"), 
			eq(WebhookService.WebhookCategory.RAID_LOOT)
		);
	}

	@Test
	public void testToaBingoItem()
	{
		// Config
		when(config.toaBingoItems()).thenReturn("Lily of the sands");
		
		// Mock widget and items
		when(client.getWidget(eq(775), anyInt())).thenReturn(rewardWidget);
		when(rewardWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
		when(itemWidget.getItemId()).thenReturn(27272);
		when(itemWidget.getItemQuantity()).thenReturn(25);
		
		when(itemManager.getItemComposition(27272)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Lily of the sands");

		// 1. KC Message
		ChatMessage kcEvent = new ChatMessage();
		kcEvent.setType(ChatMessageType.GAMEMESSAGE);
		kcEvent.setMessage("Your Tombs of Amascut: Normal Mode completion count is 10.");
		raidLootHandler.createChatHandler().handle(kcEvent);

		// 2. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(775);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		verify(webhookService).sendWebhook(
			anyString(), 
			argThat(s -> s.contains("25 x Lily of the sands") && s.contains("Tombs of Amascut") && s.contains("Kill Count: **10**")), 
			isNull(), 
			eq("Lily of the sands"), 
			eq(WebhookService.WebhookCategory.RAID_LOOT)
		);
	}

	@Test
	public void testRareDropAndBingoItemSameTime()
	{
		// Config
		when(config.coxBingoItems()).thenReturn("Twisted bow");
		
		// Mock widget and items (T-bow is the unique, and we also have it in bingo list)
		when(client.getWidget(eq(InterfaceID.RAIDS_REWARDS), anyInt())).thenReturn(rewardWidget);
		when(rewardWidget.getDynamicChildren()).thenReturn(new Widget[]{itemWidget});
		when(itemWidget.getItemId()).thenReturn(20997);
		
		when(itemManager.getItemComposition(20997)).thenReturn(itemComposition);
		when(itemComposition.getName()).thenReturn("Twisted bow");

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

		// 3. Widget Loaded
		WidgetLoaded widgetEvent = new WidgetLoaded();
		widgetEvent.setGroupId(InterfaceID.RAIDS_REWARDS);
		raidLootHandler.createWidgetHandler().handle(widgetEvent);

		// Should only send ONE webhook for the rare drop, not a second one for bingo
		verify(webhookService, times(1)).sendWebhook(
			anyString(), 
			contains("just received a rare drop"), 
			any(), 
			eq("Twisted bow"), 
			any()
		);
		verify(webhookService, never()).sendWebhook(anyString(), contains("just received a BINGO item"), any(), anyString(), any());
	}
}

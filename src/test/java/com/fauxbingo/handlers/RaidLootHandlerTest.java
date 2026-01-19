package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
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
	private Player player;

	private RaidLootHandler raidLootHandler;

	@Before
	public void before()
	{
		raidLootHandler = new RaidLootHandler(client, config, webhookService, logService, drawManager, executor);
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
}

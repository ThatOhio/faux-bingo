package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.ui.DrawManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class CollectionLogHandlerTest
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

	private CollectionLogHandler collectionLogHandler;

	@Before
	public void before()
	{
		collectionLogHandler = new CollectionLogHandler(client, config, webhookService, logService, drawManager, executor);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.includeCollectionLog()).thenReturn(true);
		when(config.sendScreenshot()).thenReturn(false);
	}

	@Test
	public void testChatMessageCollectionLog()
	{
		when(client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION)).thenReturn(1);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("New item added to your collection log: Abyssal whip");

		collectionLogHandler.createChatHandler().handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Abyssal whip"), isNull(), eq("Abyssal whip"), eq(WebhookService.WebhookCategory.COLLECTION_LOG));
		verify(logService).log(eq("COLLECTION_LOG"), any());
	}

	@Test
	public void testChatMessageCollectionLogDisabled()
	{
		when(client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION)).thenReturn(0);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("New item added to your collection log: Abyssal whip");

		collectionLogHandler.createChatHandler().handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}

	@Test
	public void testScriptCollectionLog()
	{
		// NOTIFICATION_START
		ScriptPreFired startEvent = new ScriptPreFired(ScriptID.NOTIFICATION_START);
		collectionLogHandler.createScriptHandler().handle(startEvent);

		// NOTIFICATION_DELAY
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT)).thenReturn("Collection log");
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT)).thenReturn("New item: Abyssal whip");
		ScriptPreFired delayEvent = new ScriptPreFired(ScriptID.NOTIFICATION_DELAY);
		collectionLogHandler.createScriptHandler().handle(delayEvent);

		verify(webhookService).sendWebhook(anyString(), contains("Abyssal whip"), isNull(), eq("Abyssal whip"), eq(WebhookService.WebhookCategory.COLLECTION_LOG));
	}

	@Test
	public void testScriptCollectionLogWrongTopText()
	{
		// NOTIFICATION_START
		ScriptPreFired startEvent = new ScriptPreFired(ScriptID.NOTIFICATION_START);
		collectionLogHandler.createScriptHandler().handle(startEvent);

		// NOTIFICATION_DELAY
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT)).thenReturn("Quest complete");
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT)).thenReturn("New item: Abyssal whip");
		ScriptPreFired delayEvent = new ScriptPreFired(ScriptID.NOTIFICATION_DELAY);
		collectionLogHandler.createScriptHandler().handle(delayEvent);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}
}

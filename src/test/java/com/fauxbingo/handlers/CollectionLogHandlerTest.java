package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class CollectionLogHandlerTest
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
	private Player player;

	private CollectionLogHandler collectionLogHandler;

	@Before
	public void before()
	{
		collectionLogHandler = new CollectionLogHandler(client, config, screenshotService, executor, dropCorrelationService);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.includeCollectionLog()).thenReturn(true);

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
	public void testChatMessageCollectionLog()
	{
		when(client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION)).thenReturn(1);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("New item added to your collection log: Abyssal whip");

		collectionLogHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.CHAT_COLLECTION_LOG, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("Abyssal whip", signal.getItems().get(0).getName());
		org.junit.Assert.assertTrue(signal.isAlwaysNotify());
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Abyssal whip"));
	}

	@Test
	public void testChatMessageCollectionLogDisabled()
	{
		when(client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION)).thenReturn(0);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("New item added to your collection log: Abyssal whip");

		collectionLogHandler.onChatMessage(event);

		verifyNoInteractions(dropCorrelationService);
	}

	@Test
	public void testScriptCollectionLog()
	{
		// NOTIFICATION_START
		ScriptPreFired startEvent = new ScriptPreFired(ScriptID.NOTIFICATION_START);
		collectionLogHandler.onScriptPreFired(startEvent);

		// NOTIFICATION_DELAY
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT)).thenReturn("Collection log");
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT)).thenReturn("New item: Abyssal whip");
		ScriptPreFired delayEvent = new ScriptPreFired(ScriptID.NOTIFICATION_DELAY);
		collectionLogHandler.onScriptPreFired(delayEvent);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.NOTIFICATION_COLLECTION_LOG, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("Abyssal whip", signal.getItems().get(0).getName());
	}

	@Test
	public void testScriptCollectionLogWrongTopText()
	{
		// NOTIFICATION_START
		ScriptPreFired startEvent = new ScriptPreFired(ScriptID.NOTIFICATION_START);
		collectionLogHandler.onScriptPreFired(startEvent);

		// NOTIFICATION_DELAY
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT)).thenReturn("Quest complete");
		when(client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT)).thenReturn("New item: Abyssal whip");
		ScriptPreFired delayEvent = new ScriptPreFired(ScriptID.NOTIFICATION_DELAY);
		collectionLogHandler.onScriptPreFired(delayEvent);

		verifyNoInteractions(dropCorrelationService);
	}
}

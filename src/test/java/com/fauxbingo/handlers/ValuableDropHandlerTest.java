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
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class ValuableDropHandlerTest
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

	private ValuableDropHandler valuableDropHandler;

	@Before
	public void before()
	{
		valuableDropHandler = new ValuableDropHandler(client, config, screenshotService, executor, dropCorrelationService);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.includeValuableDrops()).thenReturn(true);

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
	public void testValuableDrop()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.CHAT_VALUABLE_DROP, signal.getDetectionMethod());
		org.junit.Assert.assertEquals("Dragon metal sheet", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(1_155_320L, signal.getTotalValueGe().longValue());
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Dragon metal sheet"));
	}

	@Test
	public void testValuableDropWithTags()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("<col=ef1020>Valuable drop: Dragon metal sheet (1,155,320 coins)</col>");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("Dragon metal sheet"));
	}

	/** No local value gating here anymore, that decision moved to DropCorrelationService. */
	@Test
	public void testBelowThresholdStillReported()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon bones (2,500 coins)");

		valuableDropHandler.onChatMessage(event);

		verify(dropCorrelationService).report(any());
	}

	@Test
	public void testDisabled()
	{
		when(config.includeValuableDrops()).thenReturn(false);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.onChatMessage(event);

		verifyNoInteractions(dropCorrelationService);
	}

	@Test
	public void testScreenshotRequested()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.onChatMessage(event);

		verify(screenshotService).requestScreenshot(any());
	}

	@Test
	public void testValuableDropWithQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 30 x Chaos rune (1,680 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		// The bundling key (cleaned) should be "Chaos rune", quantity parsed out of the chat text
		org.junit.Assert.assertEquals("Chaos rune", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(30, signal.getItems().get(0).getQuantity());
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("30 x Chaos rune"));
	}

	@Test
	public void testValuableDropWithLargeQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 1,000 x Chaos rune (56,000 coins)");

		valuableDropHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Chaos rune", signal.getItems().get(0).getName());
		org.junit.Assert.assertEquals(1000, signal.getItems().get(0).getQuantity());
		org.junit.Assert.assertTrue(signal.getWebhookMessage().contains("1,000 x Chaos rune"));
	}
}

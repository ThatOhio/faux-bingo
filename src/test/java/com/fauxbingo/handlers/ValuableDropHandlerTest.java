package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
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

@RunWith(MockitoJUnitRunner.class)
public class ValuableDropHandlerTest
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
	private Player player;

	private ValuableDropHandler valuableDropHandler;

	@Before
	public void before()
	{
		valuableDropHandler = new ValuableDropHandler(client, config, webhookService, logService, screenshotService, executor);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.includeValuableDrops()).thenReturn(true);
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
	}

	@Test
	public void testValuableDrop()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Dragon metal sheet"), any(), eq("Dragon metal sheet"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
		verify(logService).log(eq("VALUABLE_DROP"), any());
	}

	@Test
	public void testValuableDropWithTags()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("<col=ef1020>Valuable drop: Dragon metal sheet (1,155,320 coins)</col>");

		valuableDropHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Dragon metal sheet"), any(), eq("Dragon metal sheet"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
	}

	@Test
	public void testBelowThreshold()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon bones (2,500 coins)");

		valuableDropHandler.handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
		verify(logService).log(eq("VALUABLE_DROP"), any());
	}

	@Test
	public void testDisabled()
	{
		when(config.includeValuableDrops()).thenReturn(false);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}

	@Test
	public void testScreenshotRequested()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.handle(event);

		verify(screenshotService).requestScreenshot(any());
	}

	@Test
	public void testValuableDropWithQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 30 x Chaos rune (1,680 coins)");

		when(config.minLootValue()).thenReturn(1000);

		valuableDropHandler.handle(event);

		// The bundling key (cleaned) should be "Chaos rune"
		verify(webhookService).sendWebhook(anyString(), contains("30 x Chaos rune"), any(), eq("Chaos rune"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
	}

	@Test
	public void testValuableDropWithLargeQuantity()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 1,000 x Chaos rune (56,000 coins)");

		when(config.minLootValue()).thenReturn(1000);

		valuableDropHandler.handle(event);

		// The bundling key (cleaned) should be "Chaos rune"
		verify(webhookService).sendWebhook(anyString(), contains("1,000 x Chaos rune"), any(), eq("Chaos rune"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
	}

	@Test
	public void testOtherBingoItem()
	{
		when(config.otherBingoItems()).thenReturn("Soul rune");
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: 100 x Soul rune (15,000 coins)");

		// minLootValue is 1M, so 15k is below
		valuableDropHandler.handle(event);

		// Should NOT send valuable drop notification
		verify(webhookService, never()).sendWebhook(anyString(), contains("valuable drop"), any(), anyString(), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
		
		// Should send bingo notification
		verify(webhookService).sendWebhook(anyString(), contains("100 x Soul rune"), any(), eq("Soul rune"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}
}

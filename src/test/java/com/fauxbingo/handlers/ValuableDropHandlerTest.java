package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.ui.DrawManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
	private DrawManager drawManager;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private Player player;

	private ValuableDropHandler valuableDropHandler;

	@Before
	public void before()
	{
		valuableDropHandler = new ValuableDropHandler(client, config, webhookService, logService, drawManager, executor);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.includeValuableDrops()).thenReturn(true);
		when(config.valuableDropThreshold()).thenReturn(1000000);
		when(config.sendScreenshot()).thenReturn(false);
	}

	@Test
	public void testValuableDrop()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Dragon metal sheet"), isNull(), eq("Dragon metal sheet"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
		verify(logService).log(eq("VALUABLE_DROP"), any());
	}

	@Test
	public void testValuableDropWithTags()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("<col=ef1020>Valuable drop: Dragon metal sheet (1,155,320 coins)</col>");

		valuableDropHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("Dragon metal sheet"), isNull(), eq("Dragon metal sheet"), eq(WebhookService.WebhookCategory.VALUABLE_DROP));
	}

	@Test
	public void testBelowThreshold()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon bones (2,500 coins)");

		valuableDropHandler.handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
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
		when(config.sendScreenshot()).thenReturn(true);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("Valuable drop: Dragon metal sheet (1,155,320 coins)");

		valuableDropHandler.handle(event);

		verify(drawManager).requestNextFrameListener(any());
		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), anyString(), any());
	}
}

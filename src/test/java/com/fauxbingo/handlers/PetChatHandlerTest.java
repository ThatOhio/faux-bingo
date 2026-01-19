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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class PetChatHandlerTest
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

	private PetChatHandler petChatHandler;

	@Before
	public void before()
	{
		petChatHandler = new PetChatHandler(client, config, webhookService, logService, drawManager, executor);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("TestPlayer");
		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.includePets()).thenReturn(true);
		when(config.sendScreenshot()).thenReturn(false);
	}

	@Test
	public void testPetFollowMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you're being followed.");

		petChatHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), contains("TestPlayer"), isNull(), eq("Pet"), eq(WebhookService.WebhookCategory.PET));
		verify(logService).log(eq("PET"), any());
	}

	@Test
	public void testPetInventoryMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You feel something weird sneaking into your backpack.");

		petChatHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), anyString(), any(), any(), eq(WebhookService.WebhookCategory.PET));
	}

	@Test
	public void testPetDuplicateMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you would have been followed.");

		petChatHandler.handle(event);

		verify(webhookService).sendWebhook(anyString(), anyString(), any(), any(), eq(WebhookService.WebhookCategory.PET));
	}

	@Test
	public void testNotPetMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You catch a shrimp.");

		petChatHandler.handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), any(), any());
	}

	@Test
	public void testDisabled()
	{
		when(config.includePets()).thenReturn(false);
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you're being followed.");

		petChatHandler.handle(event);

		verify(webhookService, never()).sendWebhook(anyString(), anyString(), any(), any(), any());
	}
}

package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import com.google.common.collect.ImmutableList;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.ui.DrawManager;

/**
 * Handles chat message events to detect pet drops.
 */
@Slf4j
public class PetChatHandler implements EventHandler<ChatMessage>
{
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of(
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed"
	);

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final LogService logService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	public PetChatHandler(
		Client client,
		FauxBingoConfig config,
		WebhookService webhookService,
		LogService logService,
		DrawManager drawManager,
		ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.webhookService = webhookService;
		this.logService = logService;
		this.drawManager = drawManager;
		this.executor = executor;
	}

	@Override
	public void handle(ChatMessage event)
	{
		if (!config.includePets())
		{
			return;
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();

		// Check if message indicates a pet drop
		if (PET_MESSAGES.stream().anyMatch(message::contains))
		{
			handlePetDrop();
		}
	}

	@Override
	public Class<ChatMessage> getEventType()
	{
		return ChatMessage.class;
	}

	private void handlePetDrop()
	{
		log.info("Pet drop detected");

		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String webhookMessage = String.format("**%s** just received a new pet!", playerName);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(webhookMessage);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), webhookMessage, null);
		}

		logPetDrop();
	}

	private void logPetDrop()
	{
		LootRecord lootRecord = LootRecord.builder()
			.source("Pet")
			.items(Collections.singletonList(LootRecord.LootItem.builder()
				.name("Pet")
				.quantity(1)
				.build()))
			.build();

		logService.log("PET", lootRecord);
	}

	private void takeScreenshotAndSend(String message)
	{
		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image);
				}
				catch (Exception e)
				{
					log.error("Error sending webhook with screenshot for pet drop", e);
				}
			});
		});
	}
}

package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.ui.DrawManager;

/**
 * Handles valuable drop notifications from chat messages.
 * Detects when the game announces a valuable drop above the configured threshold.
 */
@Slf4j
public class ValuableDropHandler implements EventHandler<ChatMessage>
{
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(
		".*Valuable drop: ([^<>]+?\\(((?:\\d+,?)+) coins\\))(?:</col>)?"
	);

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final LogService logService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	public ValuableDropHandler(
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
		if (!config.includeValuableDrops())
		{
			return;
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String chatMessage = event.getMessage();
		Matcher matcher = VALUABLE_DROP_PATTERN.matcher(chatMessage);
		
		if (matcher.matches())
		{
			long valuableDropValue = Long.parseLong(matcher.group(2).replaceAll(",", ""));
			
			if (valuableDropValue >= config.valuableDropThreshold())
			{
				String[] valuableDrop = matcher.group(1).split(" \\(");
				String valuableDropName = (String) Array.get(valuableDrop, 0);
				String valuableDropValueString = matcher.group(2);
				
				sendValuableDropNotification(valuableDropName, valuableDropValueString);
			}
		}
	}

	@Override
	public Class<ChatMessage> getEventType()
	{
		return ChatMessage.class;
	}

	private void sendValuableDropNotification(String itemName, String itemValue)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** just received a valuable drop: **%s**!\nApprox Value: **%s coins**", 
			playerName, itemName, itemValue);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message, itemName);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message, null, itemName, WebhookService.WebhookCategory.VALUABLE_DROP);
		}

		logValuableDrop(itemName, itemValue);
	}

	private void logValuableDrop(String itemName, String itemValue)
	{
		long value = Long.parseLong(itemValue.replaceAll(",", ""));

		LootRecord lootRecord = LootRecord.builder()
			.source("Valuable Drop")
			.items(Collections.singletonList(LootRecord.LootItem.builder()
				.name(itemName)
				.quantity(1)
				.price((int) value) // might overflow if > 2B, but item prices are usually ints
				.build()))
			.totalValue(value)
			.build();

		logService.log("VALUABLE_DROP", lootRecord);
	}

	private void takeScreenshotAndSend(String message, String itemName)
	{
		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image, itemName, WebhookService.WebhookCategory.VALUABLE_DROP);
				}
				catch (Exception e)
				{
					log.error("Error sending webhook with screenshot for valuable drop", e);
				}
			});
		});
	}
}

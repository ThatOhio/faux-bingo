package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import com.fauxbingo.util.LootMatcher;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
			String[] valuableDrop = matcher.group(1).split(" \\(");
			String valuableDropName = (String) Array.get(valuableDrop, 0);
			String valuableDropValueString = matcher.group(2);
			
			if (valuableDropValue >= config.valuableDropThreshold())
			{
				sendValuableDropNotification(valuableDropName, valuableDropValueString);
			}

			checkOtherBingoItems(valuableDropName);
		}
	}

	private void checkOtherBingoItems(String itemNameWithQuantity)
	{
		String otherItemsConfig = config.otherBingoItems();
		if (otherItemsConfig == null || otherItemsConfig.isEmpty())
		{
			return;
		}

		List<String> otherBingoItems = Arrays.stream(otherItemsConfig.split("[\n,]"))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());

		String itemName = cleanItemName(itemNameWithQuantity);
		if (LootMatcher.matchesAny(itemName, otherBingoItems))
		{
			int quantity = 1;
			Pattern quantityPattern = Pattern.compile("^([0-9,]+) x ");
			Matcher quantityMatcher = quantityPattern.matcher(itemNameWithQuantity);
			if (quantityMatcher.find())
			{
				quantity = Integer.parseInt(quantityMatcher.group(1).replaceAll(",", ""));
			}

			sendBingoNotification(itemName, quantity);
		}
	}

	private void sendBingoNotification(String itemName, int quantity)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** just received a special item: **%d x %s**!",
			playerName, quantity, itemName);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message, itemName, WebhookService.WebhookCategory.BINGO_LOOT);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message, null, itemName, WebhookService.WebhookCategory.BINGO_LOOT);
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

		String bundlingKey = cleanItemName(itemName);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message, bundlingKey, WebhookService.WebhookCategory.VALUABLE_DROP);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message, null, bundlingKey, WebhookService.WebhookCategory.VALUABLE_DROP);
		}

		logValuableDrop(itemName, itemValue);
	}

	private String cleanItemName(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		// Strip quantity prefix like "30 x " or "1,000 x " to help with bundling across different handlers
		return itemName.replaceAll("^[0-9,]+ x ", "");
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

	private void takeScreenshotAndSend(String message, String itemName, WebhookService.WebhookCategory category)
	{
		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image, itemName, category);
				}
				catch (Exception e)
				{
					log.error("Error sending webhook with screenshot for {}", category, e);
				}
			});
		});
	}
}

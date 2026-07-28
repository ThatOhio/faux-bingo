package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;

/**
 * Handles valuable drop notifications from chat messages.
 * Detects when the game announces a valuable drop. Logs all; webhook only when >= minLootValue.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ValuableDropHandler
{
	private static final Pattern VALUABLE_DROP_PATTERN = Pattern.compile(
		".*Valuable drop: ([^<>]+?\\(((?:\\d+,?)+) coins\\))(?:</col>)?"
	);

	/** The chat line embeds quantity in the name, as in "30 x Dragon bones". */
	private static final Pattern QUANTITY_PREFIX = Pattern.compile("^([0-9,]+) x ");

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final LogService logService;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;

	@Subscribe
	public void onChatMessage(ChatMessage event)
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
			String valuableDropName = matcher.group(1).split(" \\(")[0];
			String valuableDropValueString = matcher.group(2);

			logValuableDrop(valuableDropName, valuableDropValue);
			if (valuableDropValue >= config.minLootValue())
			{
				sendValuableDropNotification(valuableDropName, valuableDropValueString);
			}
		}
	}

	private void sendValuableDropNotification(String itemName, String itemValue)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** just received a valuable drop: **%s**!\nApprox Value: **%s coins**", 
			playerName, itemName, itemValue);

		String bundlingKey = cleanItemName(itemName);

		takeScreenshotAndSend(message, bundlingKey, WebhookService.WebhookCategory.VALUABLE_DROP);
	}

	private String cleanItemName(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		// Strip quantity prefix like "30 x " or "1,000 x " to help with bundling across different handlers
		return QUANTITY_PREFIX.matcher(itemName).replaceFirst("");
	}

	/** The chat text is the only source for quantity, so parse it out rather than assuming 1. */
	private static int parseQuantity(String itemNameWithQuantity)
	{
		Matcher matcher = QUANTITY_PREFIX.matcher(itemNameWithQuantity);
		if (!matcher.find())
		{
			return 1;
		}
		try
		{
			return Math.max(1, Integer.parseInt(matcher.group(1).replaceAll(",", "")));
		}
		catch (NumberFormatException e)
		{
			return 1;
		}
	}

	private void logValuableDrop(String itemNameWithQuantity, long value)
	{
		int quantity = parseQuantity(itemNameWithQuantity);

		LootRecord lootRecord = LootRecord.builder()
			.source("Valuable Drop")
			.items(Collections.singletonList(LootRecord.LootItem.builder()
				.name(cleanItemName(itemNameWithQuantity))
				.quantity(quantity)
				.price((int) (value / quantity)) // might overflow if > 2B, but item prices are usually ints
				.build()))
			.totalValue(value)
			.build();

		logService.log("VALUABLE_DROP", lootRecord);
	}

	private void takeScreenshotAndSend(String message, String itemName, WebhookService.WebhookCategory category)
	{
		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			try
			{
				webhookService.sendWebhook(config.webhookUrl(), message, image, itemName, category);
			}
			catch (Exception e)
			{
				log.error("Error sending webhook with screenshot for {}", category, e);
			}
		}));
	}
}

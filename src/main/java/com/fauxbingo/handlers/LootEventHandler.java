package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import com.fauxbingo.util.LootMatcher;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Handles loot-related events from NPCs and players.
 * Calculates total loot value and triggers webhook notifications when threshold is met.
 */
@Slf4j
public class LootEventHandler
{
	private final Client client;
	private final FauxBingoConfig config;
	private final ItemManager itemManager;
	private final WebhookService webhookService;
	private final LogService logService;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;

	public LootEventHandler(
		Client client,
		FauxBingoConfig config,
		ItemManager itemManager,
		WebhookService webhookService,
		LogService logService,
		ScreenshotService screenshotService,
		ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		this.webhookService = webhookService;
		this.logService = logService;
		this.screenshotService = screenshotService;
		this.executor = executor;
	}

	public EventHandler<NpcLootReceived> createNpcLootHandler()
	{
		return new EventHandler<NpcLootReceived>()
		{
			@Override
			public void handle(NpcLootReceived event)
			{
				processLoot(event.getNpc().getName(), event.getItems());
			}

			@Override
			public Class<NpcLootReceived> getEventType()
			{
				return NpcLootReceived.class;
			}
		};
	}

	public EventHandler<PlayerLootReceived> createPlayerLootHandler()
	{
		return new EventHandler<PlayerLootReceived>()
		{
			@Override
			public void handle(PlayerLootReceived event)
			{
				processLoot(event.getPlayer().getName(), event.getItems());
			}

			@Override
			public Class<PlayerLootReceived> getEventType()
			{
				return PlayerLootReceived.class;
			}
		};
	}

	private void processLoot(String source, Collection<ItemStack> items)
	{
		long totalValue = 0;
		StringBuilder lootString = new StringBuilder();

		for (ItemStack itemStack : items)
		{
			int itemId = itemStack.getId();
			int quantity = itemStack.getQuantity();
			int price = itemManager.getItemPrice(itemId);
			totalValue += (long) price * quantity;

			String itemName = itemManager.getItemComposition(itemId).getName();
			if (lootString.length() > 0)
			{
				lootString.append(", ");
			}
			lootString.append(quantity).append(" x ").append(itemName);
		}

		if (totalValue >= config.minLootValue())
		{
			String message = String.format("Loot received from %s: %s (Total value: %,d gp)",
				source, lootString.toString(), totalValue);
			
			String itemName = null;
			if (items.size() == 1)
			{
				itemName = itemManager.getItemComposition(items.iterator().next().getId()).getName();
			}
			else if (!items.isEmpty())
			{
				// Find the most valuable item to use as a bundling key
				long maxPrice = -1;
				for (ItemStack item : items)
				{
					long price = (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
					if (price > maxPrice)
					{
						maxPrice = price;
						itemName = itemManager.getItemComposition(item.getId()).getName();
					}
				}
			}

			if (config.sendScreenshot())
			{
				takeScreenshotAndSend(message, itemName, WebhookService.WebhookCategory.LOOT);
			}
			else
			{
				webhookService.sendWebhook(config.webhookUrl(), message, null, itemName, WebhookService.WebhookCategory.LOOT);
			}
		}

		// Always log to the external API if enabled
		logLoot(source, items, totalValue);

		// Check for other bingo items
		checkOtherBingoItems(source, items);
	}

	private void checkOtherBingoItems(String source, Collection<ItemStack> items)
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

		for (ItemStack itemStack : items)
		{
			String itemName = itemManager.getItemComposition(itemStack.getId()).getName();
			if (LootMatcher.matchesAny(itemName, otherBingoItems))
			{
				sendBingoNotification(source, itemName, itemStack.getQuantity());
			}
		}
	}

	private void sendBingoNotification(String source, String itemName, int quantity)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** just received a special item from %s: **%d x %s**!",
			playerName, source, quantity, itemName);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message, itemName, WebhookService.WebhookCategory.BINGO_LOOT);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message, null, itemName, WebhookService.WebhookCategory.BINGO_LOOT);
		}
	}

	private void logLoot(String source, Collection<ItemStack> items, long totalValue)
	{
		List<LootRecord.LootItem> lootItems = items.stream()
			.map(item -> LootRecord.LootItem.builder()
				.id(item.getId())
				.name(itemManager.getItemComposition(item.getId()).getName())
				.quantity(item.getQuantity())
				.price(itemManager.getItemPrice(item.getId()))
				.build())
			.collect(Collectors.toList());

		LootRecord lootRecord = LootRecord.builder()
			.source(source)
			.items(lootItems)
			.totalValue(totalValue)
			.build();

		logService.log("LOOT", lootRecord);
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

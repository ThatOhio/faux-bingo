package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import java.awt.image.BufferedImage;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.ui.DrawManager;

/**
 * Handles loot-related events from NPCs and players.
 * Calculates total loot value and triggers webhook notifications when threshold is met.
 */
@Slf4j
public class LootEventHandler
{
	private final FauxBingoConfig config;
	private final ItemManager itemManager;
	private final WebhookService webhookService;
	private final LogService logService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	public LootEventHandler(
		FauxBingoConfig config,
		ItemManager itemManager,
		WebhookService webhookService,
		LogService logService,
		DrawManager drawManager,
		ScheduledExecutorService executor)
	{
		this.config = config;
		this.itemManager = itemManager;
		this.webhookService = webhookService;
		this.logService = logService;
		this.drawManager = drawManager;
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
			
			String itemName = items.size() == 1 ? itemManager.getItemComposition(items.iterator().next().getId()).getName() : null;

			if (config.sendScreenshot())
			{
				takeScreenshotAndSend(message, itemName);
			}
			else
			{
				webhookService.sendWebhook(config.webhookUrl(), message, null, itemName, WebhookService.WebhookCategory.LOOT);
			}
		}

		// Always log to the external API if enabled
		logLoot(source, items, totalValue);
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

	private void takeScreenshotAndSend(String message, String itemName)
	{
		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image, itemName, WebhookService.WebhookCategory.LOOT);
				}
				catch (Exception e)
				{
					log.error("Error sending webhook with screenshot", e);
				}
			});
		});
	}
}

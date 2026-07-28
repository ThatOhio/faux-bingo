package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPCComposition;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Handles loot-related events from NPCs, players, and non-combat sources.
 * Calculates total loot value and triggers webhook notifications when threshold is met.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class LootEventHandler
{
	/**
	 * How long a kill stays eligible to be paired with its counterpart event.
	 */
	private static final long PAIRING_WINDOW_MS = 5_000;

	private enum NpcLootSignal
	{
		TILE_SCAN,
		SERVER;

		NpcLootSignal other()
		{
			return this == TILE_SCAN ? SERVER : TILE_SCAN;
		}
	}

	/**
	 * Kills seen through one NPC loot event and still waiting for the other.
	 */
	private final Deque<SeenKill> unpairedKills = new ArrayDeque<>();

	private static class SeenKill
	{
		private final String key;
		private final NpcLootSignal signal;
		private final long timestamp;

		SeenKill(String key, NpcLootSignal signal, long timestamp)
		{
			this.key = key;
			this.signal = signal;
			this.timestamp = timestamp;
		}
	}

	private final FauxBingoConfig config;
	private final ItemManager itemManager;
	private final WebhookService webhookService;
	private final LogService logService;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		processNpcLoot(event.getNpc().getName(), event.getItems(), NpcLootSignal.TILE_SCAN);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		processLoot(event.getPlayer().getName(), event.getItems());
	}

	/**
	 * The server driven counterpart to NpcLootReceived. Bosses whose loot does not land on their own
	 * tile (Yama at his throne for example) have no case in LootManager's getDropLocations, 
	 * so this is the only event they ever produce.
	 */
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot event)
	{
		NPCComposition npc = event.getComposition();
		processNpcLoot(npc != null ? npc.getName() : null, event.getItems(), NpcLootSignal.SERVER);
	}

	/**
	 * Most kills fire both NPC loot events, so the first one through wins and the second is paired
	 * off against it. Pairing consumes a single entry rather than suppressing everything with a
	 * matching key, so back to back kills of the same NPC with identical loot still report once each.
	 */
	private void processNpcLoot(String source, Collection<ItemStack> items, NpcLootSignal signal)
	{
		if (items == null || items.isEmpty())
		{
			return;
		}

		String key = buildKillKey(source, items);
		long now = System.currentTimeMillis();
		dropStaleKills(now);

		for (Iterator<SeenKill> it = unpairedKills.iterator(); it.hasNext(); )
		{
			SeenKill seen = it.next();
			if (seen.signal == signal.other() && seen.key.equals(key))
			{
				it.remove();
				log.debug("Skipping {} loot for {}, already reported via {}", signal, source, seen.signal);
				return;
			}
		}

		unpairedKills.add(new SeenKill(key, signal, now));
		processLoot(source, items);
	}

	private void dropStaleKills(long now)
	{
		while (!unpairedKills.isEmpty() && now - unpairedKills.peek().timestamp > PAIRING_WINDOW_MS)
		{
			unpairedKills.poll();
		}
	}

	/** Source plus its loot, order independent so both events produce the same key. */
	private static String buildKillKey(String source, Collection<ItemStack> items)
	{
		List<String> stacks = new ArrayList<>(items.size());
		for (ItemStack item : items)
		{
			stacks.add(item.getId() + "x" + item.getQuantity());
		}
		stacks.sort(null);
		return source + "|" + String.join(",", stacks);
	}

	/**
	 * Non-combat loot (Tempoross reward pool, Wintertodt crates, clue caskets, chests, Guardians of
	 * the Rift, and friends) never produces an NpcLootReceived. It only surfaces as a LootReceived
	 * from the Loot Tracker plugin, so this is the only way we see any of it.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// NPC and player kills already reach us through LootManager
		if (event.getType() != LootRecordType.EVENT)
		{
			return;
		}

		Collection<ItemStack> items = event.getItems();
		if (items == null || items.isEmpty())
		{
			return;
		}

		processLoot(event.getName(), items);
	}

	private void processLoot(String source, Collection<ItemStack> items)
	{
		long totalValue = 0;
		Map<String, Integer> quantityByName = new LinkedHashMap<>();

		for (ItemStack itemStack : items)
		{
			int itemId = itemStack.getId();
			int quantity = itemStack.getQuantity();
			int price = itemManager.getItemPrice(itemId);
			totalValue += (long) price * quantity;

			String itemName = itemManager.getItemComposition(itemId).getName();
			quantityByName.merge(itemName, quantity, Integer::sum);
		}

		StringBuilder lootString = new StringBuilder();
		for (Map.Entry<String, Integer> entry : quantityByName.entrySet())
		{
			if (lootString.length() > 0)
			{
				lootString.append(", ");
			}
			lootString.append(entry.getValue()).append(" x ").append(entry.getKey());
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

			takeScreenshotAndSend(message, itemName, WebhookService.WebhookCategory.LOOT);
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

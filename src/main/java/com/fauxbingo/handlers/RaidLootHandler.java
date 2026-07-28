package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Handles raid loot notifications for Chambers of Xeric and Theatre of Blood.
 * Tracks raid completions and unique drops from both raids.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RaidLootHandler
{
	private static final Pattern COX_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) - (.+)");
	private static final String COX_DUST_MESSAGE_TEXT = "Dust recipients: ";
	private static final String COX_KIT_MESSAGE_TEXT = "Twisted Kit recipients: ";
	private static final Pattern TOB_UNIQUE_MESSAGE_PATTERN = Pattern.compile("(.+) found something special: (.+)");
	private static final Pattern TOA_UNIQUE_MESSAGE_PATTERN = Pattern.compile("Loot recipient: (.+) - (.+)");
	private static final Pattern KC_MESSAGE_PATTERN = Pattern.compile("([0-9]+)");

	private static final int CoX_Interface_Id = InterfaceID.RAIDS_REWARDS;
	private static final int ToB_Interface_Id = InterfaceID.TOB_CHESTS;
	private static final int ToA_Interface_Id = 775; // InterfaceID.TOA_REWARD_CHEST might not be available in all versions

	private static final int CoX_Container_Id = 581;
	private static final int ToB_Container_Id = 612;
	private static final int ToA_Container_Id = 801;

	enum RaidType
	{
		COX,
		COX_CM,
		TOB,
		TOB_SM,
		TOB_HM,
		TOA_ENTRY,
		TOA_NORMAL,
		TOA_EXPERT
	}

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final LogService logService;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final ItemManager itemManager;

	private RaidType raidType;
	private Integer raidKc;
	private final List<String> rareDrops = new ArrayList<>();
	private boolean raidProcessed = false;
	private long lastProcessedTime = 0;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.includeRaidLoot())
		{
			return;
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM
			&& event.getType() != ChatMessageType.TRADE
			&& event.getType() != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		handleRaidChatMessage(event.getMessage());
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!config.includeRaidLoot())
		{
			return;
		}

		int groupId = event.getGroupId();

		if (groupId == CoX_Interface_Id || groupId == ToB_Interface_Id || groupId == ToA_Interface_Id)
		{
			handleRaidRewardWidget();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!config.includeRaidLoot())
		{
			return;
		}

		int containerId = event.getContainerId();
		if (containerId == CoX_Container_Id || containerId == ToB_Container_Id || containerId == ToA_Container_Id)
		{
			handleRaidInventory(containerId, event.getItemContainer());
		}
	}

	private void handleRaidChatMessage(String chatMessage)
	{
		String cleanMessage = Text.removeTags(chatMessage);

		// Check for completion messages - these mark the start of a new raid loot sequence
		if (cleanMessage.startsWith("Your completed Chambers of Xeric count is:") ||
			cleanMessage.startsWith("Your completed Theatre of Blood") ||
			(cleanMessage.contains("Tombs of Amascut") && cleanMessage.contains("completion count is")))
		{
			// Reset state for new raid loot
			resetState();
		}

		// Check for COX completion
		if (cleanMessage.startsWith("Your completed Chambers of Xeric count is:"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				raidType = cleanMessage.contains("Challenge Mode") ? RaidType.COX_CM : RaidType.COX;
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOB completion
		if (cleanMessage.startsWith("Your completed Theatre of Blood"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				raidType = cleanMessage.contains("Hard Mode") ? RaidType.TOB_HM :
					(cleanMessage.contains("Story Mode") ? RaidType.TOB_SM : RaidType.TOB);
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOA completion
		if (cleanMessage.contains("Tombs of Amascut") && cleanMessage.contains("completion count is"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(cleanMessage);
			if (matcher.find())
			{
				if (cleanMessage.contains("Expert Mode"))
				{
					raidType = RaidType.TOA_EXPERT;
				}
				else if (cleanMessage.contains("Entry Mode"))
				{
					raidType = RaidType.TOA_ENTRY;
				}
				else
				{
					raidType = RaidType.TOA_NORMAL;
				}
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOA unique drops
		Matcher toaUnique = TOA_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (toaUnique.matches())
		{
			final String lootRecipient = Text.sanitize(toaUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(toaUnique.group(2).trim());
			}
			return;
		}

		// Check for COX unique drops
		Matcher coxUnique = COX_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (coxUnique.matches())
		{
			final String lootRecipient = Text.sanitize(coxUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(coxUnique.group(2).trim());
			}
			return;
		}

		// Check for COX metamorphic dust
		if (cleanMessage.startsWith(COX_DUST_MESSAGE_TEXT))
		{
			if (cleanMessage.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
			{
				rareDrops.add("Metamorphic dust");
			}
			return;
		}

		// Check for COX twisted kit
		if (cleanMessage.startsWith(COX_KIT_MESSAGE_TEXT))
		{
			if (cleanMessage.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
			{
				rareDrops.add("Twisted ancestral colour kit");
			}
			return;
		}

		// Check for TOB unique drops
		Matcher tobUnique = TOB_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (tobUnique.matches())
		{
			final String lootRecipient = Text.sanitize(tobUnique.group(1)).trim();
			if (lootRecipient.equalsIgnoreCase(getLocalPlayerName()))
			{
				rareDrops.add(tobUnique.group(2).trim());
			}
			return;
		}
	}

	private void handleRaidRewardWidget()
	{
		// Widget loading is a good signal that we're looking at loot.
		// If we haven't processed for a while, we can assume it's a new attempt.
		if (System.currentTimeMillis() - lastProcessedTime > 60000)
		{
			raidProcessed = false;
		}
	}

	private void handleRaidInventory(int containerId, ItemContainer itemContainer)
	{
		if (itemContainer == null || raidProcessed)
		{
			return;
		}

		String raidName = getRaidName(containerId);
		if (raidName == null)
		{
			return;
		}

		// Only process if container has items
		Item[] items = itemContainer.getItems();
		if (items == null || items.length == 0 || Arrays.stream(items).allMatch(i -> i.getId() == -1))
		{
			return;
		}

		try
		{
			processRaidLoot(raidName, itemContainer);
		}
		finally
		{
			// Clear per-raid rare drop state after loot is processed to avoid replaying on later chests.
			rareDrops.clear();
		}
		raidProcessed = true;
		lastProcessedTime = System.currentTimeMillis();
	}

	private void processRaidLoot(String raidName, ItemContainer itemContainer)
	{
		long totalValue = 0;
		List<LootRecord.LootItem> allItems = new ArrayList<>();

		for (Item item : itemContainer.getItems())
		{
			int itemId = item.getId();
			if (itemId != -1)
			{
				String itemName = itemManager.getItemComposition(itemId).getName();
				int quantity = item.getQuantity();
				int price = itemManager.getItemPrice(itemId);
				totalValue += (long) price * quantity;

				allItems.add(LootRecord.LootItem.builder()
					.id(itemId)
					.name(itemName)
					.quantity(quantity)
					.price(price)
					.build());
			}
		}

		boolean hasRareDrop = !rareDrops.isEmpty();
		boolean isValuable = totalValue >= config.minLootValue();

		if (hasRareDrop || isValuable)
		{
			sendConsolidatedRaidNotification(raidName, allItems, totalValue);
		}
		else
		{
			logGeneralLoot(allItems, totalValue, raidName, raidKc);
		}
	}

	private void sendConsolidatedRaidNotification(String raidName, List<LootRecord.LootItem> allItems, long totalValue)
	{
		String playerName = getLocalPlayerName();
		StringBuilder message = new StringBuilder();

		String bundlingItem = null;

		if (!rareDrops.isEmpty())
		{
			bundlingItem = rareDrops.get(0);
			message.append(String.format("**%s** just received a rare drop from %s: **%s**!\n",
				playerName, raidName, String.join(", ", rareDrops)));
		}
		else
		{
			message.append(String.format("**%s** just received loot from %s:\n", playerName, raidName));
		}

		// Add all loot details
		StringBuilder lootList = new StringBuilder();
		for (LootRecord.LootItem item : allItems)
		{
			if (lootList.length() > 0) lootList.append(", ");
			lootList.append(item.getQuantity()).append(" x ").append(item.getName());
		}
		message.append("Loot: ").append(lootList).append(String.format(" (Total value: %,d gp)", totalValue));

		if (raidKc != null && raidKc > 0)
		{
			message.append(String.format("\nKill Count: **%d**", raidKc));
		}

		takeScreenshotAndSend(message.toString(), bundlingItem, WebhookService.WebhookCategory.RAID_LOOT);

		logGeneralLoot(allItems, totalValue, raidName, raidKc);
	}

	private String getRaidName(int id)
	{
		if (id == CoX_Interface_Id || id == CoX_Container_Id)
		{
			if (raidType == RaidType.COX_CM)
			{
				return "Chambers of Xeric Challenge Mode";
			}
			return "Chambers of Xeric";
		}
		else if (id == ToB_Interface_Id || id == ToB_Container_Id)
		{
			if (raidType == RaidType.TOB_SM)
			{
				return "Theatre of Blood Story Mode";
			}
			else if (raidType == RaidType.TOB_HM)
			{
				return "Theatre of Blood Hard Mode";
			}
			return "Theatre of Blood";
		}
		else if (id == ToA_Interface_Id || id == ToA_Container_Id)
		{
			if (raidType == RaidType.TOA_EXPERT)
			{
				return "Tombs of Amascut Expert Mode";
			}
			else if (raidType == RaidType.TOA_ENTRY)
			{
				return "Tombs of Amascut Entry Mode";
			}
			return "Tombs of Amascut";
		}
		return null;
	}

	private void logGeneralLoot(List<LootRecord.LootItem> items, long totalValue, String raidName, Integer kc)
	{
		LootRecord lootRecord = LootRecord.builder()
			.source(raidName)
			.items(items)
			.totalValue(totalValue)
			.kc(kc)
			.build();

		logService.log("RAID_LOOT", lootRecord);
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
				log.error("Error sending webhook with screenshot for raid loot", e);
			}
		}));
	}

	public void resetState()
	{
		raidType = null;
		raidKc = null;
		rareDrops.clear();
	}

	private String getLocalPlayerName()
	{
		return client.getLocalPlayer() != null ? Text.sanitize(client.getLocalPlayer().getName()) : "Player";
	}
}

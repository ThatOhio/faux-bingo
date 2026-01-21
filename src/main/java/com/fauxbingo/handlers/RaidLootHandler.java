package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import com.fauxbingo.util.LootMatcher;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Handles raid loot notifications for Chambers of Xeric and Theatre of Blood.
 * Tracks raid completions and unique drops from both raids.
 */
@Slf4j
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
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final ItemManager itemManager;

	private RaidType raidType;
	private Integer raidKc;
	private final List<String> rareDrops = new ArrayList<>();

	public RaidLootHandler(
		Client client,
		FauxBingoConfig config,
		WebhookService webhookService,
		LogService logService,
		DrawManager drawManager,
		ScheduledExecutorService executor,
		ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.webhookService = webhookService;
		this.logService = logService;
		this.drawManager = drawManager;
		this.executor = executor;
		this.itemManager = itemManager;
	}

	public EventHandler<ChatMessage> createChatHandler()
	{
		return new EventHandler<ChatMessage>()
		{
			@Override
			public void handle(ChatMessage event)
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

				String chatMessage = event.getMessage();
				handleRaidChatMessage(chatMessage);
			}

			@Override
			public Class<ChatMessage> getEventType()
			{
				return ChatMessage.class;
			}
		};
	}

	public EventHandler<WidgetLoaded> createWidgetHandler()
	{
		return new EventHandler<WidgetLoaded>()
		{
			@Override
			public void handle(WidgetLoaded event)
			{
				if (!config.includeRaidLoot())
				{
					return;
				}

				int groupId = event.getGroupId();

				if (groupId == CoX_Interface_Id || groupId == ToB_Interface_Id || groupId == ToA_Interface_Id)
				{
					handleRaidRewardWidget(groupId);
				}
			}

			@Override
			public Class<WidgetLoaded> getEventType()
			{
				return WidgetLoaded.class;
			}
		};
	}

	public EventHandler<ItemContainerChanged> createItemContainerHandler()
	{
		return new EventHandler<ItemContainerChanged>()
		{
			@Override
			public void handle(ItemContainerChanged event)
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

			@Override
			public Class<ItemContainerChanged> getEventType()
			{
				return ItemContainerChanged.class;
			}
		};
	}

	private void handleRaidChatMessage(String chatMessage)
	{
		// Check for COX completion
		if (chatMessage.startsWith("Your completed Chambers of Xeric count is:"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(Text.removeTags(chatMessage));
			if (matcher.find())
			{
				raidType = chatMessage.contains("Challenge Mode") ? RaidType.COX_CM : RaidType.COX;
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOB completion
		if (chatMessage.startsWith("Your completed Theatre of Blood"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(Text.removeTags(chatMessage));
			if (matcher.find())
			{
				raidType = chatMessage.contains("Hard Mode") ? RaidType.TOB_HM : 
					(chatMessage.contains("Story Mode") ? RaidType.TOB_SM : RaidType.TOB);
				raidKc = Integer.valueOf(matcher.group());
				return;
			}
		}

		// Check for TOA completion
		if (chatMessage.contains("Tombs of Amascut") && chatMessage.contains("completion count is"))
		{
			Matcher matcher = KC_MESSAGE_PATTERN.matcher(Text.removeTags(chatMessage));
			if (matcher.find())
			{
				if (chatMessage.contains("Expert Mode"))
				{
					raidType = RaidType.TOA_EXPERT;
				}
				else if (chatMessage.contains("Entry Mode"))
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
		if (chatMessage.startsWith(COX_DUST_MESSAGE_TEXT))
		{
			final String dustRecipients = Text.removeTags(chatMessage).substring(COX_DUST_MESSAGE_TEXT.length());
			if (dustRecipients.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
			{
				rareDrops.add("Metamorphic dust");
			}
			return;
		}

		// Check for COX twisted kit
		if (chatMessage.startsWith(COX_KIT_MESSAGE_TEXT))
		{
			final String kitRecipients = Text.removeTags(chatMessage).substring(COX_KIT_MESSAGE_TEXT.length());
			if (kitRecipients.toLowerCase().contains(getLocalPlayerName().toLowerCase()))
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

	private void handleRaidRewardWidget(int groupId)
	{
		if (raidType == null && rareDrops.isEmpty())
		{
			return;
		}

		String raidName = getRaidName(groupId);
		if (raidName == null)
		{
			return;
		}

		// Process rare drops from chat
		for (String itemName : rareDrops)
		{
			sendRaidLootNotification(itemName, raidName, raidKc);
		}

		// Check bingo items from widget
		checkBingoItems(groupId, raidName);

		// Reset state
		resetState();
	}

	private void handleRaidInventory(int containerId, ItemContainer itemContainer)
	{
		if (raidType == null && rareDrops.isEmpty())
		{
			return;
		}

		String raidName = getRaidName(containerId);
		if (raidName == null)
		{
			return;
		}

		// 1. Handle rare drops from chat
		for (String itemName : rareDrops)
		{
			sendRaidLootNotification(itemName, raidName, raidKc);
		}

		// 2. Handle all items in container (Bingo + Value)
		processContainerItems(itemContainer, raidName);

		// Reset state
		resetState();
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

	private void checkBingoItems(int groupId, String raidName)
	{
		List<String> bingoItems = getBingoItems(groupId);
		List<String> otherBingoItems = getOtherBingoItems();

		if (bingoItems.isEmpty() && otherBingoItems.isEmpty())
		{
			return;
		}

		int childId = -1;
		if (groupId == CoX_Interface_Id) childId = 1;
		else if (groupId == ToB_Interface_Id) childId = 3;
		else if (groupId == ToA_Interface_Id) childId = 2;

		if (childId == -1)
		{
			return;
		}

		Widget rewardWidget = client.getWidget(groupId, childId);
		if (rewardWidget == null)
		{
			return;
		}

		Widget[] children = rewardWidget.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			// Try static children as a fallback
			children = rewardWidget.getStaticChildren();
		}

		if (children == null || children.length == 0)
		{
			return;
		}

		for (Widget child : children)
		{
			int itemId = child.getItemId();
			if (itemId != -1)
			{
				String itemName = itemManager.getItemComposition(itemId).getName();
				if (LootMatcher.matchesAny(itemName, bingoItems) || LootMatcher.matchesAny(itemName, otherBingoItems))
				{
					// Avoid duplicates if this item was already notified as a rare drop
					if (isRareDrop(itemName))
					{
						continue;
					}
					sendBingoLootNotification(itemName, child.getItemQuantity(), raidName, raidKc);
				}
			}
		}
	}

	private boolean isRareDrop(String itemName)
	{
		return rareDrops.stream().anyMatch(drop -> drop.equalsIgnoreCase(itemName));
	}

	private void processContainerItems(ItemContainer itemContainer, String raidName)
	{
		int id = getContainerInterfaceId(itemContainer.getId());
		List<String> bingoItems = getBingoItems(id);
		List<String> otherBingoItems = getOtherBingoItems();

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

				if (LootMatcher.matchesAny(itemName, bingoItems) || LootMatcher.matchesAny(itemName, otherBingoItems))
				{
					// Avoid duplicates if this item was already notified as a rare drop
					if (isRareDrop(itemName))
					{
						continue;
					}
					sendBingoLootNotification(itemName, quantity, raidName, raidKc);
				}
			}
		}

		if (totalValue >= config.minLootValue())
		{
			sendValuableLootNotification(allItems, totalValue, raidName, raidKc);
		}
		else
		{
			// Always log to the external API if enabled
			logGeneralLoot(allItems, totalValue, raidName, raidKc);
		}
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

	private void sendValuableLootNotification(List<LootRecord.LootItem> items, long totalValue, String raidName, Integer kc)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		
		StringBuilder lootString = new StringBuilder();
		for (LootRecord.LootItem item : items)
		{
			if (lootString.length() > 0) lootString.append(", ");
			lootString.append(item.getQuantity()).append(" x ").append(item.getName());
		}

		StringBuilder message = new StringBuilder();
		message.append(String.format("**%s** just received loot from %s: %s (Total value: %,d gp)",
			playerName, raidName, lootString.toString(), totalValue));
		
		if (kc != null && kc > 0)
		{
			message.append(String.format("\nKill Count: **%d**", kc));
		}

		String mainItem = items.size() == 1 ? items.get(0).getName() : null;

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message.toString(), mainItem, WebhookService.WebhookCategory.RAID_LOOT);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message.toString(), null, mainItem, WebhookService.WebhookCategory.RAID_LOOT);
		}

		logGeneralLoot(items, totalValue, raidName, kc);
	}

	private int getContainerInterfaceId(int containerId)
	{
		if (containerId == CoX_Container_Id) return CoX_Interface_Id;
		if (containerId == ToB_Container_Id) return ToB_Interface_Id;
		if (containerId == ToA_Container_Id) return ToA_Interface_Id;
		return -1;
	}

	private List<String> getBingoItems(int id)
	{
		String configItems = "";
		if (id == CoX_Interface_Id || id == CoX_Container_Id)
		{
			configItems = config.coxBingoItems();
		}
		else if (id == ToB_Interface_Id || id == ToB_Container_Id)
		{
			configItems = config.tobBingoItems();
		}
		else if (id == ToA_Interface_Id || id == ToA_Container_Id)
		{
			configItems = config.toaBingoItems();
		}

		if (configItems == null || configItems.isEmpty())
		{
			return Collections.emptyList();
		}

		return Arrays.stream(configItems.split("[\n,]"))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());
	}

	private List<String> getOtherBingoItems()
	{
		String otherItems = config.otherBingoItems();
		if (otherItems == null || otherItems.isEmpty())
		{
			return Collections.emptyList();
		}

		return Arrays.stream(otherItems.split("[\n,]"))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());
	}

	private void sendBingoLootNotification(String itemName, int quantity, String raidName, Integer kc)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		StringBuilder message = new StringBuilder();
		message.append(String.format("**%s** just received a special item from %s: **%d x %s**!",
			playerName, raidName, quantity, itemName));
		
		if (kc != null && kc > 0)
		{
			message.append(String.format("\nKill Count: **%d**", kc));
		}

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message.toString(), itemName, WebhookService.WebhookCategory.BINGO_LOOT);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message.toString(), null, itemName, WebhookService.WebhookCategory.BINGO_LOOT);
		}

		logBingoLoot(itemName, quantity, raidName, kc);
	}

	private void logBingoLoot(String itemName, int quantity, String raidName, Integer kc)
	{
		LootRecord lootRecord = LootRecord.builder()
			.source(raidName)
			.items(Collections.singletonList(LootRecord.LootItem.builder()
				.name(itemName)
				.quantity(quantity)
				.build()))
			.kc(kc)
			.build();

		logService.log("BINGO_LOOT", lootRecord);
	}

	private void sendRaidLootNotification(String itemName, String raidName, Integer kc)
	{
		String playerName = getLocalPlayerName();
		StringBuilder message = new StringBuilder();
		message.append(String.format("**%s** just received a rare drop from %s: **%s**!", 
			playerName, raidName, itemName));
		
		if (kc != null && kc > 0)
		{
			message.append(String.format("\nKill Count: **%d**", kc));
		}

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message.toString(), itemName, WebhookService.WebhookCategory.RAID_LOOT);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message.toString(), null, itemName, WebhookService.WebhookCategory.RAID_LOOT);
		}

		logRaidLoot(itemName, raidName, kc);
	}

	private void logRaidLoot(String itemName, String raidName, Integer kc)
	{
		LootRecord lootRecord = LootRecord.builder()
			.source(raidName)
			.items(Collections.singletonList(LootRecord.LootItem.builder()
				.name(itemName)
				.quantity(1)
				.build()))
			.kc(kc)
			.build();

		logService.log("RAID_LOOT", lootRecord);
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
					log.error("Error sending webhook with screenshot for raid loot", e);
				}
			});
		});
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

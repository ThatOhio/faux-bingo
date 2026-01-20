package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.WebhookService;
import com.fauxbingo.services.data.LootRecord;
import com.fauxbingo.util.LootMatcher;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
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
	private static final Pattern KC_MESSAGE_PATTERN = Pattern.compile("([0-9]+)");

	private static final int CoX_Interface_Id = InterfaceID.RAIDS_REWARDS;
	private static final int ToB_Interface_Id = InterfaceID.TOB_CHESTS;
	private static final int ToA_Interface_Id = 775; // InterfaceID.TOA_REWARD_CHEST might not be available in all versions

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
	private String raidItemName;

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

		// Check for COX unique drops
		Matcher coxUnique = COX_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (coxUnique.matches())
		{
			final String lootRecipient = Text.sanitize(coxUnique.group(1)).trim();
			final String dropName = coxUnique.group(2).trim();

			if (lootRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
			{
				raidItemName = dropName;
				// Note: COX uniques are sent when widget loads
			}
		}

		// Check for COX metamorphic dust
		if (chatMessage.startsWith(COX_DUST_MESSAGE_TEXT))
		{
			final String dustRecipient = Text.removeTags(chatMessage).substring(COX_DUST_MESSAGE_TEXT.length());
			
			if (dustRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
			{
				raidItemName = "Metamorphic dust";
			}
		}

		// Check for COX twisted kit
		if (chatMessage.startsWith(COX_KIT_MESSAGE_TEXT))
		{
			final String kitRecipient = Text.removeTags(chatMessage).substring(COX_KIT_MESSAGE_TEXT.length());
			
			if (kitRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
			{
				raidItemName = "Twisted ancestral colour kit";
			}
		}

		// Check for TOB unique drops
		Matcher tobUnique = TOB_UNIQUE_MESSAGE_PATTERN.matcher(chatMessage);
		if (tobUnique.matches())
		{
			final String lootRecipient = Text.sanitize(tobUnique.group(1)).trim();
			final String dropName = tobUnique.group(2).trim();

			if (lootRecipient.equals(Text.sanitize(Objects.requireNonNull(client.getLocalPlayer().getName()))))
			{
				raidItemName = dropName;
				// Note: TOB uniques are sent when widget loads
			}
		}
	}

	private void handleRaidRewardWidget(int groupId)
	{
		String raidName = getRaidName(groupId);
		if (raidName == null)
		{
			return;
		}

		// 1. Handle rare drop if detected from chat
		if (raidItemName != null)
		{
			sendRaidLootNotification(raidItemName, raidName, raidKc);
		}

		// 2. Handle bingo items from the widget
		checkBingoItems(groupId, raidName);

		// Reset state
		raidItemName = null;
		raidType = null;
		raidKc = null;
	}

	private String getRaidName(int groupId)
	{
		if (groupId == CoX_Interface_Id)
		{
			if (raidType == RaidType.COX_CM)
			{
				return "Chambers of Xeric Challenge Mode";
			}
			return "Chambers of Xeric";
		}
		else if (groupId == ToB_Interface_Id)
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
		else if (groupId == ToA_Interface_Id)
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
		String configItems = "";
		if (groupId == CoX_Interface_Id)
		{
			configItems = config.coxBingoItems();
		}
		else if (groupId == ToB_Interface_Id)
		{
			configItems = config.tobBingoItems();
		}
		else if (groupId == ToA_Interface_Id)
		{
			configItems = config.toaBingoItems();
		}

		if (configItems == null)
		{
			configItems = "";
		}

		String otherItems = config.otherBingoItems();
		if (otherItems == null)
		{
			otherItems = "";
		}

		if (configItems.isEmpty() && otherItems.isEmpty())
		{
			return;
		}

		List<String> bingoItems = Arrays.stream(configItems.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());

		List<String> otherBingoItems = Arrays.stream(otherItems.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());

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
					// If this item was already identified as the rare drop, we don't need a second notification
					if (raidItemName != null && itemName.equalsIgnoreCase(raidItemName))
					{
						continue;
					}
					sendBingoLootNotification(itemName, child.getItemQuantity(), raidName, raidKc);
				}
			}
		}
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
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
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
		raidItemName = null;
	}
}

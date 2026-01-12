package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.WebhookService;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import static net.runelite.api.widgets.WidgetID.CHAMBERS_OF_XERIC_REWARD_GROUP_ID;
import static net.runelite.api.widgets.WidgetID.THEATRE_OF_BLOOD_REWARD_GROUP_ID;
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

	enum RaidType
	{
		COX,
		COX_CM,
		TOB,
		TOB_SM,
		TOB_HM
	}

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	private RaidType raidType;
	private Integer raidKc;
	private String raidItemName;

	public RaidLootHandler(
		Client client,
		FauxBingoConfig config,
		WebhookService webhookService,
		DrawManager drawManager,
		ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.webhookService = webhookService;
		this.drawManager = drawManager;
		this.executor = executor;
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

				if (groupId == CHAMBERS_OF_XERIC_REWARD_GROUP_ID || groupId == THEATRE_OF_BLOOD_REWARD_GROUP_ID)
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
		if (raidItemName == null)
		{
			return;
		}

		String raidName = null;

		if (groupId == CHAMBERS_OF_XERIC_REWARD_GROUP_ID)
		{
			if (raidType == RaidType.COX)
			{
				raidName = "Chambers of Xeric";
			}
			else if (raidType == RaidType.COX_CM)
			{
				raidName = "Chambers of Xeric Challenge Mode";
			}
		}
		else if (groupId == THEATRE_OF_BLOOD_REWARD_GROUP_ID)
		{
			if (raidType == RaidType.TOB)
			{
				raidName = "Theatre of Blood";
			}
			else if (raidType == RaidType.TOB_SM)
			{
				raidName = "Theatre of Blood Story Mode";
			}
			else if (raidType == RaidType.TOB_HM)
			{
				raidName = "Theatre of Blood Hard Mode";
			}
		}

		if (raidName != null)
		{
			sendRaidLootNotification(raidItemName, raidName, raidKc);
		}

		// Reset state
		raidItemName = null;
		raidType = null;
		raidKc = null;
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
			takeScreenshotAndSend(message.toString());
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message.toString(), null);
		}
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

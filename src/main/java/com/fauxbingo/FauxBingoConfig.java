package com.fauxbingo;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("fauxbingo")
public interface FauxBingoConfig extends Config
{
	@ConfigSection(
		name = "API",
		description = "Enable external API support",
		position = 0
	)
	String apiSection = "api";

	@ConfigSection(
		name = "Team Overlay",
		description = "Configure team name and timestamp overlay display",
		position = 1
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Discord Alerts",
		description = "Configure Discord webhook notifications",
		position = 3
	)
	String discordAlertsSection = "discordAlerts";

	@ConfigSection(
		name = "Bingo Tiles",
		description = "Configure specific items to track regardless of value",
		position = 4,
		closedByDefault = true
	)
	String bingoTilesSection = "bingoTiles";

	// ========== API Configuration ==========

	@ConfigItem(
			keyName = "enableBingoApi",
			name = "Enable Bingo API",
			description = "Enables the bingo API for fetching bingo configuration and logging bingo statistics.",
			warning = "This feature will submit your character name and IP address to a 3rd-party server not controlled or verified by Runelite developers. Data is used for bingo config and tile verification only.",
			position = 1,
			section = apiSection
	)
	default boolean enableBingoApi()
	{
		return false;
	}

	@ConfigItem(
			keyName = "enableWomAutoUpdate",
			name = "Enable WOM Auto-Update",
			description = "Automatically update your WiseOldMan stats on logout or when gaining 10k+ XP",
			position = 2,
			section = apiSection
	)
	default boolean enableWomAutoUpdate()
	{
		return false;
	}

	@ConfigItem(
			keyName = "showTeamIconsInChat",
			name = "Show Team Icons in Chat",
			description = "Displays a team icon before player names in chat for active bingo event participants. Requires Bingo API to be enabled.",
			position = 3,
			section = apiSection
	)
	default boolean showTeamIconsInChat()
	{
		return false;
	}


	// ========== Team Overlay Configuration ==========

	@ConfigItem(
		keyName = "displayOverlay",
		name = "Display Overlay",
		description = "Displays the team name and timestamp overlay on your game screen",
		position = 1,
		section = overlaySection
	)
	default boolean displayOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "displayDateTime",
		name = "Date & Time",
		description = "Adds the date and time to the overlay",
		position = 2,
		section = overlaySection
	)
	default boolean displayDateTime()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teamName",
		name = "Team Name",
		description = "Your team name to display in the overlay",
		position = 3,
		section = overlaySection
	)
	default String teamName()
	{
		return "";
	}

	@ConfigItem(
		keyName = "teamNameColor",
		name = "Team Name Color",
		description = "The color of the team name in the overlay",
		position = 4,
		section = overlaySection
	)
	default Color teamNameColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "dateTimeColor",
		name = "Date & Time Color",
		description = "The color of the date and time in the overlay",
		position = 5,
		section = overlaySection
	)
	default Color dateTimeColor()
	{
		return Color.WHITE;
	}

	// ========== Discord Alerts Configuration ==========

	@ConfigItem(
		keyName = "webhookUrl",
		name = "Webhook URL",
		description = "The Discord Webhook URL(s) to send loot notifications to, separated by newlines",
		position = 1,
		section = discordAlertsSection
	)
	default String webhookUrl()
	{
		return "";
	}


	@ConfigItem(
		keyName = "manualScreenshotKeybind",
		name = "Manual Screenshot Keybind",
		description = "Keybind to manually send a screenshot to the webhook",
		position = 3,
		section = discordAlertsSection
	)
	default Keybind manualScreenshotKeybind()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "screenshotHidePrivateMessages",
		name = "Hide PMs in Screenshots",
		description = "Hide private message windows before taking webhook screenshots, then restore them",
		position = 4,
		section = discordAlertsSection
	)
	default boolean screenshotHidePrivateMessages()
	{
		return false;
	}

	@ConfigItem(
		keyName = "screenshotHideChat",
		name = "Hide Chat in Screenshots",
		description = "Hide the main chat area before taking webhook screenshots, then restore it",
		position = 5,
		section = discordAlertsSection
	)
	default boolean screenshotHideChat()
	{
		return false;
	}

	@ConfigItem(
		keyName = "includePets",
		name = "Include Pets",
		description = "Send webhook notification when receiving a pet",
		position = 6,
		section = discordAlertsSection
	)
	default boolean includePets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeCollectionLog",
		name = "Include Collection Log",
		description = "Send webhook notification for new collection log entries",
		position = 7,
		section = discordAlertsSection
	)
	default boolean includeCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeValuableDrops",
		name = "Include Valuable Drops",
		description = "Send webhook notification for valuable drops above threshold",
		position = 8,
		section = discordAlertsSection
	)
	default boolean includeValuableDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeRaidLoot",
		name = "Include Raid Loot",
		description = "Send webhook notification for raid unique drops (COX/TOB)",
		position = 9,
		section = discordAlertsSection
	)
	default boolean includeRaidLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minLootValue",
		name = "Min Loot Value",
		description = "Min value (gp) for loot webhooks. Valuable drops, NPC/player loot, raid chests.",
		position = 10,
		section = discordAlertsSection
	)
	default int minLootValue()
	{
		return 1000000;
	}

	@ConfigItem(
		keyName = "funnyGameModeMessages",
		name = "Funny Game Mode Messages",
		description = "Include a goofy message when playing on temporary game modes (Leagues, Deadman, etc.)",
		position = 11,
		section = discordAlertsSection
	)
	default boolean funnyGameModeMessages()
	{
		return false;
	}

	// ========== Bingo Tiles Configuration ==========

	@ConfigItem(
		keyName = "coxBingoItems",
		name = "Chambers of Xeric",
		description = "Items to track from COX, separated by commas",
		position = 1,
		section = bingoTilesSection
	)
	default String coxBingoItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "tobBingoItems",
		name = "Theatre of Blood",
		description = "Items to track from TOB, separated by commas",
		position = 2,
		section = bingoTilesSection
	)
	default String tobBingoItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "toaBingoItems",
		name = "Tombs of Amascut",
		description = "Items to track from TOA, separated by commas",
		position = 3,
		section = bingoTilesSection
	)
	default String toaBingoItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "otherBingoItems",
		name = "Other Items",
		description = "Items to track from any source, separated by commas",
		position = 4,
		section = bingoTilesSection
	)
	default String otherBingoItems()
	{
		return "";
	}
}

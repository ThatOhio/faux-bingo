package com.fauxbingo.services.data;

/**
 * How the plugin learned about a drop. Mirrors docs/bingo-events-api.md section 6.5, plus two
 * methods the doc doesn't anticipate:
 * <ul>
 *   <li>{@link #SERVER_NPC_LOOT} - bosses whose loot never lands on their own tile (Yama, The
 *       Whisperer), only reachable through RuneLite's ServerNpcLoot event.</li>
 *   <li>{@link #LOOT_TRACKER_EVENT} - non-combat loot (Tempoross, Wintertodt, clues) that only
 *       ever arrives via the Loot Tracker plugin's LootReceived event.</li>
 * </ul>
 */
public enum DetectionMethod
{
	NPC_LOOT_RECEIVED(Confidence.EXACT, DropType.LOOT),
	PLAYER_LOOT_RECEIVED(Confidence.EXACT, DropType.LOOT),
	SERVER_NPC_LOOT(Confidence.EXACT, DropType.LOOT),
	LOOT_TRACKER_EVENT(Confidence.EXACT, DropType.LOOT),
	RAID_CHEST_CONTAINER(Confidence.EXACT, DropType.LOOT),
	CHAT_VALUABLE_DROP(Confidence.DERIVED, DropType.LOOT),
	CHAT_COLLECTION_LOG(Confidence.DERIVED, DropType.COLLECTION_LOG),
	NOTIFICATION_COLLECTION_LOG(Confidence.DERIVED, DropType.COLLECTION_LOG),
	CHAT_PET(Confidence.DERIVED, DropType.PET);

	private final Confidence confidence;
	private final DropType type;

	DetectionMethod(Confidence confidence, DropType type)
	{
		this.confidence = confidence;
		this.type = type;
	}

	public Confidence getConfidence()
	{
		return confidence;
	}

	public DropType getType()
	{
		return type;
	}
}

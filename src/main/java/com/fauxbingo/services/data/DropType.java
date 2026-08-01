package com.fauxbingo.services.data;

/**
 * The three drop-related outcomes a merged event can resolve to. VALUABLE_DROP and RAID_LOOT
 * are not separate types here, they collapse into LOOT along with everything else that hands
 * over items (NPC/player kills, raid chests), matching docs/bingo-events-api.md section 6.1.
 */
public enum DropType
{
	LOOT,
	COLLECTION_LOG,
	PET
}

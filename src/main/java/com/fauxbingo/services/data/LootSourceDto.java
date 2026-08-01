package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** `payload.source` on a LOOT envelope (docs/v1-api.md, LOOT payload shape). */
@Data
@Builder
public class LootSourceDto
{
	private String kind;
	private String name;
	private Integer npcId;
	private Integer combatLevel;
	private Integer killCount;
	private String variant;
	private LocationDto location;
}

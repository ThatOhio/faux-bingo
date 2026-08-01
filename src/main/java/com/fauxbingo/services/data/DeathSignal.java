package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** What DeathHandler hands to EventsApiService. Deaths never carry a screenshot. */
@Data
@Builder
public class DeathSignal
{
	private int regionId;
	private String killerName;
	/** PLAYER, NPC, or UNKNOWN. */
	private String killerKind;
}

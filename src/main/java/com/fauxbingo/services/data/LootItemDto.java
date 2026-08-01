package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** One entry in `payload.items[]` on a LOOT envelope. itemId is authoritative when present. */
@Data
@Builder
public class LootItemDto
{
	private Integer itemId;
	private String name;
	private int quantity;
	private Long unitPriceGe;
}

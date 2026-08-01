package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/**
 * One item within a DropSignal. id and unitPriceGe are absent on chat-derived signals, per
 * docs/bingo-events-api.md section 6.1: itemId is authoritative when present.
 */
@Data
@Builder
public class DropItem
{
	private Integer id;
	private String name;
	private int quantity;
	private Long unitPriceGe;
}

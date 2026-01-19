package com.fauxbingo.services.data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LootRecord
{
	private String source;
	private List<LootItem> items;
	private long totalValue;
	private Integer kc;

	@Data
	@Builder
	public static class LootItem
	{
		private int id;
		private String name;
		private int quantity;
		private int price;
	}
}

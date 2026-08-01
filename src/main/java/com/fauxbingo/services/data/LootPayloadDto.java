package com.fauxbingo.services.data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LootPayloadDto
{
	private LootSourceDto source;
	private List<LootItemDto> items;
	private Long totalValueGe;
}

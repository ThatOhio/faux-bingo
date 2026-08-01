package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CollectionLogPayloadDto
{
	private String entryName;
	private Integer itemId;
}

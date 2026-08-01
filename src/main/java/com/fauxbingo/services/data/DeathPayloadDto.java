package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeathPayloadDto
{
	private int regionId;
	private String killerName;
	private String killerKind;
}

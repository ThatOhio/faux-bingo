package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeathRecord
{
	private int regionId;
	private String killer;
}

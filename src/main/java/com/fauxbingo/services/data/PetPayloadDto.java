package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** Both fields may be absent, e.g. a duplicate pet fires no collection log unlock. */
@Data
@Builder
public class PetPayloadDto
{
	private String petName;
	private String sourceName;
}

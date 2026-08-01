package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LocationDto
{
	private Integer regionId;
	private Integer plane;
}

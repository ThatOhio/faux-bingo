package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** The `detection` block on a v1 event envelope. `raw` should be set on every DERIVED event. */
@Data
@Builder
public class DetectionDto
{
	private String method;
	private String confidence;
	private String raw;
}

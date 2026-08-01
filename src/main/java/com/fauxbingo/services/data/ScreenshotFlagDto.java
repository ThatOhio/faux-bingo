package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** The `screenshot` block on an envelope. Only `captured` is ever read server-side. */
@Data
@Builder
public class ScreenshotFlagDto
{
	private boolean captured;
}

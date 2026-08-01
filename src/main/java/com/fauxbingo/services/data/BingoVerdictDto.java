package com.fauxbingo.services.data;

import java.util.List;
import lombok.Data;

/**
 * `results[].bingo` on the POST /v1/events response. Always absent today per docs/v1-api.md
 * (the tile-matching engine is a stub), but the shape is kept ready for when it lands.
 */
@Data
public class BingoVerdictDto
{
	private boolean relevant;
	private boolean notify;
	private boolean requestScreenshot;
	private String tileId;
	private String tileName;
	private ProgressDto progress;
	private List<String> webhooks;
}

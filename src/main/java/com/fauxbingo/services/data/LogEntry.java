package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LogEntry
{
	private String player;
	private String type;
	private long timestamp;
	private Object data;
}

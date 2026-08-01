package com.fauxbingo.services.data;

import lombok.Data;

@Data
public class EventResultDto
{
	private String eventId;
	private String status;
	private String code;
	private String reason;
	private BingoVerdictDto bingo;
}

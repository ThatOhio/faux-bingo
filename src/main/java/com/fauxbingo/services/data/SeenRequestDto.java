package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/** Body of POST /v1/seen. Doubles as the presence heartbeat and account registration. */
@Data
@Builder
public class SeenRequestDto
{
	private String accountHash;
	private String displayName;
	private String accountType;
}

package com.fauxbingo.services.data;

import lombok.Data;

/** One RSN under a player[] entry on GET /v1/teams. This is what must be matched against clan chat. */
@Data
public class TeamAccountDto
{
	private String displayName;
	private String accountType;
	private String lastSeenAt;
}

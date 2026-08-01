package com.fauxbingo.services.data;

import java.util.List;
import lombok.Data;

/**
 * `memberName` here is a Discord nickname, hand-typed or taken from Discord. It is NOT an RSN
 * and must never be matched against one, use accounts[].displayName instead.
 */
@Data
public class TeamPlayerDto
{
	private String memberName;
	private List<TeamAccountDto> accounts;
}

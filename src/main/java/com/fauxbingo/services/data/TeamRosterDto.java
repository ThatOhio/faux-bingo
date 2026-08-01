package com.fauxbingo.services.data;

import java.util.List;
import lombok.Data;

/** One entry in `teams[]` on GET /v1/teams. Unlike TeamDto (from /v1/me) this carries the roster. */
@Data
public class TeamRosterDto
{
	private String id;
	private String name;
	private String iconUrl;
	private TeamColorsDto colors;
	private List<TeamPlayerDto> players;
}

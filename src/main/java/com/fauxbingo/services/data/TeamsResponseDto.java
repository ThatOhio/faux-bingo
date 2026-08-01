package com.fauxbingo.services.data;

import java.util.List;
import lombok.Data;

@Data
public class TeamsResponseDto
{
	private List<TeamRosterDto> teams;
}

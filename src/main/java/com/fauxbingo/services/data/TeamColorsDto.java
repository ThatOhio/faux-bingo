package com.fauxbingo.services.data;

import lombok.Data;

/** Shared four-colour palette returned by both /v1/me and /v1/teams. */
@Data
public class TeamColorsDto
{
	private String primaryBackground;
	private String secondaryBackground;
	private String textOnPrimary;
	private String textOnSecondary;
}

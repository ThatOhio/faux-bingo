package com.fauxbingo.services.data;

import lombok.Data;

/** `team` block on GET /v1/me. `iconUrl` and `discordScreenshotWebhookUrl` are null when unset. */
@Data
public class TeamDto
{
	private String id;
	private String name;
	private String iconUrl;
	private String discordScreenshotWebhookUrl;
	private TeamColorsDto colors;
}

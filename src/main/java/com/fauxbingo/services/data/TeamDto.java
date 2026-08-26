package com.fauxbingo.services.data;

import lombok.Data;

/**
 * `team` block on GET /v1/me. The response's `iconUrl` and `discordScreenshotWebhookUrl` are
 * deliberately not mapped - the plugin only ever contacts URLs it derives from the configured API
 * base or hardcodes, never one supplied by an API response.
 */
@Data
public class TeamDto
{
	private String id;
	private String name;
	private TeamColorsDto colors;
}

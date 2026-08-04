package com.fauxbingo.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the pluginVersion out of version.properties
 *
 * version.properties holds a plain, manually-bumped literal.
 * Bump the value in version.properties directly whenever a code change to the API integration
 * (envelope shape, EventsApiService, payload DTOs, etc.) warrants reporting a new version.
 */
@Slf4j
final class PluginVersion
{
	static final String VERSION = load();

	private PluginVersion()
	{
	}

	private static String load()
	{
		try (InputStream in = PluginVersion.class.getResourceAsStream("/version.properties"))
		{
			if (in == null)
			{
				return "unknown";
			}
			Properties props = new Properties();
			props.load(in);
			String version = props.getProperty("version");
			return version != null && !version.trim().isEmpty() ? version.trim() : "unknown";
		}
		catch (IOException e)
		{
			log.debug("Could not read version.properties", e);
			return "unknown";
		}
	}
}

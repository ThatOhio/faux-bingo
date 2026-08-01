package com.fauxbingo.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads the pluginVersion stamped into version.properties at build time (see build.gradle's
 * processResources), for the events envelope's pluginVersion field. RuneLite gives plugins no
 * other way to learn their own version at runtime.
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

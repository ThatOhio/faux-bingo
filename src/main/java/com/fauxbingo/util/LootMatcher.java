package com.fauxbingo.util;

import java.util.List;

public class LootMatcher
{
	public static boolean matches(String itemName, String configItem)
	{
		if (itemName == null || configItem == null)
		{
			return false;
		}

		String normalizedItem = itemName.toLowerCase().trim();
		String normalizedConfig = configItem.toLowerCase().trim();

		if (normalizedItem.equals(normalizedConfig))
		{
			return true;
		}

		// Check for plural form of config item (e.g., config="Soul rune", item="Soul runes")
		if (normalizedItem.equals(normalizedConfig + "s") ||
			normalizedItem.equals(normalizedConfig + "'s"))
		{
			return true;
		}

		// Check for singular form of config item (e.g., config="Soul runes", item="Soul rune")
		if (normalizedConfig.equals(normalizedItem + "s") ||
			normalizedConfig.equals(normalizedItem + "'s"))
		{
			return true;
		}

		// Handle "ies" / "y" pluralization (e.g., "Dragon berries" vs "Dragon berry")
		if (normalizedConfig.endsWith("y") && normalizedItem.equals(normalizedConfig.substring(0, normalizedConfig.length() - 1) + "ies"))
		{
			return true;
		}
		if (normalizedItem.endsWith("y") && normalizedConfig.equals(normalizedItem.substring(0, normalizedItem.length() - 1) + "ies"))
		{
			return true;
		}

		return false;
	}

	public static boolean matchesAny(String itemName, List<String> configItems)
	{
		if (itemName == null || configItems == null)
		{
			return false;
		}

		for (String configItem : configItems)
		{
			if (matches(itemName, configItem))
			{
				return true;
			}
		}
		return false;
	}
}

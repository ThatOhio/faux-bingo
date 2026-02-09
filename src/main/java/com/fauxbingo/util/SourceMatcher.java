package com.fauxbingo.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Case-insensitive source matching with wildcard support.
 * Used for API bingo items with source filtering.
 */
public final class SourceMatcher
{
	private SourceMatcher()
	{
	}

	/**
	 * Returns true if actualSource matches any of the pattern sources.
	 * Pattern can contain * as wildcard. Case-insensitive.
	 */
	public static boolean matchesAny(String actualSource, List<String> patternSources)
	{
		if (actualSource == null || actualSource.isEmpty() || patternSources == null)
		{
			return false;
		}

		String actual = actualSource.toLowerCase().trim();
		for (String pattern : patternSources)
		{
			if (pattern != null && !pattern.isEmpty() && matches(actual, pattern.toLowerCase().trim()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if actualSource matches the pattern.
	 * Pattern can contain * as wildcard. Case-insensitive.
	 */
	public static boolean matches(String actualSource, String pattern)
	{
		if (actualSource == null || pattern == null)
		{
			return false;
		}
		String actual = actualSource.toLowerCase().trim();
		String normalizedPattern = pattern.toLowerCase().trim();
		String regex = wildcardToRegex(normalizedPattern);
		return Pattern.matches(regex, actual);
	}

	private static String wildcardToRegex(String wildcard)
	{
		String[] parts = wildcard.split("\\*", -1);
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < parts.length; i++)
		{
			regex.append(Pattern.quote(parts[i]));
			if (i < parts.length - 1)
			{
				regex.append(".*");
			}
		}
		regex.append("$");
		return regex.toString();
	}
}

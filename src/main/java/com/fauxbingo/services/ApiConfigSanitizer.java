package com.fauxbingo.services;

import okhttp3.HttpUrl;

/**
 * Cleans the API token and base URL, which users paste in by hand.
 *
 * invisible characters - a non-breaking space, a zero-width space, a BOM.
 */
public final class ApiConfigSanitizer
{
	private ApiConfigSanitizer()
	{
	}

	public static String sanitize(String raw)
	{
		if (raw == null)
		{
			return "";
		}

		StringBuilder cleaned = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++)
		{
			char c = raw.charAt(i);
			// isWhitespace misses U+00A0 and isSpaceChar misses \t and \n, so both are needed.
			// FORMAT covers the zero-width family and the BOM.
			if (Character.isWhitespace(c) || Character.isSpaceChar(c)
				|| Character.getType(c) == Character.FORMAT || Character.isISOControl(c))
			{
				continue;
			}
			cleaned.append(c);
		}
		return cleaned.toString();
	}

	/** Sanitized with trailing slashes dropped, or "" if OkHttp cannot build a request on it. */
	public static String normalizeBaseUrl(String raw)
	{
		String cleaned = sanitize(raw).replaceAll("/+$", "");
		if (cleaned.isEmpty())
		{
			return "";
		}
		return HttpUrl.parse(cleaned) != null ? cleaned : "";
	}

	public static String bearer(String rawToken)
	{
		return "Bearer " + sanitize(rawToken);
	}
}

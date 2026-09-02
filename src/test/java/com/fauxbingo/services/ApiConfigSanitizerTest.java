package com.fauxbingo.services;

import okhttp3.Request;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ApiConfigSanitizerTest
{
	private static final String TOKEN = "fbp_exampleplayertokenvalue";

	/** trim() leaves U+00A0, which is what let a pasted token reach OkHttp and throw. */
	@Test
	public void trimLeavesNonBreakingSpaceButSanitizeRemovesIt()
	{
		String pasted = TOKEN + " ";

		assertEquals("trim() must not be trusted here", pasted, pasted.trim());
		assertEquals(TOKEN, ApiConfigSanitizer.sanitize(pasted));
	}

	@Test
	public void okHttpRejectsUnsanitizedTokenButAcceptsSanitizedOne()
	{
		String pasted = TOKEN + " ";

		try
		{
			new Request.Builder().url("https://example.com/v1/me")
				.header("Authorization", "Bearer " + pasted.trim())
				.build();
			fail("expected OkHttp to reject a header value containing U+00A0");
		}
		catch (IllegalArgumentException expected)
		{
			// The throw that used to kill the service silently.
		}

		Request ok = new Request.Builder().url("https://example.com/v1/me")
			.header("Authorization", ApiConfigSanitizer.bearer(pasted))
			.build();
		assertEquals("Bearer " + TOKEN, ok.header("Authorization"));
	}

	@Test
	public void removesTheInvisibleFamilyWhereverItAppears()
	{
		// zero-width space, zero-width non-joiner, BOM, soft hyphen, and a plain space in the middle
		String messy = "﻿fbp_ab​cd‌ef­gh ij\n";
		assertEquals("fbp_abcdefghij", ApiConfigSanitizer.sanitize(messy));
	}

	@Test
	public void sanitizeHandlesNullAndBlank()
	{
		assertEquals("", ApiConfigSanitizer.sanitize(null));
		assertEquals("", ApiConfigSanitizer.sanitize("   "));
		assertEquals("", ApiConfigSanitizer.sanitize(" ​"));
	}

	@Test
	public void normalizeBaseUrlStripsTrailingSlashesAndHiddenCharacters()
	{
		assertEquals("https://bingo.example.com",
			ApiConfigSanitizer.normalizeBaseUrl("  https://bingo.example.com/// "));
		assertEquals("https://bingo.example.com",
			ApiConfigSanitizer.normalizeBaseUrl("https://bingo.example.com "));
	}

	/** A scheme-less host looks right in the settings panel but makes Request.Builder throw. */
	@Test
	public void normalizeBaseUrlRejectsUrlsOkHttpCannotUse()
	{
		assertEquals("", ApiConfigSanitizer.normalizeBaseUrl("bingo.example.com"));
		assertEquals("", ApiConfigSanitizer.normalizeBaseUrl("ftp://bingo.example.com"));
		assertEquals("", ApiConfigSanitizer.normalizeBaseUrl("not a url"));
		assertEquals("", ApiConfigSanitizer.normalizeBaseUrl(""));
		assertEquals("", ApiConfigSanitizer.normalizeBaseUrl(null));
	}

	@Test
	public void normalizeBaseUrlAcceptsPlainHttpForLocalDevServers()
	{
		assertEquals("http://localhost:3000", ApiConfigSanitizer.normalizeBaseUrl("http://localhost:3000/"));
	}
}

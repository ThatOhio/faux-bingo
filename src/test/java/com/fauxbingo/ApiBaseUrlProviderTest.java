package com.fauxbingo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ApiBaseUrlProviderTest
{
	private static final String DEFAULT_URL = "https://fauxbingo.com";

	@Mock
	private FauxBingoConfig config;

	private final FauxBingoPlugin plugin = new FauxBingoPlugin();

	@Test
	public void blankFallsBackToTheDefault()
	{
		when(config.apiBaseUrl()).thenReturn("");
		assertEquals(DEFAULT_URL, plugin.provideApiBaseUrl(config));

		when(config.apiBaseUrl()).thenReturn("   ");
		assertEquals(DEFAULT_URL, plugin.provideApiBaseUrl(config));

		when(config.apiBaseUrl()).thenReturn(null);
		assertEquals(DEFAULT_URL, plugin.provideApiBaseUrl(config));
	}

	@Test
	public void configuredUrlIsNormalized()
	{
		when(config.apiBaseUrl()).thenReturn(" https://bingo.example.com/ ");
		assertEquals("https://bingo.example.com", plugin.provideApiBaseUrl(config));
	}

	/** Falling back to the default here would send a beta tester's events to production. */
	@Test
	public void unusableUrlDisablesRatherThanFallingBackToProduction()
	{
		when(config.apiBaseUrl()).thenReturn("bingo.example.com");
		assertEquals("", plugin.provideApiBaseUrl(config));

		when(config.apiBaseUrl()).thenReturn("htps://bingo.example.com");
		assertEquals("", plugin.provideApiBaseUrl(config));
	}

	@Test
	public void hiddenCharactersInAnOtherwiseValidUrlAreStripped()
	{
		when(config.apiBaseUrl()).thenReturn("﻿https://bingo.example.com ");
		assertEquals("https://bingo.example.com", plugin.provideApiBaseUrl(config));
	}
}

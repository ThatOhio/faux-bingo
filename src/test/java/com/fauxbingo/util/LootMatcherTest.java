package com.fauxbingo.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;

public class LootMatcherTest
{
	@Test
	public void testExactMatch()
	{
		assertTrue(LootMatcher.matches("Soul rune", "Soul rune"));
		assertTrue(LootMatcher.matches("soul rune", "SOUL RUNE"));
		assertTrue(LootMatcher.matches("  Soul rune  ", "soul rune"));
	}

	@Test
	public void testPluralWithS()
	{
		// Config is singular, item is plural
		assertTrue(LootMatcher.matches("Soul runes", "Soul rune"));
		// Config is plural, item is singular
		assertTrue(LootMatcher.matches("Soul rune", "Soul runes"));
	}

	@Test
	public void testPluralWithApostropheS()
	{
		// Config is singular, item is plural
		assertTrue(LootMatcher.matches("Dragon's bones", "Dragon's bone"));
		// Config is plural, item is singular
		assertTrue(LootMatcher.matches("Dragon's bone", "Dragon's bones"));
	}

	@Test
	public void testPluralWithIES()
	{
		// Config is singular, item is plural
		assertTrue(LootMatcher.matches("Dragon berries", "Dragon berry"));
		// Config is plural, item is singular
		assertTrue(LootMatcher.matches("Dragon berry", "Dragon berries"));
	}

	@Test
	public void testPluralWithES()
	{
		assertTrue(LootMatcher.matches("Bosses", "Boss"));
		assertTrue(LootMatcher.matches("Box", "Boxes"));
	}

	@Test
	public void testPluralWithVES()
	{
		assertTrue(LootMatcher.matches("Knife", "Knives"));
		assertTrue(LootMatcher.matches("Leaf", "Leaves"));
	}

	@Test
	public void testPossessivePluralEndingInS()
	{
		assertTrue(LootMatcher.matches("Scurrius' spine", "Scurrius spine"));
		assertTrue(LootMatcher.matches("Scurrius spine", "Scurrius' spine"));
	}

	@Test
	public void testFuzzyMatch()
	{
		assertTrue(LootMatcher.matches("Blood runes", "Blod runes"));
	}

	@Test
	public void testWildcardMatch()
	{
		assertTrue(LootMatcher.matches("Blood rune", "*rune"));
		assertTrue(LootMatcher.matches("Blood rune", "blood*"));
		assertTrue(LootMatcher.matches("Blood rune", "*oo*"));
		assertFalse(LootMatcher.matches("Dragon bones", "*rune"));
	}

	@Test
	public void testNoMatch()
	{
		assertFalse(LootMatcher.matches("Soul rune", "Blood rune"));
		assertFalse(LootMatcher.matches("Dragon bone", "Dragon stone"));
	}

	@Test
	public void testMatchesAny()
	{
		List<String> configItems = Arrays.asList("Soul rune", "Dragon bone", "Twisted bowz");
		assertTrue(LootMatcher.matchesAny("Soul runes", configItems));
		assertTrue(LootMatcher.matchesAny("Dragon bone", configItems));
		assertTrue(LootMatcher.matchesAny("Twisted bow", configItems));
		assertFalse(LootMatcher.matchesAny("Blood rune", configItems));
	}
}

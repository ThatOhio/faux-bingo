package com.fauxbingo.services.data;

/**
 * EXACT means the data came from a real game event or item container: item IDs and quantities
 * are trustworthy. DERIVED means it was parsed out of chat text or a notification script.
 */
public enum Confidence
{
	EXACT,
	DERIVED
}

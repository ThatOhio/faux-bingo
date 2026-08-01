package com.fauxbingo.services;

import com.fauxbingo.services.data.MergedDropEvent;

/**
 * Receives the one merged/authoritative event per physical drop, resolved by
 * DropCorrelationService. This is the seam a real v1 API HTTP client plugs into later without
 * touching the correlation engine. Nothing implements this except a logging no-op today.
 */
public interface EventEnvelopeSink
{
	void accept(MergedDropEvent event);
}

package com.fauxbingo.services;

import com.fauxbingo.services.data.MergedDropEvent;

/**
 * Receives the one merged/authoritative event per physical drop, resolved by
 * DropCorrelationService. EventsApiService implements this and posts to the v1 events endpoint;
 * the seam keeps the correlation engine from needing to know about HTTP transport at all.
 */
public interface EventEnvelopeSink
{
	void accept(MergedDropEvent event);
}

package com.fauxbingo.services;

import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.MergedDropEvent;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Placeholder until the real v1 API client exists. Just proves the correlation engine resolved
 * something worth sending; swap the FauxBingoPlugin binding to a real HTTP client later.
 */
@Slf4j
@Singleton
public class LoggingEventEnvelopeSink implements EventEnvelopeSink
{
	@Override
	public void accept(MergedDropEvent event)
	{
		DropSignal primary = event.getPrimarySignal();
		log.debug("Resolved drop event (not sent anywhere yet): type={} detection={} source={} items={} petName={} contributingSignals={}",
			event.getType(),
			primary != null ? primary.getDetectionMethod() : null,
			primary != null ? primary.getSourceName() : null,
			primary != null ? primary.getItems() : null,
			event.getPetName(),
			event.getContributingSignals() != null ? event.getContributingSignals().size() : 0);
	}
}

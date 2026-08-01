package com.fauxbingo.services.data;

import java.awt.image.BufferedImage;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * The single resolved event for one physical drop, folded together from every DropSignal that
 * matched within DropCorrelationService's correlation window. Handed to EventEnvelopeSink, whose
 * live implementation (EventsApiService) transports it via POST /v1/events.
 */
@Data
@Builder
public class MergedDropEvent
{
	private DropType type;
	/** Correlates every signal in this group. New per physical drop, per DropCorrelationService. */
	private String dropGroupId;
	private DropSignal primarySignal;
	private List<DropSignal> contributingSignals;
	/** Only set when a PET signal was corroborated by a COLLECTION_LOG signal in the same group. */
	private String petName;
	private String sourceNameGuess;
	/** Chat-reported total, used only when the primary signal's own priced total was missing/zero. */
	private Long corroboratedValueGe;
	private BufferedImage screenshot;
}

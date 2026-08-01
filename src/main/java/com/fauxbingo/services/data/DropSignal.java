package com.fauxbingo.services.data;

import java.awt.image.BufferedImage;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * One handler's report of a detection, built and handed to DropCorrelationService instead of
 * going straight to EventsApiService. Same-method dedup (e.g. LootEventHandler's
 * TILE_SCAN/SERVER pairing) still happens before this is built, so each handler produces at most
 * one signal per real detection; DropCorrelationService only merges across handlers.
 *
 * The screenshot is captured immediately at detection time regardless of whether this signal
 * ends up winning the group, since the moment can't be recaptured once the correlation window
 * closes. Only the winning signal's image is kept after resolution.
 */
@Data
@Builder
public class DropSignal
{
	private DetectionMethod detectionMethod;
	/** Original chat line, present on every DERIVED signal for future server-side reparsing. */
	private String raw;
	private SourceKind sourceKind;
	private String sourceName;
	private Integer npcId;
	private Integer combatLevel;
	private Integer killCount;
	/** Raid difficulty (COX/COX_CM/TOB/TOB_SM/TOB_HM/TOA_ENTRY/TOA_NORMAL/TOA_EXPERT). LOOT only. */
	private String variant;
	private Integer regionId;
	private Integer plane;
	private List<DropItem> items;
	private Long totalValueGe;
	/** Best guess at a pet's source NPC/player, from InteractionTrackingService. PET signals only. */
	private String sourceNameGuess;
	private BufferedImage screenshot;
}

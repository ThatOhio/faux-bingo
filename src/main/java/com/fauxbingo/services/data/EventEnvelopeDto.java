package com.fauxbingo.services.data;

import lombok.Builder;
import lombok.Data;

/**
 * Wire shape for one entry in the POST /v1/events array (docs/v1-api.md). `payload` is one of
 * LootPayloadDto/CollectionLogPayloadDto/PetPayloadDto/DeathPayloadDto depending on `type`, Gson
 * serialises whichever object is set.
 */
@Data
@Builder
public class EventEnvelopeDto
{
	private String eventId;
	private int schemaVersion;
	private String pluginVersion;
	private String occurredAt;
	private int sequence;
	private boolean replay;
	private String dropGroupId;
	private ActorDto actor;
	private String type;
	private DetectionDto detection;
	private Object payload;
	private ScreenshotFlagDto screenshot;
}

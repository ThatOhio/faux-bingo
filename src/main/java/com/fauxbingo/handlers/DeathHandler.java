package com.fauxbingo.handlers;

import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.data.DeathRecord;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.coords.WorldPoint;

/**
 * Handles player death. Logs death to LogService (when API enabled) with region and killer if known.
 * Uses InteractionTrackingService for killer detection.
 */
@Slf4j
public class DeathHandler
{
	private final Client client;
	private final LogService logService;
	private final InteractionTrackingService interactionTrackingService;

	public DeathHandler(Client client, LogService logService)
	{
		this(client, logService, null);
	}

	public DeathHandler(Client client, LogService logService, InteractionTrackingService interactionTrackingService)
	{
		this.client = client;
		this.logService = logService;
		this.interactionTrackingService = interactionTrackingService;
	}

	public EventHandler<ActorDeath> createActorDeathHandler()
	{
		return new EventHandler<ActorDeath>()
		{
			@Override
			public void handle(ActorDeath event)
			{
				handleActorDeath(event);
			}

			@Override
			public Class<ActorDeath> getEventType()
			{
				return ActorDeath.class;
			}
		};
	}

	public void resetState()
	{
		// InteractionTrackingService handles its own state; nothing to clear
	}

	private void handleActorDeath(ActorDeath event)
	{
		Actor local = client.getLocalPlayer();
		if (local == null || local != event.getActor())
		{
			return;
		}

		int regionId = 0;
		String killer = null;

		try
		{
			WorldPoint loc = local.getWorldLocation();
			if (loc != null)
			{
				regionId = loc.getRegionID();
			}
		}
		catch (Exception e)
		{
			log.debug("Could not get death location", e);
		}

		Actor candidate = (interactionTrackingService != null) ? interactionTrackingService.getLastTarget() : null;
		if (candidate == null)
		{
			candidate = local.getInteracting();
		}
		if (candidate != null && candidate != local)
		{
			String name = candidate.getName();
			if (name != null && !name.isEmpty())
			{
				killer = name;
			}
		}

		DeathRecord record = DeathRecord.builder()
			.regionId(regionId)
			.killer(killer)
			.build();

		logService.log("DEATH", record);
	}
}

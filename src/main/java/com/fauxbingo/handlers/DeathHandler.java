package com.fauxbingo.handlers;

import com.fauxbingo.services.EventsApiService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.data.DeathSignal;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.Subscribe;

/**
 * Handles player death. Submits a DEATH envelope to EventsApiService (batched, per the v1
 * contract - there's no dedicated death endpoint any more). Uses InteractionTrackingService for
 * killer detection.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DeathHandler
{
	private final Client client;
	private final EventsApiService eventsApiService;
	private final InteractionTrackingService interactionTrackingService;

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor local = client.getLocalPlayer();
		if (local == null || local != event.getActor())
		{
			return;
		}

		int regionId = 0;
		String killer = null;
		String killerKind = "UNKNOWN";

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
				killerKind = candidate instanceof Player ? "PLAYER" : candidate instanceof NPC ? "NPC" : "UNKNOWN";
			}
		}

		DeathSignal signal = DeathSignal.builder()
			.regionId(regionId)
			.killerName(killer)
			.killerKind(killerKind)
			.build();

		eventsApiService.submitDeath(signal);
	}
}

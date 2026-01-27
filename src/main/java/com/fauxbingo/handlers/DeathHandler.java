package com.fauxbingo.handlers;

import com.fauxbingo.services.LogService;
import com.fauxbingo.services.data.DeathRecord;
import java.lang.ref.WeakReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.coords.WorldPoint;

/**
 * Handles player death. Logs death to LogService (when API enabled) with region and killer if known.
 * Tracks last interacted combat target (InteractingChanged) to improve killer detection.
 */
@Slf4j
public class DeathHandler
{
	private final Client client;
	private final LogService logService;

	private WeakReference<Actor> lastTarget = new WeakReference<>(null);

	public DeathHandler(Client client, LogService logService)
	{
		this.client = client;
		this.logService = logService;
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

	public EventHandler<InteractingChanged> createInteractingChangedHandler()
	{
		return new EventHandler<InteractingChanged>()
		{
			@Override
			public void handle(InteractingChanged event)
			{
				handleInteractingChanged(event);
			}

			@Override
			public Class<InteractingChanged> getEventType()
			{
				return InteractingChanged.class;
			}
		};
	}

	public void resetState()
	{
		lastTarget = new WeakReference<>(null);
	}

	private void handleInteractingChanged(InteractingChanged event)
	{
		Actor local = client.getLocalPlayer();
		if (local == null || event.getSource() != local)
		{
			return;
		}
		Actor target = event.getTarget();
		if (target == null || target == local || getCombatLevel(target) <= 0)
		{
			return;
		}
		lastTarget = new WeakReference<>(target);
	}

	private void handleActorDeath(ActorDeath event)
	{
		Actor local = client.getLocalPlayer();
		if (local == null || local != event.getActor())
		{
			Actor dead = event.getActor();
			if (dead != null && dead == lastTarget.get())
			{
				lastTarget = new WeakReference<>(null);
			}
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

		Actor candidate = lastTarget.get();
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
		lastTarget = new WeakReference<>(null);

		DeathRecord record = DeathRecord.builder()
			.regionId(regionId)
			.killer(killer)
			.build();

		logService.log("DEATH", record);
	}

	private static int getCombatLevel(Actor a)
	{
		if (a instanceof Player)
		{
			return ((Player) a).getCombatLevel();
		}
		if (a instanceof NPC)
		{
			var comp = ((NPC) a).getComposition();
			return comp != null ? comp.getCombatLevel() : 0;
		}
		return 0;
	}
}

package com.fauxbingo.services;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks entities the player interacts with in a rolling 10-second window.
 * Used by DeathHandler for killer detection.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class InteractionTrackingService
{
	private static final long WINDOW_MS = 10_000;

	private final Client client;
	private final ConcurrentLinkedQueue<InteractionEntry> interactions = new ConcurrentLinkedQueue<>();

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
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
		addInteraction(target);
	}

	/**
	 * Returns names of entities the player has interacted with in the last 10 seconds.
	 * Called by PetChatHandler to guess a pet's source NPC/player, since the pet chat
	 * message never names its source.
	 */
	public List<String> getRecentInteractionNames()
	{
		pruneStale();
		List<String> names = new ArrayList<>();
		for (InteractionEntry entry : interactions)
		{
			String name = entry.getActorName();
			if (name != null && !name.isEmpty())
			{
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * Returns the most recent interaction target, or null.
	 * Used by DeathHandler for killer detection.
	 */
	public Actor getLastTarget()
	{
		pruneStale();
		InteractionEntry last = null;
		for (InteractionEntry entry : interactions)
		{
			if (last == null || entry.timestamp > last.timestamp)
			{
				last = entry;
			}
		}
		return last != null ? last.actor : null;
	}

	private void addInteraction(Actor actor)
	{
		long now = System.currentTimeMillis();
		interactions.add(new InteractionEntry(actor, now));
		pruneStale();
	}

	private void pruneStale()
	{
		long cutoff = System.currentTimeMillis() - WINDOW_MS;
		InteractionEntry entry;
		while ((entry = interactions.peek()) != null && entry.timestamp < cutoff)
		{
			interactions.poll();
		}
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

	private static class InteractionEntry
	{
		private final Actor actor;
		private final long timestamp;

		InteractionEntry(Actor actor, long timestamp)
		{
			this.actor = actor;
			this.timestamp = timestamp;
		}

		String getActorName()
		{
			return actor != null ? actor.getName() : null;
		}
	}
}

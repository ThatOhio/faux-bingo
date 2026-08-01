package com.fauxbingo.handlers;

import com.fauxbingo.services.EventsApiService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.data.DeathSignal;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class DeathHandlerTest
{
	@Mock
	private Client client;

	@Mock
	private EventsApiService eventsApiService;

	@Mock
	private Player localPlayer;

	@Mock
	private Actor killer;

	@Mock
	private Player killerTarget;

	@Mock
	private NPC npcKiller;

	@Mock
	private InteractionTrackingService interactionTrackingService;

	private DeathHandler deathHandler;

	@Before
	public void before()
	{
		deathHandler = new DeathHandler(client, eventsApiService, interactionTrackingService);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
	}

	@Test
	public void submitsDeathWithRegionAndKiller()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(killer);
		when(killer.getName()).thenReturn("Elvarg");

		ActorDeath event = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(event);

		ArgumentCaptor<DeathSignal> cap = ArgumentCaptor.forClass(DeathSignal.class);
		verify(eventsApiService).submitDeath(cap.capture());
		DeathSignal signal = cap.getValue();
		assertEquals(12893, signal.getRegionId());
		assertEquals("Elvarg", signal.getKillerName());
		assertEquals("UNKNOWN", signal.getKillerKind());
	}

	@Test
	public void submitsDeathWithRegionOnlyWhenNoKiller()
	{
		WorldPoint loc = WorldPoint.fromRegion(13100, 16, 16, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(null);

		ActorDeath event = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(event);

		ArgumentCaptor<DeathSignal> cap = ArgumentCaptor.forClass(DeathSignal.class);
		verify(eventsApiService).submitDeath(cap.capture());
		DeathSignal signal = cap.getValue();
		assertEquals(13100, signal.getRegionId());
		assertNull(signal.getKillerName());
		assertEquals("UNKNOWN", signal.getKillerKind());
	}

	@Test
	public void ignoresOtherPlayerDeath()
	{
		Player other = mock(Player.class);
		ActorDeath event = new ActorDeath(other);

		deathHandler.onActorDeath(event);

		verify(eventsApiService, never()).submitDeath(any());
	}

	@Test
	public void usesLastTargetAsKillerWhenInteractingCleared()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(killerTarget.getName()).thenReturn("Elvarg");
		when(interactionTrackingService.getLastTarget()).thenReturn(killerTarget);

		ActorDeath deathEvent = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(deathEvent);

		ArgumentCaptor<DeathSignal> cap = ArgumentCaptor.forClass(DeathSignal.class);
		verify(eventsApiService).submitDeath(cap.capture());
		assertEquals("Elvarg", cap.getValue().getKillerName());
		assertEquals("PLAYER", cap.getValue().getKillerKind());
	}

	@Test
	public void npcKillerReportsNpcKind()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(npcKiller);
		when(npcKiller.getName()).thenReturn("Vorkath");

		ActorDeath deathEvent = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(deathEvent);

		ArgumentCaptor<DeathSignal> cap = ArgumentCaptor.forClass(DeathSignal.class);
		verify(eventsApiService).submitDeath(cap.capture());
		assertEquals("Vorkath", cap.getValue().getKillerName());
		assertEquals("NPC", cap.getValue().getKillerKind());
	}
}

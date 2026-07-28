package com.fauxbingo.handlers;

import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.data.DeathRecord;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.InteractingChanged;
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
	private LogService logService;

	@Mock
	private Player localPlayer;

	@Mock
	private Actor killer;

	@Mock
	private Player killerTarget;

	@Mock
	private InteractionTrackingService interactionTrackingService;

	private DeathHandler deathHandler;

	@Before
	public void before()
	{
		deathHandler = new DeathHandler(client, logService, interactionTrackingService);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
	}

	@Test
	public void logsDeathWithRegionAndKiller()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(killer);
		when(killer.getName()).thenReturn("Elvarg");

		ActorDeath event = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(event);

		ArgumentCaptor<DeathRecord> cap = ArgumentCaptor.forClass(DeathRecord.class);
		verify(logService).log(eq("DEATH"), cap.capture());
		DeathRecord rec = cap.getValue();
		assertEquals(12893, rec.getRegionId());
		assertEquals("Elvarg", rec.getKiller());
	}

	@Test
	public void logsDeathWithRegionOnlyWhenNoKiller()
	{
		WorldPoint loc = WorldPoint.fromRegion(13100, 16, 16, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(null);

		ActorDeath event = new ActorDeath(localPlayer);

		deathHandler.onActorDeath(event);

		ArgumentCaptor<DeathRecord> cap = ArgumentCaptor.forClass(DeathRecord.class);
		verify(logService).log(eq("DEATH"), cap.capture());
		DeathRecord rec = cap.getValue();
		assertEquals(13100, rec.getRegionId());
		assertNull(rec.getKiller());
	}

	@Test
	public void ignoresOtherPlayerDeath()
	{
		Player other = mock(Player.class);
		ActorDeath event = new ActorDeath(other);

		deathHandler.onActorDeath(event);

		verify(logService, never()).log(anyString(), any());
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

		ArgumentCaptor<DeathRecord> cap = ArgumentCaptor.forClass(DeathRecord.class);
		verify(logService).log(eq("DEATH"), cap.capture());
		assertEquals("Elvarg", cap.getValue().getKiller());
	}
}

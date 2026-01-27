package com.fauxbingo.handlers;

import com.fauxbingo.handlers.EventHandler;
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

	private DeathHandler deathHandler;
	private EventHandler<ActorDeath> actorDeathHandler;

	@Before
	public void before()
	{
		deathHandler = new DeathHandler(client, logService);
		actorDeathHandler = deathHandler.createActorDeathHandler();
		when(client.getLocalPlayer()).thenReturn(localPlayer);
	}

	@Test
	public void logsDeathWithRegionAndKiller()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(killer);
		when(killer.getName()).thenReturn("Elvarg");

		ActorDeath event = mock(ActorDeath.class);
		when(event.getActor()).thenReturn(localPlayer);

		actorDeathHandler.handle(event);

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

		ActorDeath event = mock(ActorDeath.class);
		when(event.getActor()).thenReturn(localPlayer);

		actorDeathHandler.handle(event);

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
		ActorDeath event = mock(ActorDeath.class);
		when(event.getActor()).thenReturn(other);

		actorDeathHandler.handle(event);

		verify(logService, never()).log(anyString(), any());
	}

	@Test
	public void usesLastTargetAsKillerWhenInteractingCleared()
	{
		WorldPoint loc = WorldPoint.fromRegion(12893, 32, 32, 0);
		when(localPlayer.getWorldLocation()).thenReturn(loc);
		when(localPlayer.getInteracting()).thenReturn(null);
		when(killerTarget.getCombatLevel()).thenReturn(126);
		when(killerTarget.getName()).thenReturn("Elvarg");

		EventHandler<InteractingChanged> interactingHandler = deathHandler.createInteractingChangedHandler();
		InteractingChanged icEvent = mock(InteractingChanged.class);
		when(icEvent.getSource()).thenReturn(localPlayer);
		when(icEvent.getTarget()).thenReturn(killerTarget);
		interactingHandler.handle(icEvent);

		ActorDeath deathEvent = mock(ActorDeath.class);
		when(deathEvent.getActor()).thenReturn(localPlayer);

		actorDeathHandler.handle(deathEvent);

		ArgumentCaptor<DeathRecord> cap = ArgumentCaptor.forClass(DeathRecord.class);
		verify(logService).log(eq("DEATH"), cap.capture());
		assertEquals("Elvarg", cap.getValue().getKiller());
	}
}

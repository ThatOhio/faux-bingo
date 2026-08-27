package com.fauxbingo;

import com.fauxbingo.handlers.CollectionLogHandler;
import com.fauxbingo.handlers.RaidLootHandler;
import com.fauxbingo.services.EventsApiService;
import com.fauxbingo.services.MeService;
import com.fauxbingo.services.PresenceService;
import com.fauxbingo.services.TeamIconService;
import com.fauxbingo.trackers.XpTracker;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class LoginDetectionTest
{
    @Mock
    private Client client;

    @Mock
    private FauxBingoConfig config;

    @Mock
    private MeService meService;

    @Mock
    private PresenceService presenceService;

    @Mock
    private EventsApiService eventsApiService;

    @Mock
    private TeamIconService teamIconService;

    @Mock
    private CollectionLogHandler collectionLogHandler;

    @Mock
    private RaidLootHandler raidLootHandler;

    @Mock
    private XpTracker xpTracker;

    @Mock
    private ClientThread clientThread;

    private FauxBingoPlugin plugin;

    @Before
    public void before() throws Exception
    {
        plugin = new FauxBingoPlugin();

        // The plugin's own subscribers reach these collaborators, which Guice supplies in production.
        setField(plugin, "client", client);
        setField(plugin, "clientThread", clientThread);
        setField(plugin, "config", config);
        setField(plugin, "meService", meService);
        setField(plugin, "presenceService", presenceService);
        setField(plugin, "eventsApiService", eventsApiService);
        setField(plugin, "teamIconService", teamIconService);
        setField(plugin, "collectionLogHandler", collectionLogHandler);
        setField(plugin, "raidLootHandler", raidLootHandler);
        setField(plugin, "xpTracker", xpTracker);

        lenient().when(config.enableBingoApi()).thenReturn(true);

        // Match ClientThread.invoke's inline behaviour on the client thread.
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(clientThread).invoke(any(Runnable.class));
    }

    private void setField(Object obj, String fieldName, Object value) throws Exception
    {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @Test
    public void testOnGameStateChanged_LoggedIn_CallsOnLogin()
    {
        GameStateChanged event = new GameStateChanged();
        event.setGameState(GameState.LOGGED_IN);

        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        Player localPlayer = mock(Player.class);
        when(localPlayer.getName()).thenReturn("TestUser");
        when(client.getLocalPlayer()).thenReturn(localPlayer);

        plugin.onGameStateChanged(event);

        verify(meService).onLogin("TestUser");
        verify(presenceService).onLogin("TestUser");
    }

    @Test
    public void testOnUsernameChanged_CallsOnLogin()
    {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        Player localPlayer = mock(Player.class);
        when(localPlayer.getName()).thenReturn("TestUser");
        when(client.getLocalPlayer()).thenReturn(localPlayer);

        UsernameChanged event = new UsernameChanged();
        plugin.onUsernameChanged(event);

        verify(meService).onLogin("TestUser");
        verify(presenceService).onLogin("TestUser");
    }

    @Test
    public void testOnConfigChanged_CallsOnLogin()
    {
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        Player localPlayer = mock(Player.class);
        when(localPlayer.getName()).thenReturn("TestUser");
        when(client.getLocalPlayer()).thenReturn(localPlayer);

        ConfigChanged event = new ConfigChanged();
        event.setGroup("fauxbingo");
        event.setKey("enableBingoApi");

        plugin.onConfigChanged(event);

        verify(meService).onLogin("TestUser");
        verify(presenceService).onLogin("TestUser");
        verify(meService).refresh("TestUser");
    }
}

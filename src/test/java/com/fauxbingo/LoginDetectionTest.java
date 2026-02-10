package com.fauxbingo;

import com.fauxbingo.services.BingoConfigService;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class LoginDetectionTest
{
    @Mock
    private Client client;

    @Mock
    private FauxBingoConfig config;

    @Mock
    private BingoConfigService bingoConfigService;

    @Mock
    private ClientThread clientThread;

    @Mock
    private OverlayManager overlayManager;

    private FauxBingoPlugin plugin;

    @Before
    public void before() throws Exception
    {
        plugin = new FauxBingoPlugin();

        setField(plugin, "client", client);
        setField(plugin, "config", config);
        setField(plugin, "bingoConfigService", bingoConfigService);
        setField(plugin, "clientThread", clientThread);
        setField(plugin, "overlayManager", overlayManager);

        lenient().when(config.enableBingoApi()).thenReturn(true);
        
        // Mock invokeLater to execute immediately
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));
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

        verify(bingoConfigService).onLogin("TestUser");
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

        verify(bingoConfigService).onLogin("TestUser");
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

        verify(bingoConfigService).onLogin("TestUser");
    }
}

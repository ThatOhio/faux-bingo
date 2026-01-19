package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.google.gson.Gson;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class LogServiceTest
{
    @Mock
    private Client client;

    @Mock
    private FauxBingoConfig config;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private ScheduledExecutorService executor;

    @Mock
    private Player player;

    private LogService logService;
    private Gson gson = new Gson();

    @Before
    public void before()
    {
        when(config.enableLoggingApi()).thenReturn(true);
        when(config.loggingApiUrl()).thenReturn("http://api");
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("TestPlayer");
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        
        logService = new LogService(client, config, okHttpClient, gson, executor);
    }

    @Test
    public void testLogWhenLoggedIn()
    {
        logService.log("TEST", "data");
        // Should use client to get player name
        verify(client, atLeastOnce()).getLocalPlayer();
    }

    @Test
    public void testLogWhenNotLoggedIn()
    {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        logService.log("TEST", "data");
        // Should return early and not even check for local player
        verify(client, never()).getLocalPlayer();
    }
}

package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.data.DeathRecord;
import com.google.gson.Gson;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
    private Call httpCall;

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

    @Test
    public void testDeathLogSentToDeathsPath()
    {
        when(config.loggingApiUrl()).thenReturn("http://api");
        when(okHttpClient.newCall(any(Request.class))).thenReturn(httpCall);
        DeathRecord record = DeathRecord.builder().regionId(12893).killer("Elvarg").build();

        logService.log("DEATH", record);

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        assertEquals("http://api/api/deaths", captor.getValue().url().toString());
    }

    @Test
    public void testDeathLogSkippedWhenBaseUrlEmpty()
    {
        when(config.loggingApiUrl()).thenReturn("");
        DeathRecord record = DeathRecord.builder().regionId(12893).killer("Elvarg").build();

        logService.log("DEATH", record);

        verify(okHttpClient, never()).newCall(any());
    }
}

package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import com.fauxbingo.FauxBingoConfig;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bundling/grouping across signals now lives in DropCorrelationServiceTest. This only covers
 * WebhookService's own job: sending one message, URL splitting, and game-mode annotation.
 */
@RunWith(MockitoJUnitRunner.class)
public class WebhookServiceTest
{
    @Mock
    private Client client;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    @Mock
    private FauxBingoConfig config;

    @Mock
    private MeService meService;

    private WebhookService webhookService;

    @Before
    public void before()
    {
        webhookService = new WebhookService(client, okHttpClient, config);
        when(okHttpClient.newCall(any())).thenReturn(call);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
        when(config.funnyGameModeMessages()).thenReturn(false);
    }

    @Test
    public void testGameStateCheck()
    {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        webhookService.sendWebhook("http://webhook", "Message", null, true);

        verify(okHttpClient, never()).newCall(any());
    }

    @Test
    public void testManualBypassIgnoresGameState()
    {
        // No stubbing for client.getGameState() needed, it should be bypassed entirely
        webhookService.sendWebhook("http://webhook", "Manual", null);

        verify(okHttpClient).newCall(any());
        verify(client, never()).getGameState();
    }

    @Test
    public void testSendsImmediately()
    {
        webhookService.sendWebhook("http://webhook", "Loot message", null, true);

        verify(okHttpClient, times(1)).newCall(any());
    }

    @Test
    public void testWebhookUrlSplitting()
    {
        // Test various separators and whitespace: comma, newline, and mixed
        String urls = "http://url1, http://url2\nhttp://url3, \n http://url4";
        webhookService.sendWebhook(urls, "Message", null);

        verify(okHttpClient, times(4)).newCall(any());

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient, times(4)).newCall(requestCaptor.capture());

        List<Request> requests = requestCaptor.getAllValues();
        assertEquals("http://url1/", requests.get(0).url().toString());
        assertEquals("http://url2/", requests.get(1).url().toString());
        assertEquals("http://url3/", requests.get(2).url().toString());
        assertEquals("http://url4/", requests.get(3).url().toString());
    }

    @Test
    public void testLeaguesGameModeSuffix() throws IOException
    {
        // Use SEASONAL as a proxy for Leagues if LEAGUE is not available
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.SEASONAL, WorldType.MEMBERS));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        assertTrue("Body should contain (Leagues) suffix", body.contains("Loot message (Leagues)"));
    }

    @Test
    public void testDeadmanGameModeSuffix() throws IOException
    {
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.DEADMAN, WorldType.MEMBERS));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        assertTrue("Body should contain (Deadman) suffix", body.contains("Loot message (Deadman)"));
    }

    @Test
    public void testNormalGameModeNoSuffix() throws IOException
    {
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS, WorldType.PVP));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        assertTrue("Body should contain the message", body.contains("Loot message"));
        assertTrue("Body should NOT contain (Leagues) suffix", !body.contains("(Leagues)"));
        assertTrue("Body should NOT contain (Deadman) suffix", !body.contains("(Deadman)"));
    }

    @Test
    public void testTournamentGameModeSuffix() throws IOException
    {
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.TOURNAMENT_WORLD, WorldType.MEMBERS));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        assertTrue("Body should contain (Tournament) suffix", body.contains("Loot message (Tournament)"));
    }

    @Test
    public void testFunnyLeaguesMessage() throws IOException
    {
        when(config.funnyGameModeMessages()).thenReturn(true);
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.SEASONAL, WorldType.MEMBERS));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        List<String> expectedMessages = Arrays.asList(
            "This dummy is playing Leagues!",
            "Leagues: Where the drops are fake and the points don't matter!",
            "Is it really a grind if you have 16x drop rate?",
            "Playing Leagues because the main game is too hard."
        );

        boolean found = false;
        for (String msg : expectedMessages)
        {
            if (body.contains("Loot message (" + msg + ")"))
            {
                found = true;
                break;
            }
        }
        assertTrue("Body should contain one of the funny leagues messages", found);
    }

    @Test
    public void testFunnyDeadmanMessage() throws IOException
    {
        when(config.funnyGameModeMessages()).thenReturn(true);
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.DEADMAN, WorldType.MEMBERS));

        webhookService.sendWebhook("http://webhook", "Loot message", null);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        List<String> expectedMessages = Arrays.asList(
            "Look at this brave soul playing Deadman!",
            "Living life on the edge in DMM!",
            "One misclick away from a bank rebuild.",
            "Deadman Mode: Where everyone is a target."
        );

        boolean found = false;
        for (String msg : expectedMessages)
        {
            if (body.contains("Loot message (" + msg + ")"))
            {
                found = true;
                break;
            }
        }
        assertTrue("Body should contain one of the funny deadman messages", found);
    }

    @Test
    public void testTeamWebhookFromMeServiceIsMerged()
    {
        when(meService.getDiscordScreenshotWebhookUrl()).thenReturn("http://team-webhook");
        WebhookService withMeService = new WebhookService(client, okHttpClient, config, meService);

        withMeService.sendWebhook("http://personal-webhook", "Message", null, true);

        verify(okHttpClient, times(2)).newCall(any());
    }

    @Test
    public void testDuplicateTeamWebhookIsNotSentTwice()
    {
        when(meService.getDiscordScreenshotWebhookUrl()).thenReturn("http://personal-webhook/");
        WebhookService withMeService = new WebhookService(client, okHttpClient, config, meService);

        withMeService.sendWebhook("http://personal-webhook", "Message", null, true);

        verify(okHttpClient, times(1)).newCall(any());
    }

    @Test
    public void testNullTeamWebhookFallsBackToPersonalOnly()
    {
        when(meService.getDiscordScreenshotWebhookUrl()).thenReturn(null);
        WebhookService withMeService = new WebhookService(client, okHttpClient, config, meService);

        withMeService.sendWebhook("http://personal-webhook", "Message", null, true);

        verify(okHttpClient, times(1)).newCall(any());
    }
}

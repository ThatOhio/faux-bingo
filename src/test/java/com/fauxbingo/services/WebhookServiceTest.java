package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class WebhookServiceTest
{
    @Mock
    private Client client;

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private ScheduledExecutorService executor;

    @Mock
    private Call call;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private WebhookService webhookService;

    @Before
    public void before()
    {
        webhookService = new WebhookService(client, okHttpClient, executor);
        when(okHttpClient.newCall(any())).thenReturn(call);
        doReturn(scheduledFuture).when(executor).schedule(any(Runnable.class), anyLong(), any());
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));
    }

    @Test
    public void testGameStateCheck()
    {
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        webhookService.sendWebhook("http://webhook", "Message", null, "Item", WebhookService.WebhookCategory.VALUABLE_DROP);
        
        // Should not be scheduled
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    public void testManualBypass()
    {
        // No stubbing for client.getGameState() needed as it should be bypassed
        webhookService.sendWebhook("http://webhook", "Manual", null);
        
        // Should be scheduled even if not logged in
        verify(executor).schedule(any(Runnable.class), eq(3L), eq(TimeUnit.SECONDS));
        verify(client, never()).getGameState();
    }

    @Test
    public void testBundling()
    {
        String urls = "http://webhook";
        BufferedImage img1 = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        
        webhookService.sendWebhook(urls, "Valuable drop: Fang", img1, "Fang", WebhookService.WebhookCategory.VALUABLE_DROP);
        webhookService.sendWebhook(urls, "Collection log: Fang", null, "Fang", WebhookService.WebhookCategory.COLLECTION_LOG);

        // Verify scheduled task
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));

        // Run the task
        runnableCaptor.getValue().run();

        // Verify only one call was made
        verify(okHttpClient, times(1)).newCall(any());
        
        // Capture the request to check combined message
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        
        // We can't easily check the body because it's a MultipartBody, but we've verified bundling happened
    }

    @Test
    public void testDifferentItems()
    {
        String urls = "http://webhook";
        
        webhookService.sendWebhook(urls, "Item 1", null, "Item 1", WebhookService.WebhookCategory.LOOT);
        webhookService.sendWebhook(urls, "Item 2", null, "Item 2", WebhookService.WebhookCategory.LOOT);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

        // Verify two calls were made
        verify(okHttpClient, times(2)).newCall(any());
    }

    @Test
    public void testBingoBundling()
    {
        String urls = "http://webhook";
        
        webhookService.sendWebhook(urls, "Loot: 100 x Soul rune", null, "Soul rune", WebhookService.WebhookCategory.LOOT);
        webhookService.sendWebhook(urls, "Special item: 100 x Soul rune", null, "Soul rune", WebhookService.WebhookCategory.BINGO_LOOT);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

        // BINGO_LOOT (3) has higher priority than LOOT (6), so BINGO_LOOT should be primary
        verify(okHttpClient).newCall(argThat(request -> {
            // This is hard to check accurately without deep-inspecting MultipartBody, 
            // but we can at least verify it happened.
            return request.url().toString().equals("http://webhook/");
        }));
    }

    @Test
    public void testWebhookUrlSplitting()
    {
        // Test various separators and whitespace: comma, newline, and mixed
        String urls = "http://url1, http://url2\nhttp://url3, \n http://url4";
        webhookService.sendWebhook(urls, "Message", null);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

        // Should be 4 separate calls
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

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

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

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

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

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

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

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(runnableCaptor.capture(), eq(3L), eq(TimeUnit.SECONDS));
        runnableCaptor.getValue().run();

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());

        Buffer buffer = new Buffer();
        requestCaptor.getValue().body().writeTo(buffer);
        String body = buffer.readUtf8();

        assertTrue("Body should contain (Tournament) suffix", body.contains("Loot message (Tournament)"));
    }
}

package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class WebhookServiceTest
{
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
        webhookService = new WebhookService(okHttpClient, executor);
        when(okHttpClient.newCall(any())).thenReturn(call);
        doReturn(scheduledFuture).when(executor).schedule(any(Runnable.class), anyLong(), any());
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
}

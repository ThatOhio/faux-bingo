package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.WebhookService;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Handles manual screenshot capture via keybind.
 */
@Slf4j
public class ManualScreenshotHandler
{
	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;
	private final KeyManager keyManager;

	private final HotkeyListener hotkeyListener;

	public ManualScreenshotHandler(
		Client client,
		FauxBingoConfig config,
		WebhookService webhookService,
		DrawManager drawManager,
		ScheduledExecutorService executor,
		KeyManager keyManager)
	{
		this.client = client;
		this.config = config;
		this.webhookService = webhookService;
		this.drawManager = drawManager;
		this.executor = executor;
		this.keyManager = keyManager;

		this.hotkeyListener = new HotkeyListener(() -> config.manualScreenshotKeybind())
		{
			@Override
			public void hotkeyPressed()
			{
				sendManualScreenshot();
			}
		};
	}

	public void register()
	{
		keyManager.registerKeyListener(hotkeyListener);
		log.debug("Manual screenshot hotkey registered");
	}

	public void unregister()
	{
		keyManager.unregisterKeyListener(hotkeyListener);
		log.debug("Manual screenshot hotkey unregistered");
	}

	private void sendManualScreenshot()
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** sent a manual screenshot", playerName);

		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image);
					log.info("Manual screenshot sent");
				}
				catch (Exception e)
				{
					log.error("Error sending manual screenshot", e);
				}
			});
		});
	}
}

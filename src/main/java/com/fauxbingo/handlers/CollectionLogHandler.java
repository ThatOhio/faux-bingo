package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.WebhookService;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Handles collection log events.
 * Detects new collection log items through both chat messages and notification scripts.
 */
@Slf4j
public class CollectionLogHandler
{
	private static final String COLLECTION_LOG_TEXT = "New item added to your collection log: ";

	private final Client client;
	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	private boolean notificationStarted = false;

	public CollectionLogHandler(
		Client client,
		FauxBingoConfig config,
		WebhookService webhookService,
		DrawManager drawManager,
		ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.webhookService = webhookService;
		this.drawManager = drawManager;
		this.executor = executor;
	}

	public EventHandler<ChatMessage> createChatHandler()
	{
		return new EventHandler<ChatMessage>()
		{
			@Override
			public void handle(ChatMessage event)
			{
				if (!config.includeCollectionLog())
				{
					return;
				}

				if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
				{
					return;
				}

				String chatMessage = event.getMessage();
				if (chatMessage.startsWith(COLLECTION_LOG_TEXT) && 
					client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION) == 1)
				{
					String entry = Text.removeTags(chatMessage).substring(COLLECTION_LOG_TEXT.length());
					sendCollectionLogNotification(entry);
				}
			}

			@Override
			public Class<ChatMessage> getEventType()
			{
				return ChatMessage.class;
			}
		};
	}

	public EventHandler<ScriptPreFired> createScriptHandler()
	{
		return new EventHandler<ScriptPreFired>()
		{
			@Override
			public void handle(ScriptPreFired event)
			{
				if (!config.includeCollectionLog())
				{
					return;
				}

				switch (event.getScriptId())
				{
					case ScriptID.NOTIFICATION_START:
						notificationStarted = true;
						break;
					case ScriptID.NOTIFICATION_DELAY:
						if (!notificationStarted)
						{
							return;
						}
						String notificationTopText = client.getVarcStrValue(VarClientStr.NOTIFICATION_TOP_TEXT);
						String notificationBottomText = client.getVarcStrValue(VarClientStr.NOTIFICATION_BOTTOM_TEXT);
						if (notificationTopText.equalsIgnoreCase("Collection log"))
						{
							String entry = Text.removeTags(notificationBottomText).substring("New item:".length()).trim();
							sendCollectionLogNotification(entry);
						}
						notificationStarted = false;
						break;
				}
			}

			@Override
			public Class<ScriptPreFired> getEventType()
			{
				return ScriptPreFired.class;
			}
		};
	}

	private void sendCollectionLogNotification(String itemName)
	{
		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		String message = String.format("**%s** just received a new collection log item: **%s**!", 
			playerName, itemName);

		if (config.sendScreenshot())
		{
			takeScreenshotAndSend(message);
		}
		else
		{
			webhookService.sendWebhook(config.webhookUrl(), message, null);
		}
	}

	private void takeScreenshotAndSend(String message)
	{
		drawManager.requestNextFrameListener(image -> {
			executor.execute(() -> {
				try
				{
					webhookService.sendWebhook(config.webhookUrl(), message, (BufferedImage) image);
				}
				catch (Exception e)
				{
					log.error("Error sending webhook with screenshot for collection log", e);
				}
			});
		});
	}

	public void resetState()
	{
		notificationStarted = false;
	}
}

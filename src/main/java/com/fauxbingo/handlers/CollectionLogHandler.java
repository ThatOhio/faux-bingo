package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Handles collection log events. Detects new collection log items through both chat messages and
 * notification scripts, and reports them to DropCorrelationService. This is also the only signal
 * that ever learns a pet's real name (see PetChatHandler), and the only signal that can enrich a
 * raid/NPC LOOT signal with achievement confirmation for the same item.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CollectionLogHandler
{
	private static final String COLLECTION_LOG_TEXT = "New item added to your collection log: ";

	private final Client client;
	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final DropCorrelationService dropCorrelationService;

	private boolean notificationStarted = false;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String chatMessage = event.getMessage();
		if (chatMessage.startsWith(COLLECTION_LOG_TEXT) &&
			client.getVarbitValue(Varbits.COLLECTION_LOG_NOTIFICATION) == 1)
		{
			String entry = Text.removeTags(chatMessage).substring(COLLECTION_LOG_TEXT.length());
			reportCollectionLogEntry(entry, chatMessage, DetectionMethod.CHAT_COLLECTION_LOG);
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
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
					reportCollectionLogEntry(entry, notificationBottomText, DetectionMethod.NOTIFICATION_COLLECTION_LOG);
				}
				notificationStarted = false;
				break;
		}
	}

	private void reportCollectionLogEntry(String itemName, String rawText, DetectionMethod method)
	{
		DropItem dropItem = DropItem.builder()
			.name(itemName)
			.quantity(1)
			.build();

		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			DropSignal signal = DropSignal.builder()
				.detectionMethod(method)
				.raw(rawText)
				.items(Collections.singletonList(dropItem))
				.screenshot(image)
				.build();
			dropCorrelationService.report(signal);
		}));
	}

	public void resetState()
	{
		notificationStarted = false;
	}
}

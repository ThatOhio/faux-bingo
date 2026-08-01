package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;

/**
 * Handles chat message events to detect pet drops. The game never names the pet in this message,
 * so the signal carries no item identity - DropCorrelationService learns the name later if a
 * COLLECTION_LOG signal lands in the same correlation window (docs/bingo-events-api.md section 6.3).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PetChatHandler
{
	private static final ImmutableList<String> PET_MESSAGES = ImmutableList.of(
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed"
	);

	private final ScreenshotService screenshotService;
	private final ScheduledExecutorService executor;
	private final DropCorrelationService dropCorrelationService;
	private final InteractionTrackingService interactionTrackingService;

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();

		// Check if message indicates a pet drop
		if (PET_MESSAGES.stream().anyMatch(message::contains))
		{
			handlePetDrop(message);
		}
	}

	private void handlePetDrop(String rawChatLine)
	{
		log.info("Pet drop detected");

		// Best guess at what the pet came from; the plugin has no better signal than this.
		List<String> recentInteractions = interactionTrackingService != null
			? interactionTrackingService.getRecentInteractionNames()
			: List.of();
		String sourceNameGuess = recentInteractions.isEmpty() ? null : recentInteractions.get(0);

		screenshotService.requestScreenshot(image -> executor.execute(() -> {
			DropSignal signal = DropSignal.builder()
				.detectionMethod(DetectionMethod.CHAT_PET)
				.raw(rawChatLine)
				.sourceNameGuess(sourceNameGuess)
				.screenshot(image)
				.build();
			dropCorrelationService.report(signal);
		}));
	}
}

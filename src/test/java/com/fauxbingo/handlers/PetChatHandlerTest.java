package com.fauxbingo.handlers;

import com.fauxbingo.services.DropCorrelationService;
import com.fauxbingo.services.InteractionTrackingService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropSignal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class PetChatHandlerTest
{
	@Mock
	private ScreenshotService screenshotService;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private DropCorrelationService dropCorrelationService;

	@Mock
	private InteractionTrackingService interactionTrackingService;

	private PetChatHandler petChatHandler;

	@Before
	public void before()
	{
		petChatHandler = new PetChatHandler(screenshotService, executor, dropCorrelationService, interactionTrackingService);
		when(interactionTrackingService.getRecentInteractionNames()).thenReturn(Collections.emptyList());

		doAnswer(invocation -> {
			Runnable r = invocation.getArgument(0);
			r.run();
			return null;
		}).when(executor).execute(any());

		doAnswer(invocation -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = invocation.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());
	}

	private DropSignal captureSignal()
	{
		ArgumentCaptor<DropSignal> captor = ArgumentCaptor.forClass(DropSignal.class);
		verify(dropCorrelationService).report(captor.capture());
		return captor.getValue();
	}

	@Test
	public void testPetFollowMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you're being followed.");

		petChatHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals(DetectionMethod.CHAT_PET, signal.getDetectionMethod());
	}

	@Test
	public void testPetInventoryMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You feel something weird sneaking into your backpack.");

		petChatHandler.onChatMessage(event);

		verify(dropCorrelationService).report(any());
	}

	@Test
	public void testPetDuplicateMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you would have been followed.");

		petChatHandler.onChatMessage(event);

		verify(dropCorrelationService).report(any());
	}

	@Test
	public void testNotPetMessage()
	{
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You catch a shrimp.");

		petChatHandler.onChatMessage(event);

		verifyNoInteractions(dropCorrelationService);
	}

	@Test
	public void testSourceNameGuessComesFromInteractionTracking()
	{
		when(interactionTrackingService.getRecentInteractionNames()).thenReturn(List.of("Vorkath"));
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessage("You have a funny feeling like you're being followed.");

		petChatHandler.onChatMessage(event);

		DropSignal signal = captureSignal();
		org.junit.Assert.assertEquals("Vorkath", signal.getSourceNameGuess());
	}
}

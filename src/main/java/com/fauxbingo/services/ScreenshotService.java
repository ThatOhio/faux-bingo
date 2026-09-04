package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;

/**
 * Centralized screenshot capture with optional chat/PM hiding for privacy.
 * Hides private messages and/or main chat (per config) before capture, then unhides after.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ScreenshotService
{
	private static final long CAPTURE_TIMEOUT_MS = 2_000;

	private final Client client;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final FauxBingoConfig config;
	private final ScheduledExecutorService executor;

	/**
	 * Request a screenshot. Hides PM and/or main chat per config before capture, then unhides after.
	 * Safe to call from any thread, hide/show and frame capture run on the client thread.
	 *
	 * onImage is always called exactly once, with null if no frame arrived in time. Callers report
	 * the drop either way: DrawManager drops its whole listener queue without a word when the client
	 * cannot supply a frame, and a missing picture must not cost the event as well.
	 *
	 * @param onImage consumer for the captured image, null when the capture failed
	 */
	public void requestScreenshot(Consumer<BufferedImage> onImage)
	{
		boolean hidePm = config.screenshotHidePrivateMessages();
		boolean hideChat = config.screenshotHideChat();

		clientThread.invokeLater(() -> {
			boolean pmHidden = hideWidget(hidePm, InterfaceID.PmChat.CONTAINER);
			boolean chatHidden = hideWidget(hideChat, InterfaceID.Chatbox.CHATAREA);
			AtomicBoolean delivered = new AtomicBoolean();

			Runnable restore = () -> {
				unhideWidget(pmHidden, InterfaceID.PmChat.CONTAINER);
				unhideWidget(chatHidden, InterfaceID.Chatbox.CHATAREA);
			};

			executor.schedule(() -> {
				if (delivered.compareAndSet(false, true))
				{
					log.debug("No frame within {}ms, reporting the drop without a screenshot", CAPTURE_TIMEOUT_MS);
					restore.run();
					onImage.accept(null);
				}
			}, CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

			drawManager.requestNextFrameListener(image -> {
				if (!delivered.compareAndSet(false, true))
				{
					return;
				}
				restore.run();
				onImage.accept(image instanceof BufferedImage ? (BufferedImage) image : null);
			});
		});
	}

	/**
	 * Hide a widget if shouldHide is true. Call on client thread.
	 *
	 * @return true if the widget was hidden by this call
	 */
	private boolean hideWidget(boolean shouldHide, int componentId)
	{
		if (!shouldHide)
		{
			return false;
		}
		Widget widget = client.getWidget(componentId);
		if (widget == null || widget.isHidden())
		{
			return false;
		}
		widget.setHidden(true);
		return true;
	}

	/**
	 * Unhide a widget if we had hidden it. Must run on client thread, uses ClientThread.invoke.
	 */
	private void unhideWidget(boolean shouldUnhide, int componentId)
	{
		if (!shouldUnhide)
		{
			return;
		}
		clientThread.invoke(() -> {
			Widget widget = client.getWidget(componentId);
			if (widget != null)
			{
				widget.setHidden(false);
			}
		});
	}
}

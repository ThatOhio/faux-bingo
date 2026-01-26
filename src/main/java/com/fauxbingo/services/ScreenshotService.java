package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
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
public class ScreenshotService
{
	private final Client client;
	private final ClientThread clientThread;
	private final DrawManager drawManager;
	private final FauxBingoConfig config;

	public ScreenshotService(
		Client client,
		ClientThread clientThread,
		DrawManager drawManager,
		FauxBingoConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.drawManager = drawManager;
		this.config = config;
	}

	/**
	 * Request a screenshot. Hides PM and/or main chat per config before capture, then unhides after.
	 * The onImage callback receives the captured image, run any I/O (e.g. webhook) on a background executor.
	 * Safe to call from any thread (e.g. AWT for hotkeys), hide/show and frame capture run on the client thread.
	 *
	 * @param onImage consumer for the captured image, typically called from the frame listener
	 */
	public void requestScreenshot(Consumer<BufferedImage> onImage)
	{
		boolean hidePm = config.screenshotHidePrivateMessages();
		boolean hideChat = config.screenshotHideChat();

		clientThread.invokeLater(() -> {
			boolean pmHidden = hideWidget(hidePm, InterfaceID.PmChat.CONTAINER);
			boolean chatHidden = hideWidget(hideChat, InterfaceID.Chatbox.CHATAREA);

			drawManager.requestNextFrameListener(image -> {
				BufferedImage buffered = image instanceof BufferedImage ? (BufferedImage) image : null;
				if (buffered != null)
				{
					onImage.accept(buffered);
				}
				else
				{
					log.warn("DrawManager did not provide a BufferedImage. Skipping screenshot callback.");
				}

				unhideWidget(pmHidden, InterfaceID.PmChat.CONTAINER);
				unhideWidget(chatHidden, InterfaceID.Chatbox.CHATAREA);
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

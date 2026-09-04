package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * The capture is the only thing standing between a drop and never being reported, since handlers
 * build their signal inside the callback. So it has to fire exactly once, whatever DrawManager does.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ScreenshotServiceTest
{
	@Mock
	private Client client;

	@Mock
	private ClientThread clientThread;

	@Mock
	private DrawManager drawManager;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private ScheduledExecutorService executor;

	private final List<Runnable> scheduled = new ArrayList<>();
	private ScreenshotService service;

	@Before
	public void before()
	{
		doAnswer(inv -> {
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		doAnswer(inv -> {
			scheduled.add(inv.getArgument(0));
			return null;
		}).when(executor).schedule(any(Runnable.class), anyLong(), any(java.util.concurrent.TimeUnit.class));

		service = new ScreenshotService(client, clientThread, drawManager, config, executor);
	}

	private Consumer<Image> frameListener()
	{
		ArgumentCaptor<Consumer<Image>> captor = ArgumentCaptor.forClass(Consumer.class);
		verify(drawManager).requestNextFrameListener(captor.capture());
		return captor.getValue();
	}

	@Test
	public void deliversTheCapturedFrame()
	{
		List<BufferedImage> delivered = new ArrayList<>();
		service.requestScreenshot(delivered::add);

		BufferedImage frame = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		frameListener().accept(frame);

		assertEquals(1, delivered.size());
		assertNotNull(delivered.get(0));
	}

	/** DrawManager clears its whole queue without a word when it cannot supply a frame. */
	@Test
	public void deliversNullWhenNoFrameArrives()
	{
		List<BufferedImage> delivered = new ArrayList<>();
		service.requestScreenshot(delivered::add);

		scheduled.forEach(Runnable::run);

		assertEquals(1, delivered.size());
		assertNull(delivered.get(0));
	}

	@Test
	public void aLateFrameDoesNotDeliverASecondTime()
	{
		List<BufferedImage> delivered = new ArrayList<>();
		service.requestScreenshot(delivered::add);

		scheduled.forEach(Runnable::run);
		frameListener().accept(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));

		assertEquals(1, delivered.size());
	}
}

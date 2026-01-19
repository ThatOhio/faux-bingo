package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.WebhookService;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.HotkeyListener;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class ManualScreenshotHandlerTest
{
	@Mock
	private Client client;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private WebhookService webhookService;

	@Mock
	private DrawManager drawManager;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private KeyManager keyManager;

	@Mock
	private Player player;

	private ManualScreenshotHandler manualScreenshotHandler;

	@Before
	public void before()
	{
		manualScreenshotHandler = new ManualScreenshotHandler(client, config, webhookService, drawManager, executor, keyManager);
		lenient().when(client.getLocalPlayer()).thenReturn(player);
		lenient().when(player.getName()).thenReturn("TestPlayer");
		lenient().when(config.webhookUrl()).thenReturn("http://webhook");
	}

	@Test
	public void testRegister()
	{
		manualScreenshotHandler.register();
		verify(keyManager).registerKeyListener(any());
	}

	@Test
	public void testUnregister()
	{
		manualScreenshotHandler.unregister();
		verify(keyManager).unregisterKeyListener(any());
	}

	@Test
	public void testHotkeyTriggersScreenshot()
	{
		ArgumentCaptor<HotkeyListener> captor = ArgumentCaptor.forClass(HotkeyListener.class);
		manualScreenshotHandler.register();
		verify(keyManager).registerKeyListener(captor.capture());

		HotkeyListener listener = captor.getValue();
		listener.hotkeyPressed();

		verify(drawManager).requestNextFrameListener(any());
	}
}

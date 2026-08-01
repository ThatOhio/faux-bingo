package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.google.gson.Gson;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.vars.AccountType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.Silent.class)
public class PresenceServiceTest
{
	@Mock
	private Client client;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private OkHttpClient okHttpClient;

	@Mock
	private Call call;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private Player localPlayer;

	private PresenceService presenceService;

	@Before
	public void before()
	{
		when(config.enableBingoApi()).thenReturn(true);
		when(config.apiToken()).thenReturn("token123");
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		presenceService = new PresenceService(client, config, "http://api", okHttpClient, new Gson(), executor);
	}

	@Test
	public void sendsHeartbeatWhenLoggedIn()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getName()).thenReturn("Zezima");
		when(client.getAccountHash()).thenReturn(123456789L);
		when(client.getAccountType()).thenReturn(AccountType.IRONMAN);

		presenceService.onLogin("Zezima");

		ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
		verify(okHttpClient).newCall(captor.capture());
		Request request = captor.getValue();
		assertTrue(request.url().toString().endsWith("/v1/seen"));
		assertTrue(request.header("Authorization").equals("Bearer token123"));

		Buffer buffer = new Buffer();
		try
		{
			request.body().writeTo(buffer);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
		String body = buffer.readUtf8();
		assertTrue(body.contains("\"accountHash\":\"123456789\""));
		assertTrue(body.contains("\"displayName\":\"Zezima\""));
		assertTrue(body.contains("\"accountType\":\"IRONMAN\""));
	}

	@Test
	public void skipsHeartbeatFromLoginScreen()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		presenceService.onLogin("Zezima");

		verifyNoInteractions(okHttpClient);
	}

	@Test
	public void skipsHeartbeatWhenDisabled()
	{
		when(config.enableBingoApi()).thenReturn(false);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		presenceService.onLogin("Zezima");

		verifyNoInteractions(okHttpClient);
	}
}

package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.data.MeResponseDto;
import com.fauxbingo.services.data.TeamColorsDto;
import com.fauxbingo.services.data.TeamDto;
import com.google.gson.Gson;
import java.awt.Color;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.Silent.class)
public class MeServiceTest
{
	@Mock
	private FauxBingoConfig config;

	@Mock
	private ConfigManager configManager;

	@Mock
	private OkHttpClient okHttpClient;

	@Mock
	private Call call;

	@Mock
	private ScheduledExecutorService executor;

	private final Gson gson = new Gson();
	private MeService meService;

	@Before
	public void before()
	{
		when(config.enableBingoApi()).thenReturn(true);
		when(config.apiToken()).thenReturn("token123");
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

		// Run scheduled retries/fetches inline, same trick used elsewhere for ScheduledExecutorService mocks.
		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return mock(ScheduledFuture.class);
		}).when(executor).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

		meService = new MeService(config, configManager, "http://api", okHttpClient, gson, executor);
	}

	private Callback captureCallback()
	{
		ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
		verify(call).enqueue(captor.capture());
		return captor.getValue();
	}

	private Response response(int code, String body) throws Exception
	{
		return new Response.Builder()
			.request(new Request.Builder().url("http://api/v1/me").build())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("msg")
			.body(ResponseBody.create(MediaType.parse("application/json"), body))
			.build();
	}

	@Test
	public void fetchesAndCachesTeamOnLogin() throws Exception
	{
		meService.onLogin("Zezima");

		Callback callback = captureCallback();
		MeResponseDto dto = new MeResponseDto();
		dto.setMemberName("Zezima");
		TeamDto team = new TeamDto();
		team.setId("team-1");
		team.setName("Red Team");
		team.setDiscordScreenshotWebhookUrl("https://discord.com/api/webhooks/team");
		TeamColorsDto colors = new TeamColorsDto();
		colors.setPrimaryBackground("#FF0000");
		team.setColors(colors);
		dto.setTeam(team);

		callback.onResponse(call, response(200, gson.toJson(dto)));

		assertEquals("Red Team", meService.getCachedMe().getTeam().getName());
		verify(configManager).setConfiguration("fauxbingo", "teamName", "Red Team");
		verify(configManager).setConfiguration("fauxbingo", "teamNameColor", new Color(0xFF, 0x00, 0x00));
	}

	@Test
	public void doesNothingWhenDisabled()
	{
		when(config.enableBingoApi()).thenReturn(false);

		meService.onLogin("Zezima");

		verifyNoInteractions(okHttpClient);
	}

	@Test
	public void doesNothingWhenTokenBlank()
	{
		when(config.apiToken()).thenReturn("  ");

		meService.onLogin("Zezima");

		verifyNoInteractions(okHttpClient);
	}

	@Test
	public void unauthorizedResponseLeavesTeamUnset() throws Exception
	{
		meService.onLogin("Zezima");

		Callback callback = captureCallback();
		callback.onResponse(call, response(401, ""));

		assertNull(meService.getCachedMe());
	}

	@Test
	public void secondLoginWithinCacheWindowSkipsRefetch() throws Exception
	{
		meService.onLogin("Zezima");
		Callback callback = captureCallback();
		MeResponseDto dto = new MeResponseDto();
		dto.setTeam(new TeamDto());
		callback.onResponse(call, response(200, gson.toJson(dto)));

		meService.onLogin("Zezima");

		verify(okHttpClient, times(1)).newCall(any(Request.class));
	}

	@Test
	public void usernameChangeForcesRefetchEvenWithinWindow() throws Exception
	{
		meService.onLogin("Zezima");
		Callback callback = captureCallback();
		MeResponseDto dto = new MeResponseDto();
		dto.setTeam(new TeamDto());
		callback.onResponse(call, response(200, gson.toJson(dto)));

		meService.onLogin("AltAccount");

		verify(okHttpClient, times(2)).newCall(any(Request.class));
	}
}

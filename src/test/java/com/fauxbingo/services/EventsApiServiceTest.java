package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.data.BingoVerdictDto;
import com.fauxbingo.services.data.DeathSignal;
import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.DropType;
import com.fauxbingo.services.data.EventResultDto;
import com.fauxbingo.services.data.EventsResponseDto;
import com.fauxbingo.services.data.MergedDropEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.vars.AccountType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.Silent.class)
public class EventsApiServiceTest
{
	@Mock
	private Client client;

	@Mock
	private FauxBingoConfig config;

	@Mock
	private OkHttpClient okHttpClient;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private Player localPlayer;

	private final Gson gson = new Gson();
	private EventsApiService service;

	@Before
	public void before()
	{
		when(config.enableBingoApi()).thenReturn(true);
		when(config.apiToken()).thenReturn("token123");
		when(client.getLocalPlayer()).thenReturn(localPlayer);
		when(localPlayer.getName()).thenReturn("Zezima");
		when(client.getAccountHash()).thenReturn(555L);
		when(client.getAccountType()).thenReturn(AccountType.NORMAL);
		when(client.getWorld()).thenReturn(301);
		when(client.getWorldType()).thenReturn(EnumSet.of(WorldType.MEMBERS));

		doAnswer(inv -> {
			Runnable r = inv.getArgument(0);
			r.run();
			return null;
		}).when(executor).schedule(any(Runnable.class), anyLong(), any(java.util.concurrent.TimeUnit.class));

		service = new EventsApiService(client, config, "http://api", okHttpClient, gson, executor);
	}

	private Response response(int code, String body)
	{
		return new Response.Builder()
			.request(new Request.Builder().url("http://api/v1/events").build())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("msg")
			.body(ResponseBody.create(MediaType.parse("application/json"), body))
			.build();
	}

	private MergedDropEvent lootEvent()
	{
		DropSignal primary = DropSignal.builder()
			.detectionMethod(DetectionMethod.NPC_LOOT_RECEIVED)
			.sourceName("Vorkath")
			.npcId(8061)
			.combatLevel(732)
			.regionId(9007)
			.plane(0)
			.items(Collections.singletonList(DropItem.builder().id(536).name("Dragon bones").quantity(1).unitPriceGe(2500L).build()))
			.totalValueGe(2500L)
			.build();

		return MergedDropEvent.builder()
			.type(DropType.LOOT)
			.dropGroupId("group-1")
			.primarySignal(primary)
			.contributingSignals(Collections.singletonList(primary))
			.build();
	}

	@Test
	public void acceptSendsLootEnvelopeImmediately()
	{
		Call call = mock(Call.class);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

		service.accept(lootEvent());

		ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
		verify(okHttpClient).newCall(captor.capture());
		Request request = captor.getValue();
		assertTrue(request.url().toString().endsWith("/v1/events"));

		JsonArray body = bodyAsJsonArray(request);
		assertEquals(1, body.size());
		JsonObject envelope = body.get(0).getAsJsonObject();
		assertEquals("LOOT", envelope.get("type").getAsString());
		assertEquals("group-1", envelope.get("dropGroupId").getAsString());
		JsonObject source = envelope.getAsJsonObject("payload").getAsJsonObject("source");
		assertEquals("Vorkath", source.get("name").getAsString());
		assertEquals(732, source.get("combatLevel").getAsInt());
		assertEquals(9007, source.getAsJsonObject("location").get("regionId").getAsInt());
	}

	@Test
	public void acceptDoesNothingWhenDisabled()
	{
		when(config.enableBingoApi()).thenReturn(false);

		service.accept(lootEvent());

		verifyNoInteractions(okHttpClient);
	}

	@Test
	public void submitDeathBatchesUntilFiftyThenFlushes()
	{
		Call call = mock(Call.class);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

		DeathSignal death = DeathSignal.builder().regionId(12893).killerName("Elvarg").killerKind("NPC").build();

		for (int i = 0; i < 49; i++)
		{
			service.submitDeath(death);
		}
		verifyNoInteractions(okHttpClient);

		service.submitDeath(death);

		ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
		verify(okHttpClient).newCall(captor.capture());
		JsonArray body = bodyAsJsonArray(captor.getValue());
		assertEquals(50, body.size());
		assertEquals("DEATH", body.get(0).getAsJsonObject().get("type").getAsString());
	}

	@Test
	public void unauthorizedResponseDropsWithoutRetry() throws Exception
	{
		Call call = mock(Call.class);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

		service.accept(lootEvent());

		Callback callback = captureCallback(call);
		callback.onResponse(call, response(401, ""));

		verify(okHttpClient, times(1)).newCall(any());
	}

	@Test
	public void serverErrorSchedulesRetry() throws Exception
	{
		Call call = mock(Call.class);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

		service.accept(lootEvent());

		Callback callback = captureCallback(call);
		callback.onResponse(call, response(500, ""));

		// The mocked executor runs scheduled retries inline, so a retry means a second newCall.
		verify(okHttpClient, times(2)).newCall(any());
	}

	@Test
	public void requestScreenshotVerdictTriggersUpload() throws Exception
	{
		Call eventsCall = mock(Call.class);
		Call screenshotCall = mock(Call.class);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(eventsCall, screenshotCall);

		MergedDropEvent merged = lootEvent();
		merged.setScreenshot(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
		service.accept(merged);

		ArgumentCaptor<Request> firstReq = ArgumentCaptor.forClass(Request.class);
		verify(okHttpClient).newCall(firstReq.capture());
		JsonArray sentBody = bodyAsJsonArray(firstReq.getValue());
		String eventId = sentBody.get(0).getAsJsonObject().get("eventId").getAsString();

		EventResultDto result = new EventResultDto();
		result.setEventId(eventId);
		result.setStatus("ok");
		BingoVerdictDto verdict = new BingoVerdictDto();
		verdict.setRequestScreenshot(true);
		result.setBingo(verdict);
		EventsResponseDto resp = new EventsResponseDto();
		resp.setResults(Collections.singletonList(result));

		Callback callback = captureCallback(eventsCall);
		callback.onResponse(eventsCall, response(200, gson.toJson(resp)));

		verify(okHttpClient, times(2)).newCall(any());
		verify(screenshotCall).enqueue(any());
	}

	private Callback captureCallback(Call call)
	{
		ArgumentCaptor<Callback> captor = ArgumentCaptor.forClass(Callback.class);
		verify(call).enqueue(captor.capture());
		return captor.getValue();
	}

	private JsonArray bodyAsJsonArray(Request request)
	{
		try
		{
			Buffer buffer = new Buffer();
			request.body().writeTo(buffer);
			return new JsonParser().parse(buffer.readUtf8()).getAsJsonArray();
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}
}

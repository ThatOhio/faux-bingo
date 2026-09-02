package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.HttpUrl;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Matching is on accounts[].displayName (the RSN), never players[].memberName (a Discord
 * nickname) - that distinction is the whole point of the v1 /v1/teams rewrite.
 *
 * The icon tests pin the other rule the plugin has to hold to: every URL it contacts is derived
 * from the configured API base plus a hardcoded path, never lifted out of an API response.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class TeamIconServiceTest
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
	private ClientThread clientThread;

	private static final int MAX_TICKS = 50;

	private final Gson gson = new Gson();
	private final List<Request> requests = new ArrayList<>();
	private TeamIconService service;

	private String rosterJson = rosterJson("team-1", false);
	private int rosterResponseCode = 200;
	private int iconResponseCode = 200;
	private HttpUrl iconResponseUrl = null; // non-null simulates a redirect the client followed
	private byte[] iconBytes;
	private int ticksWaited = 0;

	@Before
	public void before() throws Exception
	{
		when(config.enableBingoApi()).thenReturn(true);
		when(config.apiToken()).thenReturn("token123");
		when(config.showTeamIconsInChat()).thenReturn(true);
		when(client.getModIcons()).thenReturn(new IndexedSprite[0]);
		when(client.createIndexedSprite()).thenReturn(mock(IndexedSprite.class));

		iconBytes = pngBytes();

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		// The BooleanSupplier overload re-runs on the next tick until it returns true, so the stub
		// has to keep calling it rather than firing once.
		doAnswer(inv -> {
			BooleanSupplier r = inv.getArgument(0);
			for (int tick = 0; tick < MAX_TICKS && !r.getAsBoolean(); tick++)
			{
				ticksWaited++;
			}
			return null;
		}).when(clientThread).invokeLater(any(BooleanSupplier.class));

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

		// Dispatch on the requested URL: the roster fetch and the icon download are separate calls
		// and each needs its own single-shot response body.
		when(okHttpClient.newCall(any(Request.class))).thenAnswer(inv -> {
			Request request = inv.getArgument(0);
			requests.add(request);
			Call call = mock(Call.class);
			when(call.execute()).thenAnswer(ignored -> responseFor(request));
			return call;
		});

		service = new TeamIconService(client, config, () -> "http://api", okHttpClient, gson, executor, clientThread);
	}

	private static byte[] pngBytes() throws Exception
	{
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private Response responseFor(Request request)
	{
		boolean icon = request.url().encodedPath().endsWith("/icon");
		HttpUrl finalUrl = icon && iconResponseUrl != null ? iconResponseUrl : request.url();

		return new Response.Builder()
			.request(new Request.Builder().url(finalUrl).build())
			.protocol(Protocol.HTTP_1_1)
			.code(icon ? iconResponseCode : rosterResponseCode)
			.message("msg")
			.body(icon
				? ResponseBody.create(MediaType.parse("image/png"), iconBytes)
				: ResponseBody.create(MediaType.parse("application/json"), rosterJson))
			.build();
	}

	/**
	 * memberName is a Discord nickname and must never be matched against chat names. iconUrl is
	 * included when the test needs to prove we ignore it.
	 */
	private static String rosterJson(String teamId, boolean withIconUrl)
	{
		return "{\"teams\":[{\"id\":\"" + teamId + "\",\"name\":\"Red Team\""
			+ (withIconUrl ? ",\"iconUrl\":\"http://evil.example/icon.png\"" : "")
			+ ",\"players\":[{\"memberName\":\"Bob (Discord)\","
			+ "\"accounts\":[{\"displayName\":\"Zezima\"}]}]}]}";
	}

	private List<Request> iconRequests()
	{
		return requests.stream()
			.filter(r -> r.url().encodedPath().endsWith("/icon"))
			.collect(Collectors.toList());
	}

	private Runnable refreshTask()
	{
		ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).scheduleAtFixedRate(captor.capture(), anyLong(), anyLong(), any(TimeUnit.class));
		return captor.getValue();
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> rsnToTeamId() throws Exception
	{
		Field field = TeamIconService.class.getDeclaredField("rsnToTeamId");
		field.setAccessible(true);
		return (Map<String, String>) field.get(service);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> teamToSpriteIndex() throws Exception
	{
		Field field = TeamIconService.class.getDeclaredField("teamToSpriteIndex");
		field.setAccessible(true);
		return (Map<String, Integer>) field.get(service);
	}

	private void seedSpriteIndex(String teamId, int index) throws Exception
	{
		teamToSpriteIndex().put(teamId, index);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Long> iconFetchFailures() throws Exception
	{
		Field field = TeamIconService.class.getDeclaredField("iconFetchFailures");
		field.setAccessible(true);
		return (Map<String, Long>) field.get(service);
	}

	/** Pretends the retry cooldown on a remembered icon failure has already elapsed. */
	private void expireIconFailure(String teamId) throws Exception
	{
		iconFetchFailures().put(teamId, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
	}

	@Test
	public void fetchMatchesByRsnNotDiscordNickname() throws Exception
	{
		service.start();

		Map<String, String> rsnMap = rsnToTeamId();
		assertEquals("team-1", rsnMap.get("zezima"));
		assertNull(rsnMap.get("bob (discord)"));
	}

	@Test
	public void chatMessageIsBadgedWhenRsnMatchesRegisteredTeam() throws Exception
	{
		service.start();
		seedSpriteIndex("team-1", 5);

		ChatMessage event = new ChatMessage();
		event.setName("Zezima");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node).setName("<img=5>Zezima");
		// Without this the chatbox line, which may already be built, never picks the name up.
		verify(client).refreshChat();
	}

	@Test
	public void chatMessageUnchangedWhenNoTeamMatch()
	{
		service.start();

		ChatMessage event = new ChatMessage();
		event.setName("SomeRandomGuy");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node, never()).setName(any());
		verify(client, never()).refreshChat();
	}

	@Test
	public void chatMessageIgnoredWhenIconsDisabled() throws Exception
	{
		when(config.showTeamIconsInChat()).thenReturn(false);

		service.start();
		seedSpriteIndex("team-1", 5);

		ChatMessage event = new ChatMessage();
		event.setName("Zezima");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node, never()).setName(any());
	}

	@Test
	public void failedTeamsFetchDoesNotThrow() throws Exception
	{
		rosterResponseCode = 500;
		rosterJson = "";

		service.start();

		assertEquals(0, rsnToTeamId().size());
	}

	@Test
	public void iconIsFetchedFromDerivedUrlWithAuth() throws Exception
	{
		service.start();

		assertEquals(1, iconRequests().size());
		Request icon = iconRequests().get(0);
		assertEquals("http://api/v1/teams/team-1/icon", icon.url().toString());
		assertEquals("Bearer token123", icon.header("Authorization"));
		assertEquals(Integer.valueOf(0), teamToSpriteIndex().get("team-1"));
	}

	@Test
	public void iconUrlFromApiResponseIsNeverContacted()
	{
		rosterJson = rosterJson("team-1", true);

		service.start();

		assertTrue("plugin must not contact a URL supplied by the API response",
			requests.stream().allMatch(r -> "api".equals(r.url().host())));
	}

	@Test
	public void teamIdWithSlashesIsEncodedIntoASingleSegment()
	{
		rosterJson = rosterJson("../../evil", false);

		service.start();

		assertEquals(1, iconRequests().size());
		HttpUrl url = iconRequests().get(0).url();
		assertEquals("api", url.host());
		assertEquals("/v1/teams/..%2F..%2Fevil/icon", url.encodedPath());
	}

	@Test
	public void teamIdThatEscapesTheTeamsPathIsRejected()
	{
		rosterJson = rosterJson("..", false);

		service.start();

		assertTrue("a team id that walks out of /v1/teams must not be requested",
			iconRequests().isEmpty());
	}

	@Test
	public void iconIsRegisteredOnlyOnceAcrossRefreshes()
	{
		service.start();
		refreshTask().run();

		assertEquals(1, iconRequests().size());
	}

	@Test
	public void failedIconFetchIsNotRetriedWithinTheCooldown() throws Exception
	{
		iconResponseCode = 404;

		service.start();
		refreshTask().run();

		assertEquals(1, iconRequests().size());
		assertNull(teamToSpriteIndex().get("team-1"));
	}

	/**
	 * An icon that 404'd because it had not been uploaded yet has to be able to appear later in the
	 * same session - a failure the plugin remembers forever needs a client restart to clear.
	 */
	@Test
	public void failedIconFetchIsRetriedOnceTheCooldownElapses() throws Exception
	{
		iconResponseCode = 404;

		service.start();
		assertEquals(1, iconRequests().size());

		expireIconFailure("team-1");
		iconResponseCode = 200;
		refreshTask().run();

		assertEquals(2, iconRequests().size());
		assertEquals(Integer.valueOf(0), teamToSpriteIndex().get("team-1"));
	}

	/**
	 * The icon lives behind RuneLite's shared on-disk OkHttp cache, and the route serves it with a
	 * one-hour max-age and no validator, so a replaced icon would otherwise stay stale for an hour
	 * across plugin toggles and client restarts.
	 */
	@Test
	public void iconRequestBypassesTheSharedHttpCache()
	{
		service.start();

		assertEquals("no-cache", iconRequests().get(0).header("Cache-Control"));
	}

	/**
	 * onConfigChanged calls start() on every apiToken/apiBaseUrl/enableBingoApi edit. The schedule
	 * happens on the client thread, so a guard that reads refreshTask lets two quick calls both
	 * schedule, leaking the first task uncancelled for the rest of the session.
	 */
	@Test
	public void startSchedulesOnlyOnceWhenCalledRepeatedly()
	{
		service.start();
		service.start();

		verify(executor, times(1)).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
	}

	/**
	 * The game sends spaces in chat names as \u00A0, so a trim+lowercase of the RSN can never match
	 * the plain space the API returns - every multi-word RSN would go unbadged.
	 */
	@Test
	public void rsnWithASpaceMatchesTheNonBreakingSpaceTheGameSends() throws Exception
	{
		rosterJson = "{\"teams\":[{\"id\":\"team-1\",\"name\":\"Red Team\",\"players\":["
			+ "{\"memberName\":\"Herc (Discord)\",\"accounts\":[{\"displayName\":\"DH Herc\"}]}]}]}";

		service.start();
		seedSpriteIndex("team-1", 3);

		assertEquals("team-1", rsnToTeamId().get("dh herc"));

		ChatMessage event = new ChatMessage();
		event.setName("DH\u00A0Herc");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node).setName("<img=3>DH\u00A0Herc");
	}

	@Test
	public void redirectedIconResponseIsIgnored() throws Exception
	{
		iconResponseUrl = HttpUrl.parse("http://evil.example/icon.png");

		service.start();

		assertNull(teamToSpriteIndex().get("team-1"));
		verify(client, never()).setModIcons(any());
	}

	/**
	 * getModIcons() is null until the client loads sprites, and RuneLite starts plugins at the login
	 * screen. Dereferencing it there threw, and ClientThread drops a task that throws - so the
	 * refresh was never scheduled and chat icons stayed off for the whole client session.
	 */
	@Test
	public void startWaitsForModIconsInsteadOfGivingUpAtTheLoginScreen() throws Exception
	{
		when(client.getModIcons()).thenReturn(null, null, new IndexedSprite[4]);

		service.start();

		assertEquals(2, ticksWaited);
		verify(executor).scheduleAtFixedRate(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.MINUTES));
		assertEquals("team-1", rsnToTeamId().get("zezima"));
	}

	/** A shutdown while still waiting for modIcons must drop the retry, not schedule a late task. */
	@Test
	public void pendingRetryIsDroppedWhenShutdownRunsFirst()
	{
		when(client.getModIcons()).thenReturn(null);

		service.start();
		assertEquals(MAX_TICKS, ticksWaited);

		ArgumentCaptor<BooleanSupplier> captor = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(clientThread).invokeLater(captor.capture());

		service.shutdown();

		// true takes it off the client thread queue rather than retrying for the rest of the session.
		assertTrue(captor.getValue().getAsBoolean());
		verify(executor, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
	}
}

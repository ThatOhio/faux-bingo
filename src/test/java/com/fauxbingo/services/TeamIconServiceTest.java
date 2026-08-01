package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.TeamAccountDto;
import com.fauxbingo.services.data.TeamPlayerDto;
import com.fauxbingo.services.data.TeamRosterDto;
import com.fauxbingo.services.data.TeamsResponseDto;
import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Matching is on accounts[].displayName (the RSN), never players[].memberName (a Discord
 * nickname) - that distinction is the whole point of the v1 /v1/teams rewrite.
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
	private Call call;

	@Mock
	private ScheduledExecutorService executor;

	@Mock
	private ClientThread clientThread;

	private final Gson gson = new Gson();
	private TeamIconService service;

	@Before
	public void before() throws Exception
	{
		when(config.enableBingoApi()).thenReturn(true);
		when(config.apiToken()).thenReturn("token123");
		when(config.showTeamIconsInChat()).thenReturn(true);
		when(client.getModIcons()).thenReturn(new IndexedSprite[0]);

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

		service = new TeamIconService(client, config, "http://api", okHttpClient, gson, executor, clientThread);
	}

	private Response response(int code, String body)
	{
		return new Response.Builder()
			.request(new Request.Builder().url("http://api/v1/teams").build())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("msg")
			.body(ResponseBody.create(MediaType.parse("application/json"), body))
			.build();
	}

	private TeamsResponseDto rosterWithOneTeam()
	{
		TeamAccountDto account = new TeamAccountDto();
		account.setDisplayName("Zezima");

		TeamPlayerDto player = new TeamPlayerDto();
		player.setMemberName("Bob (Discord)"); // must never be matched against chat names
		player.setAccounts(Collections.singletonList(account));

		TeamRosterDto team = new TeamRosterDto();
		team.setId("team-1");
		team.setName("Red Team");
		team.setPlayers(Collections.singletonList(player));

		TeamsResponseDto resp = new TeamsResponseDto();
		resp.setTeams(Collections.singletonList(team));
		return resp;
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> rsnToTeamId() throws Exception
	{
		Field field = TeamIconService.class.getDeclaredField("rsnToTeamId");
		field.setAccessible(true);
		return (Map<String, String>) field.get(service);
	}

	@SuppressWarnings("unchecked")
	private void seedSpriteIndex(String teamId, int index) throws Exception
	{
		Field field = TeamIconService.class.getDeclaredField("teamToSpriteIndex");
		field.setAccessible(true);
		((Map<String, Integer>) field.get(service)).put(teamId, index);
	}

	@Test
	public void fetchMatchesByRsnNotDiscordNickname() throws Exception
	{
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenReturn(response(200, gson.toJson(rosterWithOneTeam())));

		service.start();

		Map<String, String> rsnMap = rsnToTeamId();
		assertEquals("team-1", rsnMap.get("zezima"));
		assertNull(rsnMap.get("bob (discord)"));
	}

	@Test
	public void chatMessageIsBadgedWhenRsnMatchesRegisteredTeam() throws Exception
	{
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenReturn(response(200, gson.toJson(rosterWithOneTeam())));

		service.start();
		seedSpriteIndex("team-1", 5);

		ChatMessage event = new ChatMessage();
		event.setName("Zezima");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node).setName("<img=5>Zezima");
	}

	@Test
	public void chatMessageUnchangedWhenNoTeamMatch() throws Exception
	{
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenReturn(response(200, gson.toJson(rosterWithOneTeam())));

		service.start();

		ChatMessage event = new ChatMessage();
		event.setName("SomeRandomGuy");
		net.runelite.api.MessageNode node = mock(net.runelite.api.MessageNode.class);
		event.setMessageNode(node);

		service.onChatMessage(event);

		verify(node, never()).setName(any());
	}

	@Test
	public void chatMessageIgnoredWhenIconsDisabled() throws Exception
	{
		when(config.showTeamIconsInChat()).thenReturn(false);
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenReturn(response(200, gson.toJson(rosterWithOneTeam())));

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
		when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenReturn(response(500, ""));

		service.start();

		assertEquals(0, rsnToTeamId().size());
	}
}

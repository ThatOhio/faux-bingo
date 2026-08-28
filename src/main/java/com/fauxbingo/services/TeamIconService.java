package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.TeamAccountDto;
import com.fauxbingo.services.data.TeamPlayerDto;
import com.fauxbingo.services.data.TeamRosterDto;
import com.fauxbingo.services.data.TeamsResponseDto;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the full team roster from GET /v1/teams and badges clan chat names with their team's
 * icon. Matching is on accounts[].displayName (the RSN) - players[].memberName is a Discord
 * nickname and must never be matched against an RSN (docs/v1-api.md, GET /v1/teams).
 *
 * Icons are downloaded from GET {apiBaseUrl}/v1/teams/{teamId}/icon. Every URL this service
 * contacts is built from the user-configured API base plus a hardcoded path - no URL is ever
 * taken out of an API response.
 */
@Slf4j
@Singleton
public class TeamIconService
{
	private static final String TEAMS_PATH = "/v1/teams";
	private static final String ICON_SEGMENT = "icon";

	private final Client client;
	private final FauxBingoConfig config;
	private final Provider<String> apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private final Map<String, String> rsnToTeamId = new HashMap<>();     // lowercase+trimmed RSN -> team id
	private final Map<String, Integer> teamToSpriteIndex = new HashMap<>(); // team id -> mod icon array index
	private final Set<String> iconFetchFailures = new HashSet<>();          // team ids whose icon 404'd or failed to decode
	private int iconsRegisteredCount = 0;
	private volatile int initialModIconsLength = -1;                        // captured on client thread before first fetch
	private ScheduledFuture<?> refreshTask = null;

	@Inject
	public TeamIconService(
		Client client,
		FauxBingoConfig config,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) Provider<String> apiBaseUrl,
		OkHttpClient okHttpClient,
		Gson gson,
		ScheduledExecutorService executor,
		ClientThread clientThread
	)
	{
		this.client = client;
		this.config = config;
		this.apiBaseUrl = apiBaseUrl;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
		this.clientThread = clientThread;
	}

	public void start()
	{
		if (refreshTask != null && !refreshTask.isCancelled())
		{
			return;
		}

		clientThread.invokeLater(() -> {
			initialModIconsLength = client.getModIcons().length;
			refreshTask = executor.scheduleAtFixedRate(this::fetchTeamData, 0, 5, TimeUnit.MINUTES);
		});
	}

	private void fetchTeamData()
	{
		if (!enabled())
		{
			return;
		}

		try
		{
			Request request = new Request.Builder()
				.url(apiBaseUrl.get().replaceAll("/$", "") + TEAMS_PATH)
				.header("Authorization", "Bearer " + config.apiToken().trim())
				.build();

			TeamsResponseDto parsed;
			try (Response response = okHttpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					log.debug("Failed to fetch /v1/teams: {}", response);
					return;
				}
				parsed = gson.fromJson(response.body().charStream(), TeamsResponseDto.class);
			}

			if (parsed == null || parsed.getTeams() == null)
			{
				return;
			}

			updateRsnMap(parsed.getTeams());
			updateIcons(parsed.getTeams());
		}
		catch (Exception e)
		{
			log.debug("Error fetching team data", e);
		}
	}

	private void updateRsnMap(List<TeamRosterDto> teams)
	{
		synchronized (rsnToTeamId)
		{
			rsnToTeamId.clear();
			for (TeamRosterDto team : teams)
			{
				if (team.getId() == null || team.getPlayers() == null)
				{
					continue;
				}
				for (TeamPlayerDto player : team.getPlayers())
				{
					if (player.getAccounts() == null)
					{
						continue;
					}
					for (TeamAccountDto account : player.getAccounts())
					{
						if (account.getDisplayName() == null)
						{
							continue;
						}
						rsnToTeamId.put(account.getDisplayName().trim().toLowerCase(), team.getId());
					}
				}
			}
		}
	}

	private void updateIcons(List<TeamRosterDto> teams)
	{
		for (TeamRosterDto team : teams)
		{
			String teamId = team.getId();

			if (teamId == null || teamId.trim().isEmpty())
			{
				continue;
			}

			// The icon URL is derived from the team id, so it never changes within a session:
			// a team whose sprite is registered - or whose icon we already failed to fetch - is done.
			synchronized (teamToSpriteIndex)
			{
				if (teamToSpriteIndex.containsKey(teamId))
				{
					continue;
				}
			}
			synchronized (iconFetchFailures)
			{
				if (iconFetchFailures.contains(teamId))
				{
					continue;
				}
			}

			downloadAndRegisterIcon(teamId);
		}
	}

	/**
	 * Builds {apiBaseUrl}/v1/teams/{teamId}/icon. The team id is added as an encoded path segment so
	 * a server-supplied id cannot inject extra segments, and the result is rejected unless it is
	 * still under the hardcoded /v1/teams path.
	 */
	private HttpUrl iconUrl(String teamId)
	{
		HttpUrl base = HttpUrl.parse(apiBaseUrl.get().replaceAll("/$", ""));
		if (base == null)
		{
			return null;
		}

		HttpUrl teamsUrl = base.newBuilder()
			.addPathSegment("v1")
			.addPathSegment("teams")
			.build();

		HttpUrl url = teamsUrl.newBuilder()
			.addPathSegment(teamId)
			.addPathSegment(ICON_SEGMENT)
			.build();

		if (!url.encodedPath().startsWith(teamsUrl.encodedPath() + "/"))
		{
			log.debug("Refusing icon URL for team id {}: escapes {}", teamId, teamsUrl.encodedPath());
			return null;
		}

		return url;
	}

	private void downloadAndRegisterIcon(String teamId)
	{
		HttpUrl url = iconUrl(teamId);
		if (url == null)
		{
			rememberIconFailure(teamId);
			return;
		}

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + config.apiToken().trim())
			.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("Failed to download icon for team {}: {}", teamId, response);
				rememberIconFailure(teamId);
				return;
			}

			// A redirect would send us to a host the server picked rather than one derived from the
			// configured API base, so only accept a body that came back from the URL we asked for.
			if (!url.equals(response.request().url()))
			{
				log.debug("Ignoring redirected icon response for team {}: {}", teamId, response.request().url());
				rememberIconFailure(teamId);
				return;
			}

			BufferedImage image = ImageIO.read(response.body().byteStream());
			if (image == null)
			{
				log.debug("Failed to decode icon for team {}", teamId);
				rememberIconFailure(teamId);
				return;
			}

			BufferedImage resized = ImageUtil.resizeImage(image, 18, 16);
			clientThread.invokeLater(() -> {
				IndexedSprite sprite = ImageUtil.getImageIndexedSprite(resized, client);
				IndexedSprite[] current = client.getModIcons();
				IndexedSprite[] updated = Arrays.copyOf(current, current.length + 1);
				updated[current.length] = sprite;
				client.setModIcons(updated);
				iconsRegisteredCount++;

				synchronized (teamToSpriteIndex)
				{
					teamToSpriteIndex.put(teamId, current.length);
				}
			});
		}
		catch (Exception e)
		{
			log.debug("Error registering icon for team " + teamId, e);
			rememberIconFailure(teamId);
		}
	}

	private void rememberIconFailure(String teamId)
	{
		synchronized (iconFetchFailures)
		{
			iconFetchFailures.add(teamId);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!enabled() || !config.showTeamIconsInChat())
		{
			return;
		}

		String name = event.getName();
		if (name == null)
		{
			return;
		}

		String sanitizedName = Text.removeTags(name).trim().toLowerCase();
		String teamId;
		synchronized (rsnToTeamId)
		{
			teamId = rsnToTeamId.get(sanitizedName);
		}

		if (teamId == null)
		{
			return;
		}

		Integer index;
		synchronized (teamToSpriteIndex)
		{
			index = teamToSpriteIndex.get(teamId);
		}

		if (index == null)
		{
			return;
		}

		event.getMessageNode().setName("<img=" + index + ">" + event.getName());
	}

	public void shutdown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}

		clientThread.invokeLater(() -> {
			if (initialModIconsLength >= 0)
			{
				int expectedLength = initialModIconsLength + iconsRegisteredCount;
				if (client.getModIcons().length == expectedLength)
				{
					// No other plugin has appended after us — safe to truncate.
					client.setModIcons(Arrays.copyOf(client.getModIcons(), initialModIconsLength));
				}
				else
				{
					// Another plugin has appended sprites after ours. Truncating would corrupt
					// their indices. Leave the array intact and accept that our sprites are leaked
					// for the remainder of this session.
					log.debug("Skipping modIcons truncation: array length {} does not match expected {}. " +
							"Another plugin has appended sprites after TeamIconService.",
						client.getModIcons().length, expectedLength);
				}
			}
		});

		iconsRegisteredCount = 0;
		initialModIconsLength = -1;

		synchronized (rsnToTeamId)
		{
			rsnToTeamId.clear();
		}
		synchronized (teamToSpriteIndex)
		{
			teamToSpriteIndex.clear();
		}
		synchronized (iconFetchFailures)
		{
			iconFetchFailures.clear();
		}
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && config.apiToken() != null && !config.apiToken().trim().isEmpty() && !apiBaseUrl.get().isEmpty();
	}
}

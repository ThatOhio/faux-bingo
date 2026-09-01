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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import okhttp3.CacheControl;
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
	private static final long ICON_RETRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30);

	private final Client client;
	private final FauxBingoConfig config;
	private final Provider<String> apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private final Map<String, String> rsnToTeamId = new HashMap<>();     // standardized RSN -> team id
	private final Map<String, Integer> teamToSpriteIndex = new HashMap<>(); // team id -> mod icon array index
	private final Map<String, Long> iconFetchFailures = new HashMap<>();    // team id -> when its icon last failed to fetch or decode
	private int iconsRegisteredCount = 0;
	private volatile int initialModIconsLength = -1;                        // captured on client thread before first fetch
	private final AtomicBoolean started = new AtomicBoolean(false);
	private volatile ScheduledFuture<?> refreshTask = null;

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
		// Claimed here rather than by checking refreshTask, which stays null until the lambda below
		// reaches the client thread: two start() calls in quick succession - onConfigChanged lands
		// one on every apiToken/apiBaseUrl/enableBingoApi edit - would otherwise both schedule a
		// task, and only the second could ever be cancelled.
		if (!started.compareAndSet(false, true))
		{
			return;
		}

		clientThread.invokeLater(() -> {
			if (!started.get())
			{
				// shutdown() ran before this reached the client thread.
				return;
			}
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
					// Warn, not debug: an unusable roster silently disables every chat icon, and
					// the sibling services already warn on the same 401 (MeService, PresenceService).
					if (response.code() == 401)
					{
						log.warn("GET /v1/teams returned 401, token is missing or unknown. Team chat icons stay off until it is fixed.");
					}
					else
					{
						log.warn("GET /v1/teams returned {}: {}", response.code(), response.message());
					}
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
						// Text.standardize, not trim+toLowerCase: the game encodes spaces in chat
						// names as \u00A0, so an RSN like "DH Herc" arrives as "dh\u00a0herc" and
						// would never match the API's plain space. Both sides must normalize the
						// same way or multi-word RSNs are unmatchable.
						String rsn = Text.standardize(account.getDisplayName());
						if (rsn.isEmpty())
						{
							continue;
						}
						rsnToTeamId.put(rsn, team.getId());
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

			// A team whose sprite is already registered is done for this session - the icon URL is
			// derived from the team id, so it never changes.
			synchronized (teamToSpriteIndex)
			{
				if (teamToSpriteIndex.containsKey(teamId))
				{
					continue;
				}
			}
			// A failure is remembered for a cooldown rather than the whole session: an icon that
			// 404'd because it had not been uploaded yet, or a download that hit a network blip,
			// has to be able to recover without the user restarting the client.
			synchronized (iconFetchFailures)
			{
				Long failedAt = iconFetchFailures.get(teamId);
				if (failedAt != null && System.currentTimeMillis() - failedAt < ICON_RETRY_COOLDOWN_MS)
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

		// RuneLite's injected OkHttpClient carries a shared on-disk cache, and the icon route
		// answers with `Cache-Control: public, max-age=3600` and no validator - so without this an
		// icon replaced on the site stays stale for an hour, across plugin toggles and client
		// restarts alike. This runs once per team per session, so always going to the network is
		// cheap.
		Request request = new Request.Builder()
			.url(url)
			.cacheControl(CacheControl.FORCE_NETWORK)
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
			iconFetchFailures.put(teamId, System.currentTimeMillis());
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

		String sanitizedName = Text.standardize(name);
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

		event.getMessageNode().setName("<img=" + index + ">" + name);
		// The chatbox line for this message may already be built, and nothing rebuilds it on its
		// own - without this the badge does not show until some later message forces a rebuild.
		client.refreshChat();
	}

	public void shutdown()
	{
		started.set(false);

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

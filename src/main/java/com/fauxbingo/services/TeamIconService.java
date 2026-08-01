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
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches the full team roster from GET /v1/teams and badges clan chat names with their team's
 * icon. Matching is on accounts[].displayName (the RSN) - players[].memberName is a Discord
 * nickname and must never be matched against an RSN (docs/v1-api.md, GET /v1/teams).
 */
@Slf4j
@Singleton
public class TeamIconService
{
	private static final String TEAMS_PATH = "/v1/teams";

	private final Client client;
	private final FauxBingoConfig config;
	private final String apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private final Map<String, String> rsnToTeamId = new HashMap<>();     // lowercase+trimmed RSN -> team id
	private final Map<String, Integer> teamToSpriteIndex = new HashMap<>(); // team id -> mod icon array index
	private final Map<String, String> teamToIconUrl = new HashMap<>();      // team id -> registered URL (change detection + deduplication)
	private int iconsRegisteredCount = 0;
	private volatile int initialModIconsLength = -1;                        // captured on client thread before first fetch
	private ScheduledFuture<?> refreshTask = null;

	@Inject
	public TeamIconService(
		Client client,
		FauxBingoConfig config,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) String apiBaseUrl,
		OkHttpClient okHttpClient,
		Gson gson,
		ScheduledExecutorService executor,
		ClientThread clientThread
	)
	{
		this.client = client;
		this.config = config;
		this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "";
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
				.url(apiBaseUrl.replaceAll("/$", "") + TEAMS_PATH)
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
			String url = team.getIconUrl();

			if (teamId == null || url == null)
			{
				continue;
			}

			if (url.equals(teamToIconUrl.get(teamId)))
			{
				continue;
			}

			// Check if another team already has the same URL registered
			String existingTeamWithSameUrl = null;
			synchronized (teamToIconUrl)
			{
				for (Map.Entry<String, String> entry : teamToIconUrl.entrySet())
				{
					if (url.equals(entry.getValue()))
					{
						existingTeamWithSameUrl = entry.getKey();
						break;
					}
				}
			}

			if (existingTeamWithSameUrl != null)
			{
				synchronized (teamToSpriteIndex)
				{
					Integer existingIndex = teamToSpriteIndex.get(existingTeamWithSameUrl);
					if (existingIndex != null)
					{
						teamToSpriteIndex.put(teamId, existingIndex);
						synchronized (teamToIconUrl)
						{
							teamToIconUrl.put(teamId, url);
						}
						continue;
					}
				}
			}

			downloadAndRegisterIcon(teamId, url);
		}
	}

	private void downloadAndRegisterIcon(String teamId, String url)
	{
		Request request = new Request.Builder().url(url).build();
		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("Failed to download icon for team {}: {}", teamId, response);
				return;
			}

			BufferedImage image = ImageIO.read(response.body().byteStream());
			if (image == null)
			{
				log.debug("Failed to decode icon for team {}", teamId);
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
				synchronized (teamToIconUrl)
				{
					teamToIconUrl.put(teamId, url);
				}
			});
		}
		catch (Exception e)
		{
			log.debug("Error registering icon for team " + teamId, e);
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
		synchronized (teamToIconUrl)
		{
			teamToIconUrl.clear();
		}
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && config.apiToken() != null && !config.apiToken().trim().isEmpty() && !apiBaseUrl.isEmpty();
	}
}

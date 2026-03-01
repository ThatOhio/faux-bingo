package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.handlers.EventHandler;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Service for fetching and displaying team icons in chat.
 */
@Slf4j
public class TeamIconService
{
	private static class TeamIconDto
	{
		@SerializedName("teamName")
		String teamName;
		@SerializedName("teamIcon")
		String teamIcon;
	}

	private static class PlayerTeamDto
	{
		@SerializedName("character")
		String character;
		@SerializedName("teamName")
		String teamName;
	}

	private final Client client;
	private final FauxBingoConfig config;
	private final String apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final ClientThread clientThread;

	private final Map<String, String> playerToTeam = new HashMap<>();       // lowercase+trimmed character name -> team name
	private final Map<String, Integer> teamToSpriteIndex = new HashMap<>(); // team name -> mod icon array index
	private final Map<String, String> teamToIconUrl = new HashMap<>();      // team name -> registered URL (change detection + deduplication)
	private int iconsRegisteredCount = 0;
	private volatile int initialModIconsLength = -1;                        // captured on client thread before first fetch
	private ScheduledFuture<?> refreshTask = null;

	public TeamIconService(
		Client client,
		FauxBingoConfig config,
		String apiBaseUrl,
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
		if (!config.enableBingoApi())
		{
			return;
		}

		try
		{
			// 1. Fetch teamIcons endpoint
			Request teamIconsRequest = new Request.Builder()
				.url(apiBaseUrl + "/api/BingoConfig/teamIcons")
				.build();
			List<TeamIconDto> teamIcons;
			try (Response response = okHttpClient.newCall(teamIconsRequest).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					log.debug("Failed to fetch team icons: {}", response);
					return;
				}
				teamIcons = Arrays.asList(gson.fromJson(response.body().charStream(), TeamIconDto[].class));
			}

			// 2. Fetch teams endpoint
			Request teamsRequest = new Request.Builder()
				.url(apiBaseUrl + "/api/BingoConfig/teams")
				.build();
			List<PlayerTeamDto> playerTeams;
			try (Response response = okHttpClient.newCall(teamsRequest).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					log.debug("Failed to fetch player teams: {}", response);
					return;
				}
				playerTeams = Arrays.asList(gson.fromJson(response.body().charStream(), PlayerTeamDto[].class));
			}

			// 3. Update playerToTeam
			synchronized (playerToTeam)
			{
				playerToTeam.clear();
				for (PlayerTeamDto dto : playerTeams)
				{
					if (dto.character != null && dto.teamName != null)
					{
						playerToTeam.put(dto.character.trim().toLowerCase(), dto.teamName);
					}
				}
			}

			// 4. Update teamIcons
			for (TeamIconDto dto : teamIcons)
			{
				String teamName = dto.teamName;
				String url = dto.teamIcon;

				if (teamName == null || url == null)
				{
					continue;
				}

				if (url.equals(teamToIconUrl.get(teamName)))
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
							teamToSpriteIndex.put(teamName, existingIndex);
							synchronized (teamToIconUrl)
							{
								teamToIconUrl.put(teamName, url);
							}
							continue;
						}
					}
				}

				downloadAndRegisterIcon(teamName, url);
			}
		}
		catch (Exception e)
		{
			log.debug("Error fetching team data", e);
		}
	}

	private void downloadAndRegisterIcon(String teamName, String url)
	{
		Request request = new Request.Builder().url(url).build();
		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				log.debug("Failed to download icon for team {}: {}", teamName, response);
				return;
			}

			BufferedImage image = ImageIO.read(response.body().byteStream());
			if (image == null)
			{
				log.debug("Failed to decode icon for team {}", teamName);
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
					teamToSpriteIndex.put(teamName, current.length);
				}
				synchronized (teamToIconUrl)
				{
					teamToIconUrl.put(teamName, url);
				}
			});
		}
		catch (Exception e)
		{
			log.debug("Error registering icon for team " + teamName, e);
		}
	}

	public EventHandler<ChatMessage> createChatHandler()
	{
		return new EventHandler<ChatMessage>()
		{
			@Override
			public void handle(ChatMessage event)
			{
				if (!config.enableBingoApi() || !config.showTeamIconsInChat())
				{
					return;
				}

				String name = event.getName();
				if (name == null)
				{
					return;
				}

				String sanitizedName = Text.removeTags(name).trim().toLowerCase();
				String teamName;
				synchronized (playerToTeam)
				{
					teamName = playerToTeam.get(sanitizedName);
				}

				if (teamName == null)
				{
					return;
				}

				Integer index;
				synchronized (teamToSpriteIndex)
				{
					index = teamToSpriteIndex.get(teamName);
				}

				if (index == null)
				{
					return;
				}

				event.getMessageNode().setName("<img=" + index + ">" + event.getName());
			}

			@Override
			public Class<ChatMessage> getEventType()
			{
				return ChatMessage.class;
			}
		};
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

		synchronized (playerToTeam)
		{
			playerToTeam.clear();
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
}

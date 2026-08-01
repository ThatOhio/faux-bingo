package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.MeResponseDto;
import com.fauxbingo.services.data.TeamDto;
import com.google.gson.Gson;
import java.awt.Color;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Identity lookup for the plugin: GET /v1/me resolves the player token to a member and their
 * team (name, icon, colors, discordScreenshotWebhookUrl). Replaces the old bingoconfig endpoint.
 * Team name/colour are still auto-applied to the overlay config, same as before; dateTimeColor
 * has no server-side equivalent and stays user-controlled.
 */
@Slf4j
@Singleton
public class MeService
{
	private static final String ME_PATH = "/v1/me";
	private static final long CACHE_WINDOW_MS = 30_000;
	private static final long INITIAL_RETRY_DELAY_MS = 1_000;
	private static final long MAX_RETRY_DELAY_MS = 60_000;
	private static final String CONFIG_GROUP = "fauxbingo";
	private static final long REFRESH_INTERVAL_MINS = 5;

	private final FauxBingoConfig config;
	private final ConfigManager configManager;
	private final String apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private volatile MeResponseDto cachedMe = null;
	private volatile long lastSuccessfulFetchMs = 0;
	private ScheduledFuture<?> retryTask = null;
	private ScheduledFuture<?> refreshTask = null;
	private final AtomicBoolean retryCancelled = new AtomicBoolean(false);
	private long nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
	private String currentCharacterName = null;

	@Inject
	public MeService(FauxBingoConfig config, ConfigManager configManager,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) String apiBaseUrl, OkHttpClient okHttpClient,
		Gson gson, ScheduledExecutorService executor)
	{
		this.config = config;
		this.configManager = configManager;
		this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "";
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
	}

	public void start()
	{
		if (refreshTask != null && !refreshTask.isCancelled())
		{
			return;
		}
		refreshTask = executor.scheduleAtFixedRate(this::periodicRefresh, REFRESH_INTERVAL_MINS, REFRESH_INTERVAL_MINS, TimeUnit.MINUTES);
	}

	public void shutdown()
	{
		if (refreshTask != null)
		{
			refreshTask.cancel(false);
			refreshTask = null;
		}
		retryCancelled.set(true);
		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
	}

	/** Trigger fetch on login. Skips if within 30s of last successful call. */
	public void onLogin(String characterName)
	{
		if (!enabled() || characterName == null || characterName.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		boolean nameChanged = !characterName.equals(currentCharacterName);

		if (!nameChanged && cachedMe != null && (now - lastSuccessfulFetchMs) < CACHE_WINDOW_MS)
		{
			log.debug("Skipping /v1/me fetch, within 30s cache window");
			return;
		}

		if (!nameChanged && retryTask != null)
		{
			log.debug("/v1/me fetch already scheduled for {}", characterName);
			return;
		}

		currentCharacterName = characterName;
		if (nameChanged)
		{
			cachedMe = null;
			lastSuccessfulFetchMs = 0;
		}

		retryCancelled.set(false);
		nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
		scheduleFetch(nextRetryDelayMs);
	}

	/** Cancel retries when character logs out. */
	public void onLogout()
	{
		retryCancelled.set(true);
		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
	}

	/** Force a refetch, e.g. after the token or enableBingoApi config changes. */
	public void refresh(String characterName)
	{
		if (characterName == null || characterName.isEmpty() || !enabled())
		{
			return;
		}

		currentCharacterName = characterName;
		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
		nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
		retryCancelled.set(false);
		doFetch(true);
	}

	public MeResponseDto getCachedMe()
	{
		return cachedMe;
	}

	/** Null when the team has no webhook configured, or nothing has been fetched yet. */
	public String getDiscordScreenshotWebhookUrl()
	{
		MeResponseDto me = cachedMe;
		TeamDto team = me != null ? me.getTeam() : null;
		return team != null ? team.getDiscordScreenshotWebhookUrl() : null;
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && config.apiToken() != null && !config.apiToken().trim().isEmpty() && !apiBaseUrl.isEmpty();
	}

	private void periodicRefresh()
	{
		if (!enabled() || currentCharacterName == null || currentCharacterName.isEmpty())
		{
			return;
		}

		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
		retryCancelled.set(false);
		doFetch(false);
	}

	private void scheduleFetch(long delayMs)
	{
		if (retryCancelled.get())
		{
			return;
		}

		retryTask = executor.schedule(() -> {
			if (retryCancelled.get())
			{
				return;
			}
			doFetch(true);
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	private void doFetch(boolean shouldRetry)
	{
		if (!enabled())
		{
			return;
		}

		Request request = new Request.Builder()
			.url(apiBaseUrl.replaceAll("/$", "") + ME_PATH)
			.header("Authorization", "Bearer " + config.apiToken().trim())
			.get()
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("/v1/me fetch failed: {}", e.getMessage());
				if (shouldRetry)
				{
					scheduleRetry();
				}
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					if (response.code() == 401)
					{
						log.warn("/v1/me returned 401, token is missing or unknown. Not retrying until login/config changes.");
						return;
					}

					if (response.isSuccessful())
					{
						String body = response.body() != null ? response.body().string() : "";
						MeResponseDto parsed = gson.fromJson(body, MeResponseDto.class);
						if (parsed != null)
						{
							cachedMe = parsed;
							applyTeamOverlayConfig(parsed.getTeam());
							lastSuccessfulFetchMs = System.currentTimeMillis();
							nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
							retryTask = null;
							log.info("Fetched /v1/me for {}", currentCharacterName);
						}
						else if (shouldRetry)
						{
							scheduleRetry();
						}
					}
					else
					{
						log.debug("/v1/me returned {}: {}", response.code(), response.message());
						if (shouldRetry)
						{
							scheduleRetry();
						}
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	/**
	 * Auto-configures the overlay from the team's name and primary colour, same as the old
	 * bingoconfig teamConfig. dateTimeColor has no server-side equivalent and stays user-set.
	 */
	private void applyTeamOverlayConfig(TeamDto team)
	{
		if (team == null || team.getName() == null || team.getName().trim().isEmpty())
		{
			return;
		}

		Color teamNameColor = parseHexColor(team.getColors() != null ? team.getColors().getPrimaryBackground() : null);

		try
		{
			configManager.setConfiguration(CONFIG_GROUP, "displayDateTime", true);
			configManager.setConfiguration(CONFIG_GROUP, "teamName", team.getName().trim());
			if (teamNameColor != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, "teamNameColor", teamNameColor);
			}
		}
		catch (Exception e)
		{
			log.info("Failed to update team overlay configuration: {}", e.getMessage());
		}
	}

	/** Validates hex string (with or without #), exactly 6 hex digits. Returns Color or null if invalid. */
	private static Color parseHexColor(String hex)
	{
		if (hex == null)
		{
			return null;
		}
		String s = hex.startsWith("#") ? hex.substring(1) : hex;
		if (s.length() != 6)
		{
			return null;
		}
		for (int i = 0; i < 6; i++)
		{
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')))
			{
				return null;
			}
		}
		try
		{
			int rgb = Integer.parseInt(s, 16);
			return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private void scheduleRetry()
	{
		if (retryCancelled.get())
		{
			return;
		}

		long delay = nextRetryDelayMs;
		nextRetryDelayMs = Math.min(MAX_RETRY_DELAY_MS, nextRetryDelayMs * 2);
		scheduleFetch(delay);
	}
}

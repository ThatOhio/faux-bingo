package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.awt.Color;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fetches character-specific bingo config (webhooks, items with optional source filtering)
 * from the logging API. Caches in-memory with 30s window. Retries with exponential backoff.
 */
@Slf4j
public class BingoConfigService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String BINGO_CONFIG_PATH = "/api/bingoconfig";
	private static final long CACHE_WINDOW_MS = 30_000;
	private static final long INITIAL_RETRY_DELAY_MS = 1_000;
	private static final long MAX_RETRY_DELAY_MS = 60_000;
	/** Config group for plugin config (FauxBingoConfig @ConfigGroup). */
	private static final String CONFIG_GROUP = "fauxbingo";

	private final Client client;
	private final FauxBingoConfig config;
	private final ConfigManager configManager;
	private final String apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private volatile BingoConfigData cachedData = null;
	private volatile long lastSuccessfulFetchMs = 0;
	private ScheduledFuture<?> retryTask = null;
	private final AtomicBoolean retryCancelled = new AtomicBoolean(false);
	private long nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
	private String currentCharacterName = null;

	public BingoConfigService(Client client, FauxBingoConfig config, ConfigManager configManager, String apiBaseUrl, OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.configManager = configManager;
		this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "";
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
	}

	/**
	 * Trigger fetch on login. Skips if within 30s of last successful call.
	 */
	public void onLogin(String characterName)
	{
		if (!config.enableBingoApi() || characterName == null || characterName.isEmpty())
		{
			return;
		}
		if (apiBaseUrl.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		boolean nameChanged = !characterName.equals(currentCharacterName);

		retryCancelled.set(false);

		if (!nameChanged && cachedData != null && (now - lastSuccessfulFetchMs) < CACHE_WINDOW_MS)
		{
			log.debug("Skipping bingo config fetch, within 30s cache window");
			return;
		}

		if (!nameChanged && retryTask != null)
		{
			log.debug("Bingo config fetch already scheduled for {}", characterName);
			return;
		}

		currentCharacterName = characterName;
		if (nameChanged)
		{
			cachedData = null;
			lastSuccessfulFetchMs = 0;
		}

		retryCancelled.set(false);
		nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
		scheduleFetch(characterName, apiBaseUrl, nextRetryDelayMs);
	}

	/**
	 * Cancel retries when character logs out.
	 */
	public void onLogout()
	{
		retryCancelled.set(true);
		if (retryTask != null)
		{
			retryTask.cancel(false);
			retryTask = null;
		}
	}

	/**
	 * Returns cached bingo config or null if unavailable.
	 */
	public BingoConfigData getCachedConfig()
	{
		return cachedData;
	}

	/**
	 * Merges config webhooks with API webhooks. Deduplicates: trim, remove trailing slash, case-sensitive.
	 * Returns newline-separated URLs.
	 */
	public String getEffectiveWebhookUrls(String configWebhooks)
	{
		java.util.Set<String> seen = new java.util.LinkedHashSet<>();
		StringBuilder result = new StringBuilder();

		for (String url : splitWebhooks(configWebhooks))
		{
			String normalized = normalizeWebhookUrl(url);
			if (!normalized.isEmpty() && seen.add(normalized))
			{
				if (result.length() > 0) result.append("\n");
				result.append(url.trim());
			}
		}

		BingoConfigData data = cachedData;
		if (data != null && data.getWebhooks() != null)
		{
			for (String url : data.getWebhooks())
			{
				String normalized = normalizeWebhookUrl(url);
				if (!normalized.isEmpty() && seen.add(normalized))
				{
					if (result.length() > 0) result.append("\n");
					result.append(url.trim());
				}
			}
		}

		return result.toString();
	}

	private static java.util.List<String> splitWebhooks(String s)
	{
		if (s == null || s.isEmpty()) return java.util.Collections.emptyList();
		return java.util.Arrays.stream(s.split("[\n,]"))
			.map(String::trim)
			.filter(x -> !x.isEmpty())
			.collect(Collectors.toList());
	}

	private static String normalizeWebhookUrl(String url)
	{
		if (url == null) return "";
		String t = url.trim();
		if (t.isEmpty()) return "";
		return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
	}

	private void scheduleFetch(String characterName, String baseUrl, long delayMs)
	{
		if (retryCancelled.get())
		{
			return;
		}

		retryTask = executor.schedule(() -> {
			if (retryCancelled.get() || client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			doFetch(characterName, baseUrl);
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	private void doFetch(String characterName, String baseUrl)
	{
		String url = buildUrl(baseUrl, characterName);
		log.debug("Fetching bingo config for {} at {}", characterName, url);
		if (url.isEmpty())
		{
			return;
		}

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, "{}"))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bingo config fetch failed for {}: {}", characterName, e.getMessage());
				scheduleRetry(characterName, baseUrl);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					if (response.isSuccessful())
					{
						String body = response.body() != null ? response.body().string() : "";
						BingoConfigResponse parsed = gson.fromJson(body, BingoConfigResponse.class);
						if (parsed != null)
						{
							cachedData = BingoConfigData.from(parsed);
							applyTeamConfigFromResponse(parsed);
							lastSuccessfulFetchMs = System.currentTimeMillis();
							nextRetryDelayMs = INITIAL_RETRY_DELAY_MS;
							retryTask = null;
							log.info("Bingo config fetched for {}", characterName);
						}
						else
						{
							scheduleRetry(characterName, baseUrl);
						}
					}
					else
					{
						log.debug("Bingo config API returned {}: {}", response.code(), response.message());
						scheduleRetry(characterName, baseUrl);
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
	 * If teamConfig is present and valid, updates overlay config (displayOverlay, displayDateTime,
	 * teamName, teamNameColor, dateTimeColor). All-or-nothing: any validation failure skips all updates.
	 */
	private void applyTeamConfigFromResponse(BingoConfigResponse parsed)
	{
		TeamConfigDto tc = parsed != null ? parsed.teamConfig : null;
		if (tc == null)
		{
			return;
		}

		String teamName = tc.teamName;
		if (teamName == null || teamName.trim().isEmpty())
		{
			log.info("teamConfig validation failed: teamName is null or empty");
			return;
		}

		Color teamNameColor = parseHexColor(tc.teamNameColor);
		if (teamNameColor == null)
		{
			log.info("teamConfig validation failed: invalid hex color for teamNameColor: {}", tc.teamNameColor);
			return;
		}

		Color dateTimeColor = parseHexColor(tc.dateTimeColor);
		if (dateTimeColor == null)
		{
			log.info("teamConfig validation failed: invalid hex color for dateTimeColor: {}", tc.dateTimeColor);
			return;
		}

		try
		{
			configManager.setConfiguration(CONFIG_GROUP, "displayOverlay", true);
			configManager.setConfiguration(CONFIG_GROUP, "displayDateTime", true);
			configManager.setConfiguration(CONFIG_GROUP, "teamName", teamName.trim());
			configManager.setConfiguration(CONFIG_GROUP, "teamNameColor", teamNameColor);
			configManager.setConfiguration(CONFIG_GROUP, "dateTimeColor", dateTimeColor);
		}
		catch (Exception e)
		{
			log.info("Failed to update team overlay configuration: {}", e.getMessage());
		}
	}

	/**
	 * Validates hex string (with or without #), exactly 6 hex digits. Returns Color or null if invalid.
	 */
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

	private void scheduleRetry(String characterName, String baseUrl)
	{
		if (retryCancelled.get())
		{
			return;
		}

		long delay = nextRetryDelayMs;
		nextRetryDelayMs = Math.min(MAX_RETRY_DELAY_MS, nextRetryDelayMs * 2);
		scheduleFetch(characterName, baseUrl, delay);
	}

	private String buildUrl(String base, String characterName)
	{
		String baseClean = base == null ? "" : base.replaceAll("/$", "");
		if (baseClean.isEmpty())
		{
			return "";
		}
		try
		{
			String encoded = URLEncoder.encode(characterName, StandardCharsets.UTF_8.name());
			return baseClean + BINGO_CONFIG_PATH + "?character=" + encoded;
		}
		catch (Exception e)
		{
			log.warn("Failed to encode character name", e);
			return "";
		}
	}

	@Getter
	@EqualsAndHashCode
	public static class BingoConfigData
	{
		private final List<String> webhooks;
		private final List<BingoConfigItem> items;

		public BingoConfigData(List<String> webhooks, List<BingoConfigItem> items)
		{
			this.webhooks = webhooks != null ? List.copyOf(webhooks) : Collections.emptyList();
			this.items = items != null ? List.copyOf(items) : Collections.emptyList();
		}

		static BingoConfigData from(BingoConfigResponse r)
		{
			List<BingoConfigItem> items = Collections.emptyList();
			if (r.items != null)
			{
				items = r.items.stream()
					.map(dto -> new BingoConfigItem(dto.name, dto.source))
					.collect(Collectors.toList());
			}
			return new BingoConfigData(r.webhooks, items);
		}
	}

	@Getter
	public static class BingoConfigItem
	{
		private final String name;
		private final String source;

		public BingoConfigItem(String name, String source)
		{
			this.name = name;
			this.source = source == null || source.isEmpty() ? null : source;
		}
	}

	private static class BingoConfigResponse
	{
		@SerializedName("webhooks")
		List<String> webhooks;

		@SerializedName("items")
		List<BingoConfigItemDto> items;

		@SerializedName("teamConfig")
		TeamConfigDto teamConfig;
	}

	private static class TeamConfigDto
	{
		@SerializedName("teamName")
		String teamName;

		@SerializedName("teamNameColor")
		String teamNameColor;

		@SerializedName("dateTimeColor")
		String dateTimeColor;
	}

	private static class BingoConfigItemDto
	{
		@SerializedName("name")
		String name;

		@SerializedName("source")
		String source;
	}
}

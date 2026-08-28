package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.SeenRequestDto;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Presence heartbeat: POST /v1/seen every 5 minutes while actively logged in. This is how the
 * website learns about new accounts before they ever produce a drop, and how it extrapolates
 * playtime. Never called from the login screen. 5 minutes matches the server's documented
 * session-stitching cadence (docs/v1-api.md, "Presence and sessions") rather than the 5s
 * originally floated, since the server tolerates gaps up to 15 minutes anyway.
 */
@Slf4j
@Singleton
public class PresenceService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String SEEN_PATH = "/v1/seen";
	private static final long HEARTBEAT_INTERVAL_MINS = 5;

	private final Client client;
	private final ClientThread clientThread;
	private final FauxBingoConfig config;
	private final Provider<String> apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private ScheduledFuture<?> heartbeatTask = null;

	@Inject
	public PresenceService(Client client, ClientThread clientThread, FauxBingoConfig config,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) Provider<String> apiBaseUrl, OkHttpClient okHttpClient,
		Gson gson, ScheduledExecutorService executor)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiBaseUrl = apiBaseUrl;
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
	}

	public void start()
	{
		if (heartbeatTask != null && !heartbeatTask.isCancelled())
		{
			return;
		}
		heartbeatTask = executor.scheduleAtFixedRate(this::sendIfLoggedIn, HEARTBEAT_INTERVAL_MINS, HEARTBEAT_INTERVAL_MINS, TimeUnit.MINUTES);
	}

	public void shutdown()
	{
		if (heartbeatTask != null)
		{
			heartbeatTask.cancel(false);
			heartbeatTask = null;
		}
	}

	/** Fire one immediate heartbeat on login so a fresh account registers without waiting 5 minutes. */
	public void onLogin(String characterName)
	{
		sendIfLoggedIn();
	}

	private void sendIfLoggedIn()
	{
		if (!enabled())
		{
			return;
		}

		// The scheduled heartbeat runs on the executor thread, not the client thread, and
		// getAccountType() reads a varbit under the hood, which asserts client-thread-only.
		// invoke() runs inline if we're already on the client thread (the onLogin path).
		clientThread.invoke(() -> {
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}

			Player local = client.getLocalPlayer();
			if (local == null || local.getName() == null || local.getName().isEmpty())
			{
				return;
			}

			SeenRequestDto body = SeenRequestDto.builder()
				.accountHash(String.valueOf(client.getAccountHash()))
				.displayName(local.getName())
				.accountType(client.getAccountType() != null ? client.getAccountType().name() : "UNKNOWN")
				.build();

			send(body);
		});
	}

	private void send(SeenRequestDto body)
	{
		String json = gson.toJson(body);
		Request request = new Request.Builder()
			.url(apiBaseUrl.get().replaceAll("/$", "") + SEEN_PATH)
			.header("Authorization", "Bearer " + config.apiToken().trim())
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("POST /v1/seen failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					if (response.code() == 409)
					{
						log.warn("POST /v1/seen: account {} is already claimed by another player, not retrying.", body.getDisplayName());
					}
					else if (response.code() == 401)
					{
						log.warn("POST /v1/seen returned 401, token is missing or unknown.");
					}
					else if (!response.isSuccessful())
					{
						log.debug("POST /v1/seen returned {}: {}", response.code(), response.message());
					}
					else
					{
						log.info("POST /v1/seen ok for {}", body.getDisplayName());
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && config.apiToken() != null && !config.apiToken().trim().isEmpty() && !apiBaseUrl.get().isEmpty();
	}
}

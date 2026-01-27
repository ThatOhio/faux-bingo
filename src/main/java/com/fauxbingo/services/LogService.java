package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.data.LogEntry;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Service responsible for queueing and sending data logs to an external API for post Bingo statistics
 */
@Slf4j
public class LogService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int BATCH_SIZE = 10;
	private static final int FLUSH_INTERVAL_SECONDS = 30;
	private static final String LOGS_PATH = "/api/logs";
	private static final String DEATHS_PATH = "/api/deaths";

	private final Client client;
	private final FauxBingoConfig config;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Queue<LogEntry> queue = new ConcurrentLinkedQueue<>();

	public LogService(Client client, FauxBingoConfig config, OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.okHttpClient = okHttpClient;
		this.gson = gson;

		executor.scheduleAtFixedRate(this::flushQueue, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Adds an entry to the queue, or sends death logs to the deaths endpoint.
	 */
	public void log(String type, Object data)
	{
		if (!config.enableLoggingApi() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		LogEntry entry = LogEntry.builder()
			.player(playerName)
			.type(type)
			.timestamp(System.currentTimeMillis())
			.data(data)
			.build();

		if ("DEATH".equals(type))
		{
			String url = buildUrl(DEATHS_PATH);
			if (url.isEmpty())
			{
				return;
			}
			sendDeath(entry, url);
			return;
		}

		if (buildUrl(LOGS_PATH).isEmpty())
		{
			return;
		}
		queue.add(entry);
		if (queue.size() >= BATCH_SIZE)
		{
			flushQueue();
		}
	}

	private synchronized void flushQueue()
	{
		String logsUrl = buildUrl(LOGS_PATH);
		if (queue.isEmpty() || logsUrl.isEmpty())
		{
			return;
		}

		List<LogEntry> batch = new ArrayList<>();
		LogEntry entry;
		while (batch.size() < BATCH_SIZE && (entry = queue.poll()) != null)
		{
			batch.add(entry);
		}

		if (batch.isEmpty())
		{
			return;
		}

		sendBatch(batch, logsUrl);
	}

	private String buildUrl(String path)
	{
		String base = config.loggingApiUrl();
		if (base == null || base.isEmpty())
		{
			return "";
		}
		return base.replaceAll("/$", "") + path;
	}

	private void sendBatch(List<LogEntry> batch, String url)
	{
		String json = gson.toJson(batch);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Error sending batch to API", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				if (!response.isSuccessful())
				{
					log.error("API returned error: {} {}", response.code(), response.message());
				}
				response.close();
			}
		});
	}

	private void sendDeath(LogEntry entry, String url)
	{
		String json = gson.toJson(entry);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Error sending death log to API", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				if (!response.isSuccessful())
				{
					log.error("Deaths API returned error: {} {}", response.code(), response.message());
				}
				response.close();
			}
		});
	}
}

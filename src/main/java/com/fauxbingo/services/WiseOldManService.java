package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * Service for interacting with the WiseOldMan API.
 */
@Slf4j
public class WiseOldManService
{
	private static final String WOM_API_HOST = "api.wiseoldman.net";
	private static final String WOM_API_VERSION = "v2";
	private static final String USER_AGENT = "FauxBingo-RuneLite-Plugin";

	private final Client client;
	private final FauxBingoConfig config;
	private final OkHttpClient okHttpClient;
	private final Gson gson;

	public WiseOldManService(Client client, FauxBingoConfig config, OkHttpClient okHttpClient)
	{
		this.client = client;
		this.config = config;
		this.okHttpClient = okHttpClient;
		this.gson = new Gson();
	}

	public void updatePlayer(String username)
	{
		if (!config.enableWomAutoUpdate())
		{
			return;
		}

		if (username == null || username.isEmpty())
		{
			log.warn("Cannot update WiseOldMan: username is null or empty");
			return;
		}

		long accountHash = client.getAccountHash();

		// Build the API URL: POST /v2/players/{username}
		HttpUrl url = new HttpUrl.Builder()
			.scheme("https")
			.host(WOM_API_HOST)
			.addPathSegment(WOM_API_VERSION)
			.addPathSegment("players")
			.addPathSegment(username)
			.build();

		// Create payload with account hash for verification
		WomPlayerUpdate payload = new WomPlayerUpdate(accountHash);
		String json = gson.toJson(payload);

		RequestBody body = RequestBody.create(
			MediaType.parse("application/json; charset=utf-8"),
			json
		);

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.post(body)
			.build();

		// Send the request asynchronously
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to update WiseOldMan stats for {}: {}", username, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					if (response.isSuccessful())
					{
						log.info("Successfully updated WiseOldMan stats for {}", username);
					}
					else
					{
						log.debug("WiseOldMan update returned status {}: {}", 
							response.code(), response.message());
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private static class WomPlayerUpdate
	{
		private final long accountHash;

		public WomPlayerUpdate(long accountHash)
		{
			this.accountHash = accountHash;
		}
	}
}

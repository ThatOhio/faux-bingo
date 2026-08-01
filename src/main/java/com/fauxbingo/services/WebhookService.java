package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import com.fauxbingo.FauxBingoConfig;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends a single Discord webhook message with an optional screenshot. Grouping/bundling of
 * related signals into one message now happens upstream in DropCorrelationService, so this class
 * is just a sender: URL parsing, multipart POST, and the game-mode annotation.
 */
@Slf4j
@Singleton
public class WebhookService
{
	private final OkHttpClient okHttpClient;
	private final Client client;
	private final FauxBingoConfig config;
	private final BingoConfigService bingoConfigService;
	private final Random random = new Random();

	private static final String[] LEAGUES_MESSAGES = {
			"This dummy is playing Leagues!",
			"Leagues: Where the drops are fake and the points don't matter!",
			"Is it really a grind if you have 16x drop rate?",
			"Playing Leagues because the main game is too hard."
	};

	private static final String[] DEADMAN_MESSAGES = {
			"Look at this brave soul playing Deadman!",
			"Living life on the edge in DMM!",
			"One misclick away from a bank rebuild."
	};

	private static final String[] FRESH_START_MESSAGES = {
			"Fresh Start, same old mistakes.",
			"Reliving the glory days in a Fresh Start World.",
			"Starting over for the 10th time in Fresh Start."
	};

	private static final String[] TOURNAMENT_MESSAGES = {
			"Practicing for a win they'll never get in a Tournament World!",
			"Playing with toys in the sandbox (Tournament World).",
			"In a Tournament World because they can't afford the gear otherwise."
	};

	/** Without a BingoConfigService the effective webhook list is whatever the user configured. */
	public WebhookService(Client client, OkHttpClient okHttpClient, FauxBingoConfig config)
	{
		this(client, okHttpClient, config, null);
	}

	@Inject
	public WebhookService(Client client, OkHttpClient okHttpClient, FauxBingoConfig config, BingoConfigService bingoConfigService)
	{
		this.client = client;
		this.okHttpClient = okHttpClient;
		this.config = config;
		this.bingoConfigService = bingoConfigService;
	}

	/**
	 * Send a webhook message, bypassing the logged-in check. Used for manual screenshots, which
	 * should go out regardless of game state.
	 */
	public void sendWebhook(String webhookUrls, String message, BufferedImage image)
	{
		sendWebhook(webhookUrls, message, image, false);
	}

	/**
	 * Send a single, already-composed webhook message to the configured URLs.
	 *
	 * @param webhookUrls    Newline/comma-separated list of webhook URLs
	 * @param message        The final message content to send
	 * @param image          Optional screenshot to attach (can be null)
	 * @param checkGameState Whether to skip sending unless the player is logged in
	 */
	public void sendWebhook(String webhookUrls, String message, BufferedImage image, boolean checkGameState)
	{
		if (checkGameState && client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		String effectiveUrls = (bingoConfigService != null)
				? bingoConfigService.getEffectiveWebhookUrls(webhookUrls)
				: webhookUrls;
		if (effectiveUrls == null || effectiveUrls.isEmpty())
		{
			return;
		}

		processWebhook(effectiveUrls, message, image);
	}

	private String getGameModeAnnotation()
	{
		EnumSet<WorldType> worldTypes = client.getWorldType();
		boolean isDeadman = false;
		boolean isLeagues = false;
		boolean isFreshStart = false;
		boolean isTournament = false;

		for (WorldType type : worldTypes) {
			String name = type.name();
			if (name.equals("DEADMAN")) {
				isDeadman = true;
			} else if (name.equals("LEAGUE") || name.equals("SEASONAL")) {
				isLeagues = true;
			} else if (name.equals("FRESH_START_WORLD")) {
				isFreshStart = true;
			} else if (name.equals("TOURNAMENT_WORLD")) {
				isTournament = true;
			}
		}

		if (isDeadman) {
			return config.funnyGameModeMessages()
					? " (" + DEADMAN_MESSAGES[random.nextInt(DEADMAN_MESSAGES.length)] + ")"
					: " (Deadman)";
		}
		if (isLeagues) {
			return config.funnyGameModeMessages()
					? " (" + LEAGUES_MESSAGES[random.nextInt(LEAGUES_MESSAGES.length)] + ")"
					: " (Leagues)";
		}
		if (isFreshStart) {
			return config.funnyGameModeMessages()
					? " (" + FRESH_START_MESSAGES[random.nextInt(FRESH_START_MESSAGES.length)] + ")"
					: " (Fresh Start)";
		}
		if (isTournament) {
			return config.funnyGameModeMessages()
					? " (" + TOURNAMENT_MESSAGES[random.nextInt(TOURNAMENT_MESSAGES.length)] + ")"
					: " (Tournament)";
		}
		return "";
	}

	private void processWebhook(String webhookUrls, String message, BufferedImage image)
	{
		String suffix = getGameModeAnnotation();
		if (!suffix.isEmpty()) {
			message += suffix;
		}

		String[] urls = webhookUrls.split("[\n,]");

		byte[] imageBytes = null;
		if (image != null) {
			imageBytes = convertImageToBytes(image);
		}

		for (String url : urls) {
			final String finalUrl = url.trim();
			if (finalUrl.isEmpty()) {
				continue;
			}

			sendToUrl(finalUrl, message, imageBytes);
		}
	}

	private byte[] convertImageToBytes(BufferedImage image)
	{
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			log.error("Error converting image to bytes", e);
			return null;
		}
	}

	private void sendToUrl(String url, String message, byte[] imageBytes)
	{
		HttpUrl httpUrl = HttpUrl.parse(url);
		if (httpUrl == null) {
			log.warn("Invalid webhook URL: {}", url);
			return;
		}

		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("content", message);

		if (imageBytes != null) {
			requestBodyBuilder.addFormDataPart("file", "screenshot.png",
					RequestBody.create(MediaType.parse("image/png"), imageBytes));
		}

		Request request = new Request.Builder()
				.url(httpUrl)
				.post(requestBodyBuilder.build())
				.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Error submitting webhook to {}", url, e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}
}

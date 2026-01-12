package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
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
 * Service responsible for sending discord webhook notifications with optional screenshots.
 */
@Slf4j
public class WebhookService
{
	private final OkHttpClient okHttpClient;

	public WebhookService(OkHttpClient okHttpClient)
	{
		this.okHttpClient = okHttpClient;
	}

	/**
	 * Send a webhook message to the configured URLs.
	 *
	 * @param webhookUrls Newline-separated list of webhook URLs
	 * @param message The message content to send
	 * @param image Optional screenshot to attach (can be null)
	 */
	public void sendWebhook(String webhookUrls, String message, BufferedImage image)
	{
		if (webhookUrls == null || webhookUrls.isEmpty())
		{
			return;
		}

		String[] urls = webhookUrls.split("\n");

		byte[] imageBytes = null;
		if (image != null)
		{
			imageBytes = convertImageToBytes(image);
		}

		for (String url : urls)
		{
			final String finalUrl = url.trim();
			if (finalUrl.isEmpty())
			{
				continue;
			}

			sendToUrl(finalUrl, message, imageBytes);
		}
	}

	private byte[] convertImageToBytes(BufferedImage image)
	{
		try (ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			log.error("Error converting image to bytes", e);
			return null;
		}
	}

	private void sendToUrl(String url, String message, byte[] imageBytes)
	{
		HttpUrl httpUrl = HttpUrl.parse(url);
		if (httpUrl == null)
		{
			log.warn("Invalid webhook URL: {}", url);
			return;
		}

		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("content", message);

		if (imageBytes != null)
		{
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

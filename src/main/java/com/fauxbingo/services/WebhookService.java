package com.fauxbingo.services;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.Builder;
import lombok.Data;
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
	private final ScheduledExecutorService executor;
	private final List<QueuedWebhook> queue = new ArrayList<>();
	private ScheduledFuture<?> flushTask = null;

	public enum WebhookCategory
	{
		PET(1),
		RAID_LOOT(2),
		VALUABLE_DROP(3),
		COLLECTION_LOG(4),
		LOOT(5),
		MISC(6);

		private final int priority;

		WebhookCategory(int priority)
		{
			this.priority = priority;
		}

		public int getPriority()
		{
			return priority;
		}
	}

	@Data
	@Builder
	private static class QueuedWebhook
	{
		private final String webhookUrls;
		private final String message;
		private final BufferedImage image;
		private final String itemName;
		private final WebhookCategory category;
	}

	public WebhookService(OkHttpClient okHttpClient, ScheduledExecutorService executor)
	{
		this.okHttpClient = okHttpClient;
		this.executor = executor;
	}

	public void sendWebhook(String webhookUrls, String message, BufferedImage image)
	{
		sendWebhook(webhookUrls, message, image, null, WebhookCategory.MISC);
	}

	/**
	 * Send a webhook message to the configured URLs with an optional item name for bundling.
	 *
	 * @param webhookUrls Newline-separated list of webhook URLs
	 * @param message The message content to send
	 * @param image Optional screenshot to attach (can be null)
	 * @param itemName Optional item name to use for bundling related events
	 * @param category The category of the webhook for priority and bundling
	 */
	public synchronized void sendWebhook(String webhookUrls, String message, BufferedImage image, String itemName, WebhookCategory category)
	{
		if (webhookUrls == null || webhookUrls.isEmpty())
		{
			return;
		}

		queue.add(QueuedWebhook.builder()
			.webhookUrls(webhookUrls)
			.message(message)
			.image(image)
			.itemName(itemName)
			.category(category)
			.build());

		if (flushTask == null || flushTask.isDone())
		{
			flushTask = executor.schedule(this::flushBatch, 3, TimeUnit.SECONDS);
		}
	}

	private synchronized void flushBatch()
	{
		if (queue.isEmpty())
		{
			return;
		}

		// Group by webhookUrls first, in case they are different
		Map<String, List<QueuedWebhook>> byUrls = queue.stream()
			.collect(Collectors.groupingBy(QueuedWebhook::getWebhookUrls));

		for (Map.Entry<String, List<QueuedWebhook>> urlEntry : byUrls.entrySet())
		{
			String urls = urlEntry.getKey();
			List<QueuedWebhook> urlQueue = urlEntry.getValue();

			// Separate items with names and those without
			List<QueuedWebhook> namedItems = urlQueue.stream()
				.filter(q -> q.getItemName() != null)
				.collect(Collectors.toList());

			List<QueuedWebhook> unnamedItems = urlQueue.stream()
				.filter(q -> q.getItemName() == null)
				.collect(Collectors.toList());

			// Group named items by item name
			Map<String, List<QueuedWebhook>> groupedItems = namedItems.stream()
				.collect(Collectors.groupingBy(QueuedWebhook::getItemName));

			for (List<QueuedWebhook> group : groupedItems.values())
			{
				if (group.size() == 1)
				{
					QueuedWebhook single = group.get(0);
					processWebhook(urls, single.getMessage(), single.getImage());
				}
				else
				{
					sendCombinedWebhook(urls, group);
				}
			}

			for (QueuedWebhook unnamed : unnamedItems)
			{
				processWebhook(urls, unnamed.getMessage(), unnamed.getImage());
			}
		}

		queue.clear();
		flushTask = null;
	}

	private void sendCombinedWebhook(String urls, List<QueuedWebhook> group)
	{
		// Sort by priority (lower number is higher priority)
		group.sort(Comparator.comparingInt(q -> q.getCategory().getPriority()));

		QueuedWebhook primary = group.get(0);
		StringBuilder combinedMessage = new StringBuilder(primary.getMessage());

		for (int i = 1; i < group.size(); i++)
		{
			QueuedWebhook other = group.get(i);

			// Only append if it's not exactly the same message
			if (!other.getMessage().equals(primary.getMessage()))
			{
				String additionalText = getAdditionalText(other);
				if (additionalText != null)
				{
					combinedMessage.append("\n").append(additionalText);
				}
				else
				{
					combinedMessage.append("\n").append(other.getMessage());
				}
			}
		}

		// Use the first image available in the group
		BufferedImage image = group.stream()
			.map(QueuedWebhook::getImage)
			.filter(img -> img != null)
			.findFirst()
			.orElse(null);

		processWebhook(urls, combinedMessage.toString(), image);
	}

	private String getAdditionalText(QueuedWebhook webhook)
	{
		switch (webhook.getCategory())
		{
			case COLLECTION_LOG:
				return "*This item was also added to their collection log!*";
			case VALUABLE_DROP:
				return "*This was also a valuable drop!*";
			case PET:
				return "*They also received a pet!*";
			case RAID_LOOT:
				return "";
			default:
				return null;
		}
	}

	private void processWebhook(String webhookUrls, String message, BufferedImage image)
	{
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

package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.FauxBingoPlugin;
import com.fauxbingo.services.data.ActorDto;
import com.fauxbingo.services.data.CollectionLogPayloadDto;
import com.fauxbingo.services.data.DeathPayloadDto;
import com.fauxbingo.services.data.DeathSignal;
import com.fauxbingo.services.data.DetectionDto;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.EventEnvelopeDto;
import com.fauxbingo.services.data.LocationDto;
import com.fauxbingo.services.data.LootItemDto;
import com.fauxbingo.services.data.LootPayloadDto;
import com.fauxbingo.services.data.LootSourceDto;
import com.fauxbingo.services.data.MergedDropEvent;
import com.fauxbingo.services.data.PetPayloadDto;
import com.fauxbingo.services.data.ScreenshotFlagDto;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
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
 * POST /v1/events client. This is the real seam behind EventEnvelopeSink: DropCorrelationService
 * hands it one MergedDropEvent per physical drop (LOOT/COLLECTION_LOG/PET), sent immediately as
 * a batch of one; DeathHandler calls submitDeath directly, batched on a 5s/50-event timer per
 * the contract. Delivery is in-memory only - a failed batch retries with backoff, but nothing
 * survives a plugin restart or logout.
 *
 * The events endpoint is fire and forget: it answers 2xx (published) or 5xx (retry this batch)
 * and never carries a per-event verdict, so there's nothing to wait on before uploading evidence.
 * eventId is generated here, client-side, before the envelope is even built, so a captured
 * screenshot uploads immediately in parallel with the batch send rather than waiting on its
 * response.
 */
@Slf4j
@Singleton
public class EventsApiService implements EventEnvelopeSink
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String EVENTS_PATH = "/v1/events";
	private static final String SCREENSHOT_PATH_SUFFIX = "/screenshot";
	private static final int SCHEMA_VERSION = 1;
	private static final long INITIAL_RETRY_DELAY_MS = 1_000;
	private static final long MAX_RETRY_DELAY_MS = 60_000;
	private static final long DEATH_BATCH_FLUSH_MS = 5_000;
	private static final int DEATH_BATCH_MAX_SIZE = 50;

	private final Client client;
	private final FauxBingoConfig config;
	private final String apiBaseUrl;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private final AtomicInteger sequence = new AtomicInteger(0);
	private final List<EventEnvelopeDto> deathQueue = new ArrayList<>();
	private volatile long deathQueueOpenedAtMs = 0;
	private volatile ActorDto currentActor = null;

	private ScheduledFuture<?> deathFlushTask = null;

	@Inject
	public EventsApiService(Client client, FauxBingoConfig config,
		@Named(FauxBingoPlugin.API_BASE_URL_KEY) String apiBaseUrl, OkHttpClient okHttpClient,
		Gson gson, ScheduledExecutorService executor)
	{
		this.client = client;
		this.config = config;
		this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "";
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
	}

	public void start()
	{
		if (deathFlushTask == null || deathFlushTask.isCancelled())
		{
			deathFlushTask = executor.scheduleAtFixedRate(this::flushDeathQueueIfDue, 1, 1, TimeUnit.SECONDS);
		}
	}

	public void shutdown()
	{
		if (deathFlushTask != null)
		{
			deathFlushTask.cancel(false);
			deathFlushTask = null;
		}
		flushDeathQueueNow();
	}

	/** New login/username change: sequence starts back at 0 and the cached actor is dropped. */
	public void resetSession()
	{
		sequence.set(0);
		currentActor = null;
	}

	@Override
	public void accept(MergedDropEvent merged)
	{
		if (!enabled() || merged == null || merged.getPrimarySignal() == null)
		{
			return;
		}

		ActorDto actor = currentActor;
		if (actor == null)
		{
			return;
		}

		Object payload = buildPayload(merged);
		if (payload == null)
		{
			log.debug("No payload builder for merged drop type {}, dropping", merged.getType());
			return;
		}

		DropSignal primary = merged.getPrimarySignal();
		String eventId = UUID.randomUUID().toString();
		EventEnvelopeDto envelope = EventEnvelopeDto.builder()
			.eventId(eventId)
			.schemaVersion(SCHEMA_VERSION)
			.pluginVersion(PluginVersion.VERSION)
			.occurredAt(Instant.now().toString())
			.sequence(sequence.getAndIncrement())
			.replay(false)
			.dropGroupId(merged.getDropGroupId())
			.actor(actor)
			.type(merged.getType().name())
			.detection(DetectionDto.builder()
				.method(primary.getDetectionMethod().name())
				.confidence(primary.getDetectionMethod().getConfidence().name())
				.raw(primary.getRaw())
				.build())
			.payload(payload)
			.screenshot(ScreenshotFlagDto.builder().captured(merged.getScreenshot() != null).build())
			.build();

		if (merged.getScreenshot() != null)
		{
			// No verdict to wait on and eventId is already ours, so this races ahead of (and
			// independent of) the batch send below. The mediator tolerates that (docs/bingo-events-api.md §11).
			uploadScreenshot(eventId, merged.getScreenshot());
		}

		// LOOT/COLLECTION_LOG/PET post immediately, typically a batch of one, per the contract's
		// "Immediate" delivery path.
		sendBatch(Collections.singletonList(envelope), INITIAL_RETRY_DELAY_MS);
	}

	/** DEATH is the "Batched" path: flushed on a 5s timer or at 50 events, whichever first. */
	public void submitDeath(DeathSignal signal)
	{
		if (!enabled() || signal == null)
		{
			return;
		}

		ActorDto actor = currentActor;
		if (actor == null)
		{
			return;
		}

		DeathPayloadDto payload = DeathPayloadDto.builder()
			.regionId(signal.getRegionId())
			.killerName(signal.getKillerName())
			.killerKind(signal.getKillerKind())
			.build();

		EventEnvelopeDto envelope = EventEnvelopeDto.builder()
			.eventId(UUID.randomUUID().toString())
			.schemaVersion(SCHEMA_VERSION)
			.pluginVersion(PluginVersion.VERSION)
			.occurredAt(Instant.now().toString())
			.sequence(sequence.getAndIncrement())
			.replay(false)
			.actor(actor)
			.type("DEATH")
			.detection(DetectionDto.builder().method("ACTOR_DEATH").confidence("EXACT").build())
			.payload(payload)
			.screenshot(ScreenshotFlagDto.builder().captured(false).build())
			.build();

		synchronized (deathQueue)
		{
			if (deathQueue.isEmpty())
			{
				deathQueueOpenedAtMs = System.currentTimeMillis();
			}
			deathQueue.add(envelope);
			if (deathQueue.size() < DEATH_BATCH_MAX_SIZE)
			{
				return;
			}
		}
		flushDeathQueueNow();
	}

	private void flushDeathQueueIfDue()
	{
		boolean due;
		synchronized (deathQueue)
		{
			due = !deathQueue.isEmpty() && System.currentTimeMillis() - deathQueueOpenedAtMs >= DEATH_BATCH_FLUSH_MS;
		}
		if (due)
		{
			flushDeathQueueNow();
		}
	}

	private void flushDeathQueueNow()
	{
		List<EventEnvelopeDto> batch;
		synchronized (deathQueue)
		{
			if (deathQueue.isEmpty())
			{
				return;
			}
			batch = new ArrayList<>(deathQueue);
			deathQueue.clear();
		}
		sendBatch(batch, INITIAL_RETRY_DELAY_MS);
	}

	private Object buildPayload(MergedDropEvent merged)
	{
		DropSignal primary = merged.getPrimarySignal();
		switch (merged.getType())
		{
			case LOOT:
				return buildLootPayload(primary, merged);
			case COLLECTION_LOG:
				return buildCollectionLogPayload(primary);
			case PET:
				return PetPayloadDto.builder()
					.petName(merged.getPetName())
					.sourceName(merged.getSourceNameGuess())
					.build();
			default:
				return null;
		}
	}

	private LootPayloadDto buildLootPayload(DropSignal primary, MergedDropEvent merged)
	{
		LocationDto location = primary.getRegionId() != null
			? LocationDto.builder().regionId(primary.getRegionId()).plane(primary.getPlane()).build()
			: null;

		LootSourceDto source = LootSourceDto.builder()
			.kind(primary.getSourceKind() != null ? primary.getSourceKind().name() : "OTHER")
			.name(primary.getSourceName())
			.npcId(primary.getNpcId())
			.combatLevel(primary.getCombatLevel())
			.killCount(primary.getKillCount())
			.variant(primary.getVariant())
			.location(location)
			.build();

		List<LootItemDto> items = primary.getItems() == null ? Collections.emptyList() : primary.getItems().stream()
			.map(this::toLootItemDto)
			.collect(Collectors.toList());

		Long totalValueGe = (primary.getTotalValueGe() != null && primary.getTotalValueGe() != 0)
			? primary.getTotalValueGe()
			: merged.getCorroboratedValueGe();

		return LootPayloadDto.builder().source(source).items(items).totalValueGe(totalValueGe).build();
	}

	private LootItemDto toLootItemDto(DropItem item)
	{
		return LootItemDto.builder()
			.itemId(item.getId())
			.name(item.getName())
			.quantity(item.getQuantity())
			.unitPriceGe(item.getUnitPriceGe())
			.build();
	}

	private CollectionLogPayloadDto buildCollectionLogPayload(DropSignal primary)
	{
		DropItem item = primary.getItems() != null && !primary.getItems().isEmpty() ? primary.getItems().get(0) : null;
		return CollectionLogPayloadDto.builder()
			.entryName(item != null ? item.getName() : null)
			.itemId(item != null ? item.getId() : null)
			.build();
	}

	/**
	 * The actor block is read straight off the client, and getAccountType() reads a varbit under
	 * the hood, which asserts client-thread-only. Dispatch (sweep/flush) runs on the executor
	 * thread, so it never reads the client at all - it reads this snapshot, taken once per
	 * session from checkLogin, which is the point where the local player is known to be loaded.
	 */
	public void onLogin(String displayName)
	{
		currentActor = ActorDto.builder()
			.accountHash(String.valueOf(client.getAccountHash()))
			.displayName(displayName)
			.accountType(client.getAccountType() != null ? client.getAccountType().name() : "UNKNOWN")
			.world(client.getWorld())
			.worldTypes(worldTypes())
			.build();
	}

	/**
	 * A hop moves world/worldTypes without ever going back through login, so the snapshot would
	 * otherwise report the old world for the rest of the session. Only those two fields can have
	 * changed, and both are readable here without the local player, which a hop has not
	 * necessarily respawned yet. Rebuilt rather than mutated in place, since the dispatch thread
	 * can be reading the current snapshot.
	 */
	public void onWorldChanged()
	{
		ActorDto cached = currentActor;
		if (cached == null)
		{
			return;
		}

		currentActor = ActorDto.builder()
			.accountHash(cached.getAccountHash())
			.displayName(cached.getDisplayName())
			.accountType(cached.getAccountType())
			.world(client.getWorld())
			.worldTypes(worldTypes())
			.build();
	}

	private List<String> worldTypes()
	{
		return client.getWorldType().stream().map(WorldType::name).collect(Collectors.toList());
	}

	private void sendBatch(List<EventEnvelopeDto> envelopes, long nextRetryDelayMs)
	{
		if (envelopes.isEmpty() || !enabled())
		{
			return;
		}

		String json = gson.toJson(envelopes);
		Request request = new Request.Builder()
			.url(apiBaseUrl.replaceAll("/$", "") + EVENTS_PATH)
			.header("Authorization", "Bearer " + config.apiToken().trim())
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("POST /v1/events failed: {}, retrying in {}ms", e.getMessage(), nextRetryDelayMs);
				scheduleRetry(envelopes, nextRetryDelayMs);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try
				{
					int code = response.code();
					if (code == 401)
					{
						log.warn("POST /v1/events returned 401, token is missing or unknown. Dropping batch of {}.", envelopes.size());
						return;
					}
					if (code >= 500)
					{
						log.debug("POST /v1/events returned {}, retrying in {}ms", code, nextRetryDelayMs);
						scheduleRetry(envelopes, nextRetryDelayMs);
						return;
					}
					if (!response.isSuccessful())
					{
						log.warn("POST /v1/events returned {}: {}. Dropping batch of {}.", code, response.message(), envelopes.size());
						return;
					}

					// 2xx (204, no body) means the topic took the batch. Nothing else to read.
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private void scheduleRetry(List<EventEnvelopeDto> envelopes, long delayMs)
	{
		executor.schedule(() -> sendBatch(envelopes, Math.min(MAX_RETRY_DELAY_MS, delayMs * 2)), delayMs, TimeUnit.MILLISECONDS);
	}

	private void uploadScreenshot(String eventId, BufferedImage image)
	{
		byte[] bytes = toPngBytes(image);
		if (bytes == null)
		{
			return;
		}

		HttpUrl url = HttpUrl.parse(apiBaseUrl.replaceAll("/$", "") + EVENTS_PATH + "/" + eventId + SCREENSHOT_PATH_SUFFIX);
		if (url == null)
		{
			return;
		}

		RequestBody fileBody = RequestBody.create(MediaType.parse("image/png"), bytes);
		MultipartBody multipart = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", "screenshot.png", fileBody)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + config.apiToken().trim())
			.post(multipart)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Screenshot upload for {} failed: {}", eventId, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				// Upload failures are terminal per the contract, never retried: the event is
				// already recorded, a lost screenshot only costs its evidence.
				if (!response.isSuccessful())
				{
					log.debug("Screenshot upload for {} returned {}: {}", eventId, response.code(), response.message());
				}
				response.close();
			}
		});
	}

	private byte[] toPngBytes(BufferedImage image)
	{
		try (ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			log.debug("Failed to encode screenshot", e);
			return null;
		}
	}

	private boolean enabled()
	{
		return config.enableBingoApi() && config.apiToken() != null && !config.apiToken().trim().isEmpty() && !apiBaseUrl.isEmpty();
	}
}

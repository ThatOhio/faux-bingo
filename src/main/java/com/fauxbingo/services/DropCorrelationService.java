package com.fauxbingo.services;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.data.Confidence;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.DropType;
import com.fauxbingo.services.data.MergedDropEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Merges the one signal each loot handler produces into a single authoritative/enriched event
 * per physical drop, so both the Discord webhook and (eventually) the v1 API only ever see one
 * event for one item.
 *
 * Two dedup layers exist on purpose. LootEventHandler's TILE_SCAN/SERVER pairing and
 * RaidLootHandler's own chat+chest assembly already solve same-method duplicates with timing
 * this service can't match (raid chat-to-chest latency can exceed the window below). This
 * service only handles the cross-method case: the same item reported via more than one
 * detection method.
 */
@Slf4j
@Singleton
public class DropCorrelationService
{
	private static final long CORRELATION_WINDOW_MS = 5_000;
	private static final long SWEEP_INTERVAL_MS = 500;
	private static final Pattern QUANTITY_PREFIX = Pattern.compile("^[0-9,]+\\s*x\\s+");

	private final FauxBingoConfig config;
	private final WebhookService webhookService;
	private final EventEnvelopeSink envelopeSink;
	private final ScheduledExecutorService executor;

	private final Deque<PendingGroup> pendingGroups = new ArrayDeque<>();
	private ScheduledFuture<?> sweepTask;

	@Inject
	public DropCorrelationService(FauxBingoConfig config, WebhookService webhookService,
		EventEnvelopeSink envelopeSink, ScheduledExecutorService executor)
	{
		this.config = config;
		this.webhookService = webhookService;
		this.envelopeSink = envelopeSink;
		this.executor = executor;
	}

	public void start()
	{
		if (sweepTask != null && !sweepTask.isCancelled())
		{
			return;
		}
		sweepTask = executor.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public void shutdown()
	{
		if (sweepTask != null)
		{
			sweepTask.cancel(false);
			sweepTask = null;
		}
		flushAll();
	}

	/** Handlers call this instead of touching WebhookService/EventsApiService directly. */
	public synchronized void report(DropSignal signal)
	{
		if (signal == null)
		{
			return;
		}

		PendingGroup group = findMatchingGroup(signal);
		if (group == null)
		{
			group = new PendingGroup(System.currentTimeMillis() + CORRELATION_WINDOW_MS);
			pendingGroups.add(group);
		}
		group.signals.add(signal);
	}

	/**
	 * Item-name/id intersection is the normal match. PET signals carry no item identity at all
	 * (the game's pet message never names it), so they pair against the nearest unclaimed
	 * COLLECTION_LOG signal instead - the only way the plugin ever learns a pet's name. That
	 * pairing works regardless of which signal lands first.
	 */
	private PendingGroup findMatchingGroup(DropSignal signal)
	{
		boolean isPet = signal.getDetectionMethod().getType() == DropType.PET;
		boolean isCollectionLog = signal.getDetectionMethod().getType() == DropType.COLLECTION_LOG;

		if (isPet)
		{
			return findGroup(g -> g.hasCollectionLog() && !g.hasPet());
		}

		Set<String> keys = itemKeys(signal);
		if (!keys.isEmpty())
		{
			PendingGroup byItem = findGroup(g -> !disjoint(g.itemKeys(), keys));
			if (byItem != null)
			{
				return byItem;
			}
		}

		if (isCollectionLog)
		{
			return findGroup(g -> g.hasPet() && !g.hasCollectionLog());
		}

		return null;
	}

	private PendingGroup findGroup(Predicate<PendingGroup> predicate)
	{
		for (PendingGroup group : pendingGroups)
		{
			if (predicate.test(group))
			{
				return group;
			}
		}
		return null;
	}

	private static boolean disjoint(Set<String> a, Set<String> b)
	{
		for (String key : a)
		{
			if (b.contains(key))
			{
				return false;
			}
		}
		return true;
	}

	private static Set<String> itemKeys(DropSignal signal)
	{
		Set<String> keys = new HashSet<>();
		if (signal.getItems() == null)
		{
			return keys;
		}
		for (DropItem item : signal.getItems())
		{
			if (item.getId() != null)
			{
				keys.add("id:" + item.getId());
			}
			String normalized = normalizeName(item.getName());
			if (normalized != null)
			{
				keys.add("name:" + normalized);
			}
		}
		return keys;
	}

	/** Strips a chat-embedded quantity prefix like "30 x " so names line up across handlers. */
	private static String normalizeName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String stripped = QUANTITY_PREFIX.matcher(name.trim()).replaceFirst("");
		return stripped.toLowerCase(Locale.ROOT);
	}

	private synchronized void sweep()
	{
		long now = System.currentTimeMillis();
		Iterator<PendingGroup> it = pendingGroups.iterator();
		while (it.hasNext())
		{
			PendingGroup group = it.next();
			if (now >= group.deadline)
			{
				it.remove();
				resolveAndDispatch(group);
			}
		}
	}

	/** Called from shutdown so nothing lingers unresolved past a logout/plugin stop. */
	private synchronized void flushAll()
	{
		Iterator<PendingGroup> it = pendingGroups.iterator();
		while (it.hasNext())
		{
			PendingGroup group = it.next();
			it.remove();
			resolveAndDispatch(group);
		}
	}

	private void resolveAndDispatch(PendingGroup group)
	{
		if (group.signals.isEmpty())
		{
			return;
		}

		try
		{
			MergedDropEvent merged = resolve(group.signals, group.dropGroupId);
			dispatch(merged, group.signals);
		}
		catch (Exception e)
		{
			log.error("Error resolving correlated drop", e);
		}
	}

	private MergedDropEvent resolve(List<DropSignal> signals, String dropGroupId)
	{
		DropSignal primary = pickPrimary(signals);

		DropSignal petSignal = findFirst(signals, s -> s.getDetectionMethod().getType() == DropType.PET);
		DropSignal clogSignal = findFirst(signals, s -> s.getDetectionMethod().getType() == DropType.COLLECTION_LOG);

		String petName = null;
		String sourceNameGuess = petSignal != null ? petSignal.getSourceNameGuess() : null;
		if (petSignal != null && clogSignal != null && clogSignal.getItems() != null && !clogSignal.getItems().isEmpty())
		{
			petName = clogSignal.getItems().get(0).getName();
		}

		Long corroboratedValue = null;
		if (primary.getTotalValueGe() == null || primary.getTotalValueGe() == 0)
		{
			for (DropSignal other : signals)
			{
				if (other != primary && other.getTotalValueGe() != null && other.getTotalValueGe() > 0)
				{
					corroboratedValue = other.getTotalValueGe();
					break;
				}
			}
		}

		String finalMessage = composeMessage(primary, signals, petName);

		return MergedDropEvent.builder()
			.type(primary.getDetectionMethod().getType())
			.dropGroupId(dropGroupId)
			.primarySignal(primary)
			.contributingSignals(new ArrayList<>(signals))
			.petName(petName)
			.sourceNameGuess(sourceNameGuess)
			.corroboratedValueGe(corroboratedValue)
			.finalMessage(finalMessage)
			.screenshot(primary.getScreenshot())
			.build();
	}

	private static DropSignal findFirst(List<DropSignal> signals, Predicate<DropSignal> predicate)
	{
		for (DropSignal signal : signals)
		{
			if (predicate.test(signal))
			{
				return signal;
			}
		}
		return null;
	}

	/** EXACT beats DERIVED; among ties, richer data (has an itemId) wins; final tiebreak is arrival order. */
	private static DropSignal pickPrimary(List<DropSignal> signals)
	{
		DropSignal best = null;
		int bestScore = Integer.MIN_VALUE;
		for (DropSignal signal : signals)
		{
			int score = score(signal);
			if (score > bestScore)
			{
				bestScore = score;
				best = signal;
			}
		}
		return best;
	}

	private static int score(DropSignal signal)
	{
		int score = 0;
		if (signal.getDetectionMethod().getConfidence() == Confidence.EXACT)
		{
			score += 10;
		}
		if (signal.getItems() != null && signal.getItems().stream().anyMatch(i -> i.getId() != null))
		{
			score += 1;
		}
		return score;
	}

	/**
	 * Base text is the winning signal's own message. A PET winner paired with a COLLECTION_LOG
	 * corroborator gets its name spliced in, since that's the only way the plugin ever learns a
	 * pet's name. Every other corroborator just adds a one-line footnote.
	 */
	private static String composeMessage(DropSignal primary, List<DropSignal> signals, String petName)
	{
		String message = primary.getWebhookMessage();
		if (petName != null && primary.getDetectionMethod().getType() == DropType.PET)
		{
			message = message.endsWith("!")
				? message.substring(0, message.length() - 1) + ": **" + petName + "**!"
				: message + ": **" + petName + "**";
		}

		StringBuilder combined = new StringBuilder(message);
		for (DropSignal other : signals)
		{
			if (other == primary)
			{
				continue;
			}
			String extra = additionalText(other);
			if (extra != null)
			{
				combined.append("\n").append(extra);
			}
		}
		return combined.toString();
	}

	private static String additionalText(DropSignal signal)
	{
		switch (signal.getDetectionMethod())
		{
			case CHAT_COLLECTION_LOG:
			case NOTIFICATION_COLLECTION_LOG:
				return "*This item was also added to their collection log!*";
			case CHAT_VALUABLE_DROP:
				return "*This was also a valuable drop!*";
			case CHAT_PET:
				return "*They also received a pet!*";
			default:
				if (signal.getDetectionMethod().getType() == DropType.LOOT && signal.getSourceName() != null)
				{
					return String.format("Dropped by: **%s**", signal.getSourceName());
				}
				return null;
		}
	}

	/** Preserves each handler's own notify rule (value threshold, or always-on for pets/clog/rares). */
	private void dispatch(MergedDropEvent merged, List<DropSignal> signals)
	{
		long mergedValue = 0;
		boolean alwaysNotify = false;
		for (DropSignal signal : signals)
		{
			if (signal.getTotalValueGe() != null)
			{
				mergedValue = Math.max(mergedValue, signal.getTotalValueGe());
			}
			if (signal.isAlwaysNotify())
			{
				alwaysNotify = true;
			}
		}
		if (merged.getCorroboratedValueGe() != null)
		{
			mergedValue = Math.max(mergedValue, merged.getCorroboratedValueGe());
		}

		if (alwaysNotify || mergedValue >= config.minLootValue())
		{
			webhookService.sendWebhook(config.webhookUrl(), merged.getFinalMessage(), merged.getScreenshot(), true);
		}

		envelopeSink.accept(merged);
	}

	private static class PendingGroup
	{
		private final long deadline;
		private final String dropGroupId = UUID.randomUUID().toString();
		private final List<DropSignal> signals = new ArrayList<>();

		PendingGroup(long deadline)
		{
			this.deadline = deadline;
		}

		boolean hasPet()
		{
			return signals.stream().anyMatch(s -> s.getDetectionMethod().getType() == DropType.PET);
		}

		boolean hasCollectionLog()
		{
			return signals.stream().anyMatch(s -> s.getDetectionMethod().getType() == DropType.COLLECTION_LOG);
		}

		Set<String> itemKeys()
		{
			Set<String> keys = new HashSet<>();
			for (DropSignal signal : signals)
			{
				keys.addAll(DropCorrelationService.itemKeys(signal));
			}
			return keys;
		}
	}
}

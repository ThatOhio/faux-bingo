package com.fauxbingo.services;

import com.fauxbingo.services.data.DetectionMethod;
import com.fauxbingo.services.data.DropItem;
import com.fauxbingo.services.data.DropSignal;
import com.fauxbingo.services.data.MergedDropEvent;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Reporting is synchronous (grouping happens on report()), but dispatch only happens once the
 * window closes. shutdown() forces an immediate flush of whatever is pending, standing in here
 * for "the correlation window elapsed" without needing to sleep in these tests.
 */
@RunWith(MockitoJUnitRunner.class)
public class DropCorrelationServiceTest
{
	@Mock
	private EventEnvelopeSink envelopeSink;

	@Mock
	private ScheduledExecutorService executor;

	private DropCorrelationService service;

	@Before
	public void before()
	{
		service = new DropCorrelationService(envelopeSink, executor);
	}

	private static DropSignal.DropSignalBuilder lootSignal(DetectionMethod method, String itemName, long value)
	{
		return DropSignal.builder()
			.detectionMethod(method)
			.items(Collections.singletonList(DropItem.builder().name(itemName).quantity(1).unitPriceGe(value).build()))
			.totalValueGe(value)
			.sourceName("Vorkath");
	}

	private MergedDropEvent captureMergedEvent()
	{
		ArgumentCaptor<MergedDropEvent> captor = ArgumentCaptor.forClass(MergedDropEvent.class);
		verify(envelopeSink).accept(captor.capture());
		return captor.getValue();
	}

	@Test
	public void uncorroboratedSignalStillResolvesAlone()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 2_000_000).build());

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(1, merged.getContributingSignals().size());
	}

	@Test
	public void sameItemFromTwoMethodsMergesIntoOneEnvelope()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 500_000).build());
		service.report(lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Dragon bones", 2_000_000).build());

		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(2, merged.getContributingSignals().size());
	}

	@Test
	public void exactConfidenceWinsOverDerivedAsPrimary()
	{
		DropSignal exact = lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Twisted bow", 2_000_000).build();
		DropSignal derived = lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Twisted bow", 2_000_000).build();

		// Report the DERIVED signal first to prove arrival order doesn't override confidence tier.
		service.report(derived);
		service.report(exact);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(DetectionMethod.NPC_LOOT_RECEIVED, merged.getPrimarySignal().getDetectionMethod());
	}

	/** No local value gating here anymore, envelopeSink.accept must fire regardless of value. */
	@Test
	public void belowValueThresholdStillReachesSink()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Bones", 100).build());
		service.shutdown();

		verify(envelopeSink).accept(any());
	}

	@Test
	public void petAndCollectionLogMergeLearnsPetName()
	{
		DropSignal pet = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_PET)
			.sourceNameGuess("Vorkath")
			.build();
		DropSignal clog = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_COLLECTION_LOG)
			.items(Collections.singletonList(DropItem.builder().name("Vorki").quantity(1).build()))
			.build();

		service.report(pet);
		service.report(clog);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals("Vorki", merged.getPetName());
		assertEquals("Vorkath", merged.getSourceNameGuess());
	}

	@Test
	public void petArrivingAfterCollectionLogStillPairs()
	{
		DropSignal clog = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_COLLECTION_LOG)
			.items(Collections.singletonList(DropItem.builder().name("Baby mole").quantity(1).build()))
			.build();
		DropSignal pet = DropSignal.builder()
			.detectionMethod(DetectionMethod.CHAT_PET)
			.build();

		service.report(clog);
		service.report(pet);
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals("Baby mole", merged.getPetName());
	}

	@Test
	public void unrelatedItemsResolveIntoSeparateGroups()
	{
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Dragon bones", 2_000_000).build());
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Shark", 2_000_000).build());
		service.shutdown();

		verify(envelopeSink, times(2)).accept(any());
	}

	@Test
	public void screenshotFromWinningSignalIsKept()
	{
		BufferedImage exactImage = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
		BufferedImage derivedImage = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);

		service.report(lootSignal(DetectionMethod.CHAT_VALUABLE_DROP, "Fang", 2_000_000).screenshot(derivedImage).build());
		service.report(lootSignal(DetectionMethod.NPC_LOOT_RECEIVED, "Fang", 2_000_000).screenshot(exactImage).build());
		service.shutdown();

		MergedDropEvent merged = captureMergedEvent();
		assertEquals(exactImage, merged.getScreenshot());
	}
}

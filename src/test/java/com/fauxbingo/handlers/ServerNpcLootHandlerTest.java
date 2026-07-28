package com.fauxbingo.handlers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.BingoConfigService;
import com.fauxbingo.services.LogService;
import com.fauxbingo.services.ScreenshotService;
import com.fauxbingo.services.WebhookService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Yama and The Whisperer drop loot away from their own tile, so LootManager finds nothing to scan
 * and never posts NpcLootReceived. ServerNpcLoot is the only event they produce. Ordinary NPCs fire
 * both, so these also pin down the pairing that stops a double post.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ServerNpcLootHandlerTest
{
	private static final int OATHPLATE_SHARDS_ID = 30765;
	private static final int SHARD_PRICE = 183_000;
	private static final int BONES_ID = 526;

	@Mock
	private Client client;
	@Mock
	private FauxBingoConfig config;
	@Mock
	private ItemManager itemManager;
	@Mock
	private WebhookService webhookService;
	@Mock
	private LogService logService;
	@Mock
	private ScreenshotService screenshotService;
	@Mock
	private ScheduledExecutorService executor;
	@Mock
	private BingoConfigService bingoConfigService;
	@Mock
	private NPC npc;
	@Mock
	private NPCComposition npcComposition;

	private EventHandler<ServerNpcLoot> serverHandler;
	private EventHandler<NpcLootReceived> tileHandler;

	@Before
	public void before()
	{
		LootEventHandler lootEventHandler = new LootEventHandler(client, config, bingoConfigService, null,
			itemManager, webhookService, logService, screenshotService, executor);
		serverHandler = lootEventHandler.createServerNpcLootHandler();
		tileHandler = lootEventHandler.createNpcLootHandler();

		when(config.webhookUrl()).thenReturn("http://webhook");
		when(config.minLootValue()).thenReturn(1_500_000);

		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(executor).execute(any());

		doAnswer(inv -> {
			java.util.function.Consumer<java.awt.image.BufferedImage> cb = inv.getArgument(0);
			cb.accept(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB));
			return null;
		}).when(screenshotService).requestScreenshot(any());

		ItemComposition shards = mock(ItemComposition.class);
		when(shards.getName()).thenReturn("Oathplate shards");
		when(itemManager.getItemComposition(OATHPLATE_SHARDS_ID)).thenReturn(shards);
		when(itemManager.getItemPrice(OATHPLATE_SHARDS_ID)).thenReturn(SHARD_PRICE);

		ItemComposition bones = mock(ItemComposition.class);
		when(bones.getName()).thenReturn("Bones");
		when(itemManager.getItemComposition(BONES_ID)).thenReturn(bones);
		when(itemManager.getItemPrice(BONES_ID)).thenReturn(100);
	}

	private ServerNpcLoot serverLoot(String name, List<ItemStack> items)
	{
		when(npcComposition.getName()).thenReturn(name);
		return new ServerNpcLoot(npcComposition, items);
	}

	private NpcLootReceived tileLoot(String name, List<ItemStack> items)
	{
		when(npc.getName()).thenReturn(name);
		return new NpcLootReceived(npc, items);
	}

	private static List<ItemStack> shards()
	{
		return Collections.singletonList(new ItemStack(OATHPLATE_SHARDS_ID, 12, null));
	}

	private void withShardTile()
	{
		when(bingoConfigService.getCachedConfig()).thenReturn(new BingoConfigService.BingoConfigData(
			Collections.emptyList(),
			Collections.singletonList(new BingoConfigService.BingoConfigItem("oathplate shards", null))));
	}

	/** The reported bug: 183k a shard is under the gate, so only the tile path could have posted it. */
	@Test
	public void yamaShardsFireOnServerEventAlone()
	{
		withShardTile();

		serverHandler.handle(serverLoot("Yama", shards()));

		verify(logService).log(eq("LOOT"), any());
		verify(webhookService).sendWebhook(anyString(), anyString(), any(),
			eq("Oathplate shards"), eq(WebhookService.WebhookCategory.BINGO_LOOT));
	}

	@Test
	public void whispererLootFiresOnServerEventAlone()
	{
		serverHandler.handle(serverLoot("The Whisperer", shards()));

		verify(logService).log(eq("LOOT"), any());
	}

	/** Ordinary NPC: both events arrive for one kill and only one report comes out. */
	@Test
	public void bothEventsForOneKillReportOnce()
	{
		serverHandler.handle(serverLoot("Vorkath", shards()));
		tileHandler.handle(tileLoot("Vorkath", shards()));

		verify(logService, times(1)).log(eq("LOOT"), any());
	}

	/** Order should not matter, the tile scan can land first. */
	@Test
	public void pairingWorksInEitherOrder()
	{
		tileHandler.handle(tileLoot("Vorkath", shards()));
		serverHandler.handle(serverLoot("Vorkath", shards()));

		verify(logService, times(1)).log(eq("LOOT"), any());
	}

	/** Pairing consumes one entry, so identical repeat kills are not swallowed. */
	@Test
	public void twoIdenticalKillsBothReport()
	{
		List<ItemStack> loot = Collections.singletonList(new ItemStack(BONES_ID, 1, null));

		serverHandler.handle(serverLoot("Goblin", loot));
		tileHandler.handle(tileLoot("Goblin", loot));
		serverHandler.handle(serverLoot("Goblin", loot));
		tileHandler.handle(tileLoot("Goblin", loot));

		verify(logService, times(2)).log(eq("LOOT"), any());
	}

	/** Interleaved, which is what a delayed tile scan looks like against a fast second kill. */
	@Test
	public void interleavedIdenticalKillsBothReport()
	{
		List<ItemStack> loot = Collections.singletonList(new ItemStack(BONES_ID, 1, null));

		serverHandler.handle(serverLoot("Goblin", loot));
		serverHandler.handle(serverLoot("Goblin", loot));
		tileHandler.handle(tileLoot("Goblin", loot));
		tileHandler.handle(tileLoot("Goblin", loot));

		verify(logService, times(2)).log(eq("LOOT"), any());
	}

	/** Two of the same event in a row are never each other's pair. */
	@Test
	public void repeatedServerEventsAreNotPairedTogether()
	{
		serverHandler.handle(serverLoot("Yama", shards()));
		serverHandler.handle(serverLoot("Yama", shards()));

		verify(logService, times(2)).log(eq("LOOT"), any());
	}

	/** Different loot from the same NPC is a different kill, not a pair. */
	@Test
	public void differentLootIsNotPaired()
	{
		serverHandler.handle(serverLoot("Vorkath", shards()));
		tileHandler.handle(tileLoot("Vorkath", Collections.singletonList(new ItemStack(BONES_ID, 1, null))));

		verify(logService, times(2)).log(eq("LOOT"), any());
	}

	/** Item ordering differs between the two events, the key must not care. */
	@Test
	public void itemOrderDoesNotBreakPairing()
	{
		ItemStack shard = new ItemStack(OATHPLATE_SHARDS_ID, 12, null);
		ItemStack bone = new ItemStack(BONES_ID, 1, null);

		serverHandler.handle(serverLoot("Yama", Arrays.asList(shard, bone)));
		tileHandler.handle(tileLoot("Yama", Arrays.asList(bone, shard)));

		verify(logService, times(1)).log(eq("LOOT"), any());
	}

	@Test
	public void nullCompositionAndEmptyItemsAreSafe()
	{
		serverHandler.handle(new ServerNpcLoot(null, shards()));
		serverHandler.handle(serverLoot("Yama", Collections.emptyList()));

		verify(logService, times(1)).log(eq("LOOT"), any());
	}
}

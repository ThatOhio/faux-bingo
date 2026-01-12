package com.fauxbingo.trackers;

import com.fauxbingo.FauxBingoConfig;
import com.fauxbingo.services.WiseOldManService;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tracks player XP and level changes to trigger WiseOldMan updates.
 * Updates are triggered when:
 * - Player logs out and gained 10k+ XP
 * - Player levels up during the session
 */
@Slf4j
public class XpTracker
{
	private static final int XP_THRESHOLD = 10_000;

	private final Client client;
	private final FauxBingoConfig config;
	private final WiseOldManService wiseOldManService;

	private final Map<Skill, Integer> previousSkillLevels = new EnumMap<>(Skill.class);
	private long lastTotalXp = 0;
	private boolean levelUpThisSession = false;
	private boolean fetchXp = false;
	private String playerName;
	private long accountHash;

	public XpTracker(Client client, FauxBingoConfig config, WiseOldManService wiseOldManService)
	{
		this.client = client;
		this.config = config;
		this.wiseOldManService = wiseOldManService;
	}

	public void onStatChanged(StatChanged event)
	{
		if (!config.enableWomAutoUpdate())
		{
			return;
		}

		Skill skill = event.getSkill();
		int levelAfter = client.getRealSkillLevel(skill);
		int levelBefore = previousSkillLevels.getOrDefault(skill, -1);

		if (levelBefore != -1 && levelAfter > levelBefore)
		{
			levelUpThisSession = true;
			log.debug("Level up detected in {}: {} -> {}", skill, levelBefore, levelAfter);
		}

		previousSkillLevels.put(skill, levelAfter);
	}

	public void onGameStateChanged(GameStateChanged event)
	{
		if (!config.enableWomAutoUpdate())
		{
			return;
		}

		GameState state = event.getGameState();

		switch (state)
		{
			case LOGGED_IN:
				// Check if account changed
				if (accountHash != client.getAccountHash())
				{
					fetchXp = true;
				}
				break;

			case LOGIN_SCREEN:
			case HOPPING:
				Player local = client.getLocalPlayer();
				if (local == null)
				{
					return;
				}

				playerName = local.getName();
				long currentTotalXp = client.getOverallExperience();

				// Only update if XP threshold is reached or player leveled up
				if (Math.abs(currentTotalXp - lastTotalXp) > XP_THRESHOLD || levelUpThisSession)
				{
					log.debug("Triggering WiseOldMan update for {} (XP change: {}, Level up: {})",
						playerName,
						Math.abs(currentTotalXp - lastTotalXp),
						levelUpThisSession);

					wiseOldManService.updatePlayer(playerName);
					lastTotalXp = currentTotalXp;
					levelUpThisSession = false;
				}
				break;
		}
	}

	public void onGameTick()
	{
		if (!config.enableWomAutoUpdate())
		{
			return;
		}

		if (fetchXp)
		{
			lastTotalXp = client.getOverallExperience();
			accountHash = client.getAccountHash();
			fetchXp = false;

			Player local = client.getLocalPlayer();
			if (local != null)
			{
				playerName = local.getName();
			}

			// Save current skill levels
			saveCurrentLevels();
		}
	}

	private void saveCurrentLevels()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		previousSkillLevels.clear();
		for (Skill skill : Skill.values())
		{
			previousSkillLevels.put(skill, client.getRealSkillLevel(skill));
		}
	}

	public void reset()
	{
		previousSkillLevels.clear();
		lastTotalXp = 0;
		levelUpThisSession = false;
		fetchXp = false;
		playerName = null;
		accountHash = 0;
	}
}

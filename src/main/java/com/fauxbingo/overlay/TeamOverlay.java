package com.fauxbingo.overlay;

import com.fauxbingo.FauxBingoConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;

/**
 * Overlay that displays team name and current date/time in UTC.
 * Used for verification purposes when screenshots are sent to Discord webhooks.
 */
public class TeamOverlay extends OverlayPanel
{
	private final FauxBingoConfig config;

	@Inject
	public TeamOverlay(FauxBingoConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.TOP_CENTER);
		getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "Faux Bingo overlay"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Only render if overlay is enabled and team name is set
		if (!config.displayOverlay() || config.teamName().trim().isEmpty())
		{
			return null;
		}

		String teamName = config.teamName();
		Color teamNameColor = config.teamNameColor();
		Color dateTimeColor = config.dateTimeColor();

		// Ensure colors are different, fallback to defaults if they match
		if (teamNameColor.equals(dateTimeColor))
		{
			teamNameColor = Color.GREEN;
			dateTimeColor = Color.WHITE;
		}

		// Build the full text for width calculation
		String fullText = teamName;
		if (config.displayDateTime())
		{
			fullText = teamName + " " + getUtcDateTime();
		}

		// Add team name as the left component
		panelComponent.getChildren().add(LineComponent.builder()
			.left(teamName)
			.leftColor(teamNameColor)
			.build());

		// Add date/time as the right component if enabled
		if (config.displayDateTime())
		{
			List<LayoutableRenderableEntity> children = panelComponent.getChildren();
			LineComponent line = (LineComponent) children.get(0);
			line.setRight(getUtcDateTime());
			line.setRightColor(dateTimeColor);
		}

		// Set preferred size based on text width
		panelComponent.setPreferredSize(new Dimension(
			graphics.getFontMetrics().stringWidth(fullText) + 10, 0));

		return super.render(graphics);
	}

	private String getUtcDateTime()
	{
		Date date = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		return sdf.format(date) + " UTC";
	}
}

package net.runelite.client.plugins.microbot.woodcutting.guildmagics;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.plugins.microbot.woodcutting.guildmagics.GuildMagicsScript.debugMessages;
import static net.runelite.client.plugins.microbot.woodcutting.guildmagics.GuildMagicsScript.status;

public class GuildMagicsOverlay extends OverlayPanel {
    @Inject
    GuildMagicsOverlay(GuildMagicsPlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(375, 700));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Red Bracket Guild Magics version 1.0")
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status: " + status.toString())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("---")
                    .build());
            for (int i = 0; i < debugMessages.size(); i++) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Debug " + (1 + i) + ": " + debugMessages.get(i))
                        .build());
            }
        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

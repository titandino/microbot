package net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload.BruteSorcScript.debugMessages;


public class BruteSorcOverlay extends OverlayPanel {
    @Inject
    BruteSorcOverlay(BruteSorcPlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(375, 700));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Red Bracket Brute Sorc Plugin 0.1")
                    .color(Color.GREEN)
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

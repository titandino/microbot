package net.runelite.client.plugins.microbot.util.paintlogs;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.Arrays;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debugMessages;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.status;


public class LogPaintOverlay extends OverlayPanel {
    @Inject
    LogPaintOverlay(PaintLogsPlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            // System.out.println("Walker dest: " + Microbot.getClient().getLocalDestinationLocation() + " (dist: " + distTo(Microbot.getClient().getLocalDestinationLocation()) + ")");
            panelComponent.setPreferredSize(new Dimension(375, 700));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Red Bracket Logs version 2.1")
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status: " + status)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("---")
                    .build());
            for (int i = 0; i < debugMessages.size(); i++) {
                panelComponent.getChildren().add(LineComponent.builder()
                        .left("Debug " + (1 + i) + ": " + debugMessages.get(i))
                        .build());
            }
            return super.render(graphics);
        } catch(Exception ex) {
            StringBuilder sb = new StringBuilder("Error rendering status " + status + ", debug lines. ");
            for (int i = 0; i < debugMessages.size(); i++) {
                sb.append(i).append(": ").append(debugMessages.get(i)).append("\n");
            }
            System.out.println("Failed to render " + sb);
            System.out.println("Exception: " + ex + "\n. Stack: " + Arrays.toString(ex.getStackTrace()));
        }
        return null;
    }
}

package net.runelite.client.plugins.microbot.mining.motherloadmine;

import net.runelite.api.ObjectID;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.example.ExampleScript;
import net.runelite.client.plugins.microbot.mining.MiningScript;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.natepainthelper.PaintFormat;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.plugins.microbot.mining.motherloadmine.MotherloadMineScript.*;
import static net.runelite.client.plugins.natepainthelper.Info.*;
import static net.runelite.client.plugins.natepainthelper.Info.xpTillNextLevel;


public class MotherloadMineOverlay extends OverlayPanel {
    @Inject
    MotherloadMineOverlay(MotherloadMinePlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            panelComponent.setPreferredSize(new Dimension(275, 700));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Red Bracket Top Motherload version 2.1")
                    .color(Color.GREEN)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Status: " + status.toString())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("On upper floor: " + isOnUpperFloor())
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Debug old: " + debugOld)
                    .build());
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("Debug new: " + debugNew)
                    .build());
        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }
}

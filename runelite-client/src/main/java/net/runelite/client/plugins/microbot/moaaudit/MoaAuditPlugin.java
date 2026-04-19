package net.runelite.client.plugins.microbot.moaaudit;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

// TEMP debug plugin: on enable, iterates every Map of Alacrity seasonal transport,
// attempts to teleport, and logs actual landing vs expected coord for each. Used to
// catch bad destination coords in seasonal_transports.tsv. Delete when done.
@PluginDescriptor(
        name = PluginDescriptor.Default + "MoA Audit",
        description = "[TEMP] Record Map of Alacrity teleport landing tiles",
        tags = {"temp", "debug", "league", "microbot"},
        enabledByDefault = false
)
@Slf4j
public class MoaAuditPlugin extends Plugin {
    private Thread worker;

    @Override
    protected void startUp() {
        worker = new Thread(Rs2Walker::runMoaAudit, "moa-audit");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    protected void shutDown() {
        if (worker != null) worker.interrupt();
    }
}

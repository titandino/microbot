package net.runelite.client.plugins.microbot.util.bank;

import net.runelite.api.GameObject;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

public class Rs2DepositBox {
    public static boolean isOpen() {
        return Rs2Widget.findWidget("The Bank of Gielinor - Deposit Box", null) != null;
    }

    public static boolean openDepositBox() {
        Microbot.status = "Opening deposit box";
        try {
            if (Microbot.getClient().isWidgetSelected())
                Microbot.getMouse().click();
            if (isOpen()) return true;
            GameObject depositBox = Rs2GameObject.findObject("Bank deposit box", false);
            Rs2GameObject.interact(depositBox, "deposit");
            sleepUntil(Rs2DepositBox::isOpen, 20000);
            return isOpen();
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
        return false;
    }

    public static void depositAll() {
        Microbot.status = "Deposit all";
        if (Inventory.isEmpty()) return;

        Widget widget = Rs2Widget.findWidget(SpriteID.BANK_DEPOSIT_INVENTORY, null);
        if (widget == null) return;

        Microbot.getMouse().click(widget.getBounds());
    }
}

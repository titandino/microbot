package net.runelite.client.plugins.microbot.util.player;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.math.Calculations;
import net.runelite.client.plugins.microbot.util.math.Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.util.Global.sleep;


public class Rs2Player {
    static int VENOM_VALUE_CUTOFF = -38;
    private static int antiFireTime = -1;
    private static int superAntiFireTime = -1;
    private static int divineRangedTime = -1;
    private static int divineBastionTime = -1;
    public static int antiVenomTime = -1;

    public static boolean hasAntiFireActive() {
        return antiFireTime > 0 || hasSuperAntiFireActive();
    }

    public static boolean hasSuperAntiFireActive() {
        return superAntiFireTime > 0;
    }

    public static boolean hasDivineRangedActive() {
        return divineRangedTime > 0 || hasDivineBastionActive();
    }

    public static boolean hasDivineBastionActive() {
        return divineBastionTime > 0;
    }

    public static boolean hasAntiVenomActive() {
        if (Rs2Equipment.hasEquipped("serpentine helm")) {
            return true;
        } else return antiVenomTime < VENOM_VALUE_CUTOFF;
    }

    public static void handlePotionTimers(VarbitChanged event) {
        if (event.getVarbitId() == Varbits.ANTIFIRE) {
            antiFireTime = event.getValue();
        }
        if (event.getVarbitId() == Varbits.SUPER_ANTIFIRE) {
            superAntiFireTime = event.getValue();
        }
        if (event.getVarbitId() == Varbits.DIVINE_RANGING) {
            divineRangedTime = event.getValue();
        }
        if (event.getVarbitId() == Varbits.DIVINE_BASTION) {
            divineBastionTime = event.getValue();
        }
        if (event.getVarpId() == VarPlayer.POISON) {
            if (event.getValue() >= VENOM_VALUE_CUTOFF) {
                antiVenomTime = 0;
                return;
            }
            antiVenomTime = event.getValue();
        }
    }

    public static boolean isAnimating() {
        return Microbot.isAnimating();
    }

    public static boolean isWalking() {
        return Microbot.isMoving();
    }

    public static boolean isMoving() {
        return Microbot.isMoving();
    }

    public static boolean isInteracting() {
        return Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getLocalPlayer().isInteracting());
    }

    public static boolean isMember() {
        return Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getVarpValue(VarPlayer.MEMBERSHIP_DAYS) > 0);
    }

    @Deprecated(since = "Use the Rs2Combat.specState method", forRemoval = true)
    public static void toggleSpecialAttack(int energyRequired) {
        int currentSpecEnergy = Microbot.getClient().getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
        if (currentSpecEnergy >= energyRequired && (Microbot.getClient().getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 0)) {
            Rs2Widget.clickWidget("special attack");
        }
    }

    public static boolean toggleRunEnergy(boolean toggle) {
        System.out.println("Toggling run energy at " + System.currentTimeMillis() + " - " + toggle);
        // print stack trace
        new Exception().printStackTrace();
        if (Microbot.getVarbitPlayerValue(173) == 0 && !toggle) return true;
        if (Microbot.getVarbitPlayerValue(173) == 1 && toggle) return true;
        Widget widget = Rs2Widget.getWidget(WidgetInfo.MINIMAP_TOGGLE_RUN_ORB.getId());
        if (widget == null) return false;
        if (Microbot.getClient().getEnergy() > 200 && toggle) {
            Microbot.getMouse().click(widget.getCanvasLocation());
            return true;
        } else if (!toggle) {
            Microbot.getMouse().click(widget.getCanvasLocation());
            return true;
        }
        return false;
    }

    public static WorldPoint getWorldLocation() {
        return Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());
    }

    public static Player playerInteraction = null;
    public static String playerAction = null;

    public static boolean interact(Player player, String action) {
        if (player == null) return false;
        try {
            playerInteraction = player;
            playerAction = action;
            if (Calculations.tileOnScreen(player)) {
                Microbot.getMouse().click(player.getCanvasTilePoly().getBounds());
            } else {
                Microbot.getMouse().clickFast(Random.random(0, Microbot.getClient().getCanvasWidth()), Random.random(0, Microbot.getClient().getCanvasHeight()));
            }
            sleep(100);
            playerInteraction = null;
            playerAction = null;
        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }

        return true;
    }

    public static void handleMenuSwapper(MenuEntry menuEntry) {
        if (playerInteraction == null) return;
        try {
            menuEntry.setIdentifier(playerInteraction.getId());
            menuEntry.setParam0(0);
            menuEntry.setTarget("<col=ffff00>" + playerInteraction.getName() + "<col=ff00>  (level-" + playerInteraction.getCombatLevel() + ")");
            menuEntry.setParam1(0);
            menuEntry.setOption(playerAction);

//            if (Microbot.getClient().isWidgetSelected()) {
//                menuEntry.setType(MenuAction.WIDGET_TARGET_ON_NPC);
//            } else if (index == 0) {
//                menuEntry.setType(MenuAction.PLAYER_FIRST_OPTION);
//            } else
            if (playerAction == "attack") {
                menuEntry.setType(MenuAction.PLAYER_SECOND_OPTION);
            }
//            } else if (index == 2) {
//                menuEntry.setType(MenuAction.PLAYER_THIRD_OPTION);
//
//            } else if (index == 3) {
//                menuEntry.setType(MenuAction.PLAYER_FOURTH_OPTION);
//
//            } else if (index == 4) {
//                menuEntry.setType(MenuAction.PLAYER_FIFTH_OPTION);
//            }
        } catch (Exception ex) {
            System.out.println("NPC MENU SWAP FAILED WITH MESSAGE: " + ex.getMessage());
        }
    }

}

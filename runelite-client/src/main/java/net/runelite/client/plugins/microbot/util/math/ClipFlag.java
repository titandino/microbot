package net.runelite.client.plugins.microbot.util.math;

import java.util.ArrayList;

public enum ClipFlag {
    EMPTY(true, -1),
    BW_NW(0),
    BW_N(1),
    BW_NE(2),
    BW_E(3),
    BW_SE(4),
    BW_S(5),
    BW_SW(6),
    BW_W(7),
    BW_FULL(8),
    BP_NW(9),
    BP_N(10),
    BP_NE(11),
    BP_E(12),
    BP_SE(13),
    BP_S(14),
    BP_SW(15),
    BP_W(16),
    BP_FULL(17),
    PFBW_GROUND_DECO(18),
    BW_NPC(19),
    BW_PLAYER(20),
    PFBW_FLOOR(21),
    PF_NW(22),
    PF_N(23),
    PF_NE(24),
    PF_E(25),
    PF_SE(26),
    PF_S(27),
    PF_SW(28),
    PF_W(29),
    PF_FULL(30),
    UNDER_ROOF(31);

    public int flag;

    private ClipFlag(int flag) {
        this.flag = 1 << flag;
    }

    private ClipFlag(boolean absolute, int flag) {
        this.flag = flag;
    }

    public static ArrayList<ClipFlag> getFlags(int value) {
        ArrayList<ClipFlag> flags = new ArrayList();
        ClipFlag[] var2 = values();
        int var3 = var2.length;

        for(int var4 = 0; var4 < var3; ++var4) {
            ClipFlag f = var2[var4];
            if ((value & f.flag) != 0 && f != EMPTY) {
                flags.add(f);
            }
        }

        return flags;
    }

    public static boolean flagged(int value, ClipFlag... flags) {
        int flag = 0;
        ClipFlag[] var3 = flags;
        int var4 = flags.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            ClipFlag f = var3[var5];
            flag |= f.flag;
        }

        return (value & flag) != 0;
    }

    public static int or(ClipFlag... flags) {
        int flag = 0;
        ClipFlag[] var2 = flags;
        int var3 = flags.length;

        for(int var4 = 0; var4 < var3; ++var4) {
            ClipFlag f = var2[var4];
            flag |= f.flag;
        }

        return flag;
    }

    public static int blockNorth(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_N.flag;
        }

        if (projectiles) {
            flags |= BP_N.flag;
        }

        if (pathfinder) {
            flags |= PF_N.flag;
        }

        return flags;
    }

    public static int blockNorthEast(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_NE.flag;
        }

        if (projectiles) {
            flags |= BP_NE.flag;
        }

        if (pathfinder) {
            flags |= PF_NE.flag;
        }

        return flags;
    }

    public static int blockNorthWest(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_NW.flag;
        }

        if (projectiles) {
            flags |= BP_NW.flag;
        }

        if (pathfinder) {
            flags |= PF_NW.flag;
        }

        return flags;
    }

    public static int blockSouth(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_S.flag;
        }

        if (projectiles) {
            flags |= BP_S.flag;
        }

        if (pathfinder) {
            flags |= PF_S.flag;
        }

        return flags;
    }

    public static int blockSouthEast(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_SE.flag;
        }

        if (projectiles) {
            flags |= BP_SE.flag;
        }

        if (pathfinder) {
            flags |= PF_SE.flag;
        }

        return flags;
    }

    public static int blockSouthWest(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_SW.flag;
        }

        if (projectiles) {
            flags |= BP_SW.flag;
        }

        if (pathfinder) {
            flags |= PF_SW.flag;
        }

        return flags;
    }

    public static int blockEast(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_E.flag;
        }

        if (projectiles) {
            flags |= BP_E.flag;
        }

        if (pathfinder) {
            flags |= PF_E.flag;
        }

        return flags;
    }

    public static int blockWest(boolean walk, boolean projectiles, boolean pathfinder) {
        int flags = 0;
        if (walk) {
            flags |= BW_W.flag;
        }

        if (projectiles) {
            flags |= BP_W.flag;
        }

        if (pathfinder) {
            flags |= PF_W.flag;
        }

        return flags;
    }
}

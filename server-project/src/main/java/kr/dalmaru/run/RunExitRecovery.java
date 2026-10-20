package kr.dalmaru.run;

import net.minecraft.server.level.ServerPlayer;

/** Apply only after a run return succeeds; RPG health uses the player's engine health ratio. */
public final class RunExitRecovery {
    private RunExitRecovery() { }

    public static void restore(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        var food = player.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20);
    }
}

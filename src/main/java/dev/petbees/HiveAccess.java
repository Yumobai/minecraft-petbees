package dev.petbees;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

public interface HiveAccess {
    void petbees$releaseGuards(ServerPlayer owner, LivingEntity target);
}

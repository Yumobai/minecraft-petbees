package dev.petbees.mixin;

import dev.petbees.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity.Occupant;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BeehiveBlockEntity.class)
public abstract class HiveMixin extends net.minecraft.world.level.block.entity.BlockEntity implements HiveAccess {
    protected HiveMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
    @Shadow private BlockPos savedFlowerPos;
    @Shadow private static boolean releaseOccupant(Level level, BlockPos pos, BlockState state, Occupant occupant,
            List<Entity> spawned, BeeReleaseStatus status, BlockPos flower) { throw new AssertionError(); }

    @Redirect(method = "releaseOccupant", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean petbees$verifyRelease(Level level, Entity entity, Level sourceLevel, BlockPos pos,
            BlockState state, Occupant occupant, List<Entity> spawned, BeeReleaseStatus status, BlockPos flower) {
        if (entity instanceof Bee bee) BeeOwnership.verifyRelease(bee, occupant.entityData().getUnsafe());
        return level.addFreshEntity(entity);
    }

    @Override public void petbees$releaseGuards(ServerPlayer owner, LivingEntity target) {
        BeehiveBlockEntity hive = (BeehiveBlockEntity)(Object)this;
        var access = (HiveAccessor) this;
        var iterator = access.petbees$stored().iterator();
        boolean changed = false;
        while (iterator.hasNext()) {
            Occupant occupant = ((BeeDataAccessor) iterator.next()).petbees$occupant();
            var tag = occupant.entityData().getUnsafe();
            var ownership = BeeOwnership.fromTag(tag);
            if (ownership == null || !owner.getUUID().equals(ownership.owner())) continue;
            int age = tag.getIntOr("Age", 0);
            boolean locked = tag.getBooleanOr("AgeLocked", false);
            if (age < 0 && (locked || (long)age + occupant.ticksInHive() < 0)) continue;
            if (net.minecraft.world.phys.Vec3.atCenterOf(hive.getBlockPos()).distanceToSqr(target.position()) > GuardService.RADIUS_SQR) continue;
            List<Entity> spawned = new ArrayList<>(1);
            if (releaseOccupant(hive.getLevel(), hive.getBlockPos(), hive.getBlockState(), occupant,
                    spawned, BeeReleaseStatus.EMERGENCY, savedFlowerPos)) {
                iterator.remove();
                changed = true;
                for (Entity entity : spawned) if (entity instanceof Bee bee) GuardService.start(bee, target, hive.getBlockPos());
            }
        }
        if (changed) super.setChanged();
    }

    @Redirect(method = "emptyAllLivingFromHive", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/bee/Bee;setTarget(Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void petbees$peacefulHarvest(Bee bee, LivingEntity target) {
        if (GuardService.state(bee).owns(target.getUUID())) GuardService.calmFromHive(bee);
        else bee.setTarget(target);
    }
}

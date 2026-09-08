package dev.petbees;

import java.util.EnumSet;
import dev.petbees.mixin.BeeAccessor;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Only takes control during a return trip; ordinary bee goals remain untouched. */
public final class ReturnGoal extends Goal {
    private final Bee bee;
    public ReturnGoal(Bee bee) {
        this.bee = bee;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    @Override public boolean canUse() {
        var s = GuardService.state(bee);
        return s.tamed() && s.returning && s.origin != null && bee.level().getGameTime() >= s.returnAfter;
    }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public void start() { bee.getNavigation().stop(); }
    @Override public void tick() {
        BeeState s = GuardService.state(bee);
        Vec3 destination = s.returnHive == null ? s.origin : Vec3.atCenterOf(s.returnHive);
        if (destination == null) return;
        if (s.returnHive != null && bee.level().isLoaded(s.returnHive)) {
            var blockEntity = bee.level().getBlockEntity(s.returnHive);
            if (!(blockEntity instanceof BeehiveBlockEntity hive) || hive.isFull() || hive.isFireNearby()) {
                s.returnHive = null;
                destination = s.origin;
            } else if (bee.position().distanceToSqr(destination) < 4) {
                GuardService.finish(bee);
                hive.addOccupant(bee);
                return;
            }
        }
        if (s.returnHive == null && bee.position().distanceToSqr(destination) < 4) {
            GuardService.finish(bee);
            return;
        }
        // A blocked or removed home must not trap a bee in a permanent custom goal.
        if (++s.returnTicks > 600) { GuardService.finish(bee); return; }
        if (s.returnTicks % 10 == 1) {
            if (bee.position().distanceToSqr(destination) > 256) {
                ((BeeAccessor) bee).petbees$pathfind(net.minecraft.core.BlockPos.containing(destination));
            } else {
                bee.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.0);
            }
        }
    }
    @Override public void stop() { bee.getNavigation().stop(); }
}

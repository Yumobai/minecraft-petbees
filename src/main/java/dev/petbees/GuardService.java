package dev.petbees;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class GuardService {
    public static final double RADIUS_SQR = 32 * 32;
    private static final Map<ServerPlayer, LivingEntity> PENDING = new HashMap<>();
    private GuardService() {}

    public static BeeState state(Bee bee) { return ((PetBee) bee).petbees$state(); }

    public static boolean validEnemy(Bee bee, LivingEntity enemy) {
        var s = state(bee);
        if (enemy == null || !enemy.isAlive() || enemy == bee || s.owns(enemy.getUUID())) return false;
        if (enemy instanceof Player player && (player.isSpectator() || player.isCreative())) return false;
        if (enemy instanceof Bee other && s.owner != null && state(other).owns(s.owner)) return false;
        return !bee.isAlliedTo(enemy);
    }

    public static void queue(ServerPlayer owner, LivingEntity enemy) {
        if (!owner.isAlive() || enemy == owner || !enemy.isAlive() || owner.level() != enemy.level()) return;
        if (enemy instanceof Bee bee && state(bee).owns(owner.getUUID())) return;
        PENDING.put(owner, enemy);
    }

    /** Runs only for owners involved in damage events, at most once per owner per tick. */
    public static void flush(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        var work = new HashMap<>(PENDING);
        PENDING.clear();
        work.forEach(GuardService::summon);
    }

    public static void clear() { PENDING.clear(); }

    private static void summon(ServerPlayer owner, LivingEntity enemy) {
        if (!owner.isAlive() || owner.isRemoved() || !enemy.isAlive()) return;
        ServerLevel level = (ServerLevel) owner.level();
        if (enemy.level() != level) return;
        for (Bee bee : level.getEntitiesOfClass(Bee.class, owner.getBoundingBox().inflate(32),
                bee -> bee.distanceToSqr(owner) <= RADIUS_SQR && state(bee).owns(owner.getUUID()) && !bee.isBaby())) {
            start(bee, enemy, null);
        }
        BlockPos center = owner.blockPosition();
        for (int x = (center.getX() - 32) >> 4; x <= (center.getX() + 32) >> 4; x++) {
            for (int z = (center.getZ() - 32) >> 4; z <= (center.getZ() + 32) >> 4; z++) {
                // getChunkNow never loads or generates a chunk.
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var blockEntity : List.copyOf(chunk.getBlockEntities().values())) {
                    if (blockEntity instanceof BeehiveBlockEntity hive
                            && Vec3.atCenterOf(hive.getBlockPos()).distanceToSqr(owner.position()) <= RADIUS_SQR) {
                        ((HiveAccess) hive).petbees$releaseGuards(owner, enemy);
                    }
                }
            }
        }
    }

    public static void start(Bee bee, LivingEntity enemy, BlockPos hive) {
        BeeState s = state(bee);
        if (!s.tamed() || bee.isBaby() || !validEnemy(bee, enemy) || s.returning) return;
        Vec3 origin = s.origin == null ? (hive == null ? bee.position() : Vec3.atCenterOf(hive)) : s.origin;
        if (origin.distanceToSqr(enemy.position()) > RADIUS_SQR) return;
        if (s.origin == null) {
            s.origin = origin;
            s.returnHive = hive;
            s.charged = false;
            s.returnTicks = 0;
            s.returnAfter = 0;
        }
        s.target = enemy.getUUID();
        bee.setStayOutOfHiveCountdown(40);
        bee.setPersistentAngerTarget(EntityReference.of(enemy));
        bee.setTimeToRemainAngry(200);
        bee.setTarget(enemy);
    }

    public static void retreat(Bee bee) {
        BeeState s = state(bee);
        s.returning = true;
        s.target = null;
        bee.stopBeingAngry();
        bee.setTarget(null);
        bee.getNavigation().stop();
        bee.setStayOutOfHiveCountdown(0);
    }

    public static void calmFromHive(Bee bee) {
        BeeState s = state(bee);
        if (!s.tamed() || s.active()) return;
        s.origin = bee.position();
        s.returnHive = bee.getHivePos();
        s.returnAfter = bee.level().getGameTime() + 100;
        s.returnTicks = 0;
        retreat(bee);
        bee.setStayOutOfHiveCountdown(100);
    }

    public static void finish(Bee bee) {
        BeeState s = state(bee);
        s.origin = null;
        s.returnHive = null;
        s.returning = false;
        s.charged = false;
        s.target = null;
        s.returnTicks = 0;
        s.returnAfter = 0;
    }

    public static void tick(Bee bee, ServerLevel level) {
        BeeState s = state(bee);
        if (!s.tamed() || s.origin == null || s.returning) return;
        Player owner = level.getPlayerByUUID(s.owner);
        if (owner == null || !owner.isAlive() || owner.isSpectator()
                || owner.position().distanceToSqr(s.origin) > RADIUS_SQR
                || bee.position().distanceToSqr(s.origin) > RADIUS_SQR) {
            retreat(bee);
            return;
        }
        Entity resolved = s.target == null ? null : level.getEntity(s.target);
        LivingEntity target = resolved instanceof LivingEntity living ? living : null;
        if (target != null && target.position().distanceToSqr(s.origin) > RADIUS_SQR) {
            retreat(bee);
            return;
        }
        if (!validEnemy(bee, target)) {
            // Only when the previous target disappears; never a per-tick area search.
            target = nextThreat(bee, owner, level);
            if (target == null) { retreat(bee); return; }
            s.target = target.getUUID();
        }
        if (bee.getTarget() != target) bee.setTarget(target);
        if (bee.tickCount % 20 == 0) {
            bee.setPersistentAngerTarget(EntityReference.of(target));
            bee.setTimeToRemainAngry(200);
        }
    }

    private static LivingEntity nextThreat(Bee bee, Player owner, ServerLevel level) {
        Vec3 origin = state(bee).origin;
        List<LivingEntity> candidates = new ArrayList<>();
        var last = owner.getLastHurtByMob();
        if (last != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200) candidates.add(last);
        candidates.addAll(level.getEntitiesOfClass(Mob.class, new AABB(origin, origin).inflate(32),
                mob -> mob.getTarget() == owner));
        return candidates.stream().filter(e -> validEnemy(bee, e) && e.position().distanceToSqr(origin) <= RADIUS_SQR)
                .min(java.util.Comparator.comparingDouble(bee::distanceToSqr)).orElse(null);
    }
}

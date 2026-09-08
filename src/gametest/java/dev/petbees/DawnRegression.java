package dev.petbees;

import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.petbees.mixin.HiveAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;

final class DawnRegression {
    static void verify(GameTestHelper h) throws Exception {
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        h.setBlock(new BlockPos(1, 1, 1), Blocks.BEEHIVE);
        h.getLevel().resetWeatherCycle();
        var lines = new String(DawnRegression.class.getResourceAsStream("/incident-hives.snbt").readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        int pets = 0, wilds = 0;
        for (String line : lines) {
            var hive = (BeehiveBlockEntity)BlockEntity.loadStatic(pos, Blocks.BEEHIVE.defaultBlockState(), TagParser.parseCompoundFully(line), h.getLevel().registryAccess());
            hive.setLevel(h.getLevel());
            var records = ((HiveAccessor)hive).petbees$bees();
            var expected = new HashMap<UUID, Integer>();
            for (var record : records) {
                var ownership = BeeOwnership.fromTag(record.entityData().getUnsafe());
                if (ownership != null) { expected.merge(ownership.owner(), 1, Integer::sum); pets++; }
                else wilds++;
            }
            // Preserve saved bee data; fast-forward only the time spent inside.
            hive = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
            hive.setLevel(h.getLevel());
            for (var record : records) hive.storeBee(new BeehiveBlockEntity.Occupant(record.entityData(), 3000, record.minTicksInHive()));
            for (int day = 0; day < 3; day++) {
                h.setTime(18000);
                BeehiveBlockEntity.serverTick(h.getLevel(), pos, hive.getBlockState(), hive);
                if (hive.getOccupantCount() != records.size()) throw new AssertionError("Night unexpectedly released bees");
                var before = new HashSet<>(h.getLevel().getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(pos).inflate(4)));
                h.setTime(1000);
                BeehiveBlockEntity.serverTick(h.getLevel(), pos, hive.getBlockState(), hive);
                if (!hive.isEmpty()) throw new AssertionError("Natural daylight did not release bees");
                var born = h.getLevel().getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(pos).inflate(4), b -> !before.contains(b));
                if (born.size() != records.size()) throw new AssertionError("Natural release entity count changed");
                var actual = new HashMap<UUID, Integer>();
                for (var bee : born) if (GuardService.state(bee).tamed()) actual.merge(GuardService.state(bee).owner, 1, Integer::sum);
                if (!expected.equals(actual)) throw new AssertionError("Natural sunrise lost owner on day " + day);
                for (var bee : born) hive.addOccupant(bee);
                var next = ((HiveAccessor)hive).petbees$bees();
                hive = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
                hive.setLevel(h.getLevel());
                for (var record : next) hive.storeBee(new BeehiveBlockEntity.Occupant(record.entityData(), 3000, record.minTicksInHive()));
            }
        }
        if (pets != 212 || wilds != 450) throw new AssertionError("Incident fixture counts changed " + pets + "/" + wilds);
        h.succeed();
    }
}

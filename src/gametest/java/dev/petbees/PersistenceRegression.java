package dev.petbees;

import java.io.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.storage.*;
import dev.petbees.mixin.HiveAccessor;

final class PersistenceRegression {
    static void combat(GameTestHelper h) throws IOException {
        var registry = h.getLevel().registryAccess();
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        UUID owner = UUID.randomUUID();
        for (boolean againstPlayer : List.of(false, true)) {
            Bee bee = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.COMMAND);
            bee.setPos(net.minecraft.world.phys.Vec3.atCenterOf(pos));
            GuardService.state(bee).tame(owner, "CombatOwner");
            net.minecraft.world.entity.LivingEntity enemy = againstPlayer
                    ? h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL)
                    : EntityTypes.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
            enemy.setPos(bee.position().add(1, 0, 0));
            for (int trip = 0; trip < 3; trip++) {
                enemy.setHealth(enemy.getMaxHealth());
                enemy.invulnerableTime = 0;
                GuardService.start(bee, enemy, pos);
                if (!bee.doHurtTarget(h.getLevel(), enemy)) throw new AssertionError("Combat damage failed");
                // Full combat snapshot contains Origin, target, charged flag and pending cost.
                var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
                bee.save(output);
                var combatTag = roundTrip(output.buildResult());
                // Also exercise the original 1.0.1 persistence route, with no backup present.
                combatTag.remove(net.fabricmc.fabric.api.attachment.v1.AttachmentTarget.NBT_ATTACHMENT_KEY);
                var restored = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.LOAD);
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, combatTag));
                bee = restored;
                if (!GuardService.state(bee).owns(owner)) throw new AssertionError("Attack/reload lost ownership");
                if (againstPlayer) {
                    var access = (dev.petbees.testmixin.BeeTestAccess)bee;
                    access.petbees$timer(1199);
                    access.petbees$tick(h.getLevel());
                }
                if (!GuardService.state(bee).owns(owner)) throw new AssertionError("Delayed sting erased ownership");
                if (againstPlayer && trip == 2) {
                    if (bee.isAlive()) throw new AssertionError("Expected third player trip to kill");
                    break;
                }
                if (!bee.isAlive()) throw new AssertionError("Bee unexpectedly died");
                GuardService.retreat(bee);
                GuardService.finish(bee);
                var hive = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
                hive.storeBee(BeehiveBlockEntity.Occupant.of(bee));
                hive = (BeehiveBlockEntity)BlockEntity.loadStatic(pos, hive.getBlockState(), roundTrip(hive.saveWithFullMetadata(registry)), registry);
                bee = (Bee)((HiveAccessor)hive).petbees$bees().getFirst().createEntity(h.getLevel(), pos);
                bee.setPos(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                if (!GuardService.state(bee).owns(owner)) throw new AssertionError("Post-combat hive return erased ownership");
                if (!againstPlayer && bee.getHealth() != 10) throw new AssertionError("Monster trip cost health");
            }
        }
    }
    static void recovery(GameTestHelper h) {
        var registry = h.getLevel().registryAccess();
        UUID owner = UUID.randomUUID();
        var bee = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.COMMAND);
        GuardService.state(bee).tame(owner, "PersistentOwner");
        GuardService.state(bee).pendingStings = 2;
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
        bee.save(output);
        var saved = output.buildResult();
        if (!saved.contains(net.fabricmc.fabric.api.attachment.v1.AttachmentTarget.NBT_ATTACHMENT_KEY)) throw new AssertionError("No native ownership backup");

        var missingPrimary = saved.copy();
        missingPrimary.remove(BeeState.TAG);
        var recovered = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.LOAD);
        recovered.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, missingPrimary));
        if (!GuardService.state(recovered).owns(owner)) throw new AssertionError("Backup did not restore owner");
        if (!BeeOwnership.fromTag(missingPrimary).owner().equals(owner)) throw new AssertionError("Stored hive backup unreadable");

        var legacy = saved.copy();
        legacy.remove(net.fabricmc.fabric.api.attachment.v1.AttachmentTarget.NBT_ATTACHMENT_KEY);
        var migrated = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.LOAD);
        migrated.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, legacy));
        if (!GuardService.state(migrated).owns(owner)) throw new AssertionError("1.0.1 legacy ownership lost");
        if (!((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget)migrated).hasAttached(BeeOwnership.BACKUP)) throw new AssertionError("Legacy data not backed up");

        // Existing known entity: a partial load must preserve its known owner and pending cost.
        var partial = legacy.copy();
        partial.remove(BeeState.TAG);
        bee.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, partial));
        if (!GuardService.state(bee).owns(owner) || GuardService.state(bee).pendingStings != 2) throw new AssertionError("Incomplete reload erased live state");

        // A fresh entity without either record must remain wild; never guess an owner.
        var unknown = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.LOAD);
        unknown.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, partial));
        if (GuardService.state(unknown).tamed() || BeeOwnership.fromTag(partial) != null) throw new AssertionError("Wild bee was automatically claimed");
    }
    private static CompoundTag roundTrip(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
    }
    static void verify(GameTestHelper h) throws IOException {
        var registry = h.getLevel().registryAccess();
        var ops = registry.createSerializationContext(NbtOps.INSTANCE);
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        // 300 different owners, through binary entity NBT, hive NBT and carried hive item components.
        for (int i = 0; i < 100; i++) {
            var hive = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
            var owners = new ArrayList<UUID>();
            for (int n = 0; n < 3; n++) {
                Bee bee = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.COMMAND);
                UUID owner = UUID.randomUUID();
                owners.add(owner);
                GuardService.state(bee).tame(owner, "Owner" + i + "_" + n);
                var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
                bee.save(out);
                var restored = EntityTypes.BEE.create(h.getLevel(), EntitySpawnReason.LOAD);
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, roundTrip(out.buildResult())));
                if (!GuardService.state(restored).owns(owner)) throw new AssertionError("Entity binary NBT lost owner");
                hive.storeBee(BeehiveBlockEntity.Occupant.of(restored));
            }
            for (int cycle = 0; cycle < 10; cycle++) {
                hive = (BeehiveBlockEntity) BlockEntity.loadStatic(pos, Blocks.BEEHIVE.defaultBlockState(),
                        roundTrip(hive.saveWithFullMetadata(registry)), registry);
                ItemStack item = new ItemStack(Items.BEEHIVE);
                item.applyComponents(hive.collectComponents());
                var encoded = (CompoundTag)ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
                ItemStack restoredItem = ItemStack.CODEC.parse(ops, roundTrip(encoded)).getOrThrow();
                var placed = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
                placed.applyComponentsFromItemStack(restoredItem);
                var occupants = ((HiveAccessor)placed).petbees$bees();
                if (occupants.size() != 3) throw new AssertionError("Hive item lost occupants");
                var nextHive = new BeehiveBlockEntity(pos, Blocks.BEEHIVE.defaultBlockState());
                for (int n = 0; n < 3; n++) {
                    Bee bee = (Bee)occupants.get(n).createEntity(h.getLevel(), pos);
                    if (!GuardService.state(bee).owns(owners.get(n))) throw new AssertionError("Hive cycle " + cycle + " lost owner " + n);
                    nextHive.storeBee(BeehiveBlockEntity.Occupant.of(bee));
                }
                hive = nextHive;
            }
        }
    }
}

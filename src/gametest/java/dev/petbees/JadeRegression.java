package dev.petbees;

import dev.petbees.jade.PetBeesJade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.impl.BlockAccessorImpl;
import snownee.jade.impl.EntityAccessorImpl;

/** Enforces the client-only Jade registration constraint even on a dedicated test server. */
final class JadeRegression {
    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }
    static void verify(GameTestHelper h) {
        check(h, !((Object)PetBeesJade.BeeProvider.INSTANCE instanceof IComponentProvider), "Jade rejects combined bee data/tooltip provider on client");
        check(h, !((Object)PetBeesJade.HiveProvider.INSTANCE instanceof IComponentProvider), "Jade rejects combined hive data/tooltip provider on client");
        check(h, !((Object)PetBeesJade.BeeClient.INSTANCE instanceof IServerDataProvider), "Bee client provider must be separate");
        check(h, !((Object)PetBeesJade.HiveClient.INSTANCE instanceof IServerDataProvider), "Hive client provider must be separate");
        check(h, PetBeesJade.BeeProvider.INSTANCE.getUid().equals(PetBeesJade.BeeClient.INSTANCE.getUid()), "Bee provider IDs disagree");
        check(h, PetBeesJade.HiveProvider.INSTANCE.getUid().equals(PetBeesJade.HiveClient.INSTANCE.getUid()), "Hive provider IDs disagree");

        var viewer = h.makeMockPlayer(GameType.SURVIVAL);
        var bee = h.spawn(EntityTypes.BEE, 1, 2, 1);
        GuardService.state(bee).tame(java.util.UUID.randomUUID(), "OfflineBeeOwner");
        var entityAccessor = new EntityAccessorImpl.Builder().level(h.getLevel()).player(viewer)
                .entity(bee).hit(new EntityHitResult(bee)).serverData(new CompoundTag()).build();
        var data = new CompoundTag();
        PetBeesJade.BeeProvider.INSTANCE.appendServerData(data, entityAccessor);
        check(h, data.getBooleanOr("PetBeeTamed", false), "Jade tamed status missing");
        check(h, data.getStringOr("PetBeeOwner", "").equals("OfflineBeeOwner"), "Jade offline owner name missing");

        for (var block : java.util.List.of(Blocks.BEEHIVE, Blocks.BEE_NEST)) {
            BlockPos relative = new BlockPos(1, 1, 1);
            BlockPos absolute = h.absolutePos(relative);
            h.setBlock(relative, block);
            var hive = (BeehiveBlockEntity)h.getLevel().getBlockEntity(absolute);
            hive.storeBee(BeehiveBlockEntity.Occupant.of(bee));
            var accessor = new BlockAccessorImpl.Builder().level(h.getLevel()).player(viewer)
                    .blockEntity(hive).blockState(hive.getBlockState())
                    .hit(new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false))
                    .serverData(new CompoundTag()).build();
            var hiveData = new CompoundTag();
            PetBeesJade.HiveProvider.INSTANCE.appendServerData(hiveData, accessor);
            check(h, hiveData.getIntOr("PetBeeCount", 0) == 1, "Jade hive/nest tamed count missing");
            check(h, hiveData.getStringOr("PetBeeOwner0", "").equals("OfflineBeeOwner"), "Jade hive/nest offline owner missing");
            check(h, hiveData.getIntOr("PetBeeWildCount", -1) == 0, "Tamed-only hive wild count wrong");
            hive.storeBee(BeehiveBlockEntity.Occupant.create(0));
            var mixed = new CompoundTag();
            PetBeesJade.HiveProvider.INSTANCE.appendServerData(mixed, accessor);
            check(h, mixed.getIntOr("PetBeeCount", -1) == 1 && mixed.getIntOr("PetBeeWildCount", -1) == 1, "Mixed hive counts wrong");
            ((dev.petbees.mixin.HiveAccessor)hive).petbees$stored().clear();
            hive.storeBee(BeehiveBlockEntity.Occupant.create(0));
            var wildOnly = new CompoundTag();
            PetBeesJade.HiveProvider.INSTANCE.appendServerData(wildOnly, accessor);
            check(h, wildOnly.getIntOr("PetBeeCount", -1) == 0 && wildOnly.getIntOr("PetBeeWildCount", -1) == 1, "Wild-only hive not represented");
            ((dev.petbees.mixin.HiveAccessor)hive).petbees$stored().clear();
            var empty = new CompoundTag();
            PetBeesJade.HiveProvider.INSTANCE.appendServerData(empty, accessor);
            check(h, empty.getIntOr("PetBeeCount", -1) == 0 && empty.getIntOr("PetBeeWildCount", -1) == 0, "Empty hive counts wrong");
        }
    }
}

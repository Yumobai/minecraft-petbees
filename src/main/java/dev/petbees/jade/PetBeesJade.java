package dev.petbees.jade;

import dev.petbees.BeeState;
import dev.petbees.BeeOwnership;
import dev.petbees.GuardService;
import dev.petbees.PetBees;
import dev.petbees.mixin.HiveAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Optional Jade entrypoint: the main mod never loads these classes without Jade. */
public final class PetBeesJade implements IWailaPlugin {
    @Override public void register(IWailaCommonRegistration registration) {
        registration.registerEntityDataProvider(BeeProvider.INSTANCE, Bee.class);
        registration.registerBlockDataProvider(HiveProvider.INSTANCE, BeehiveBlockEntity.class);
    }
    @Override public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(BeeClient.INSTANCE, Bee.class);
        registration.registerBlockComponent(HiveClient.INSTANCE, BeehiveBlock.class);
    }

    public enum BeeProvider implements IServerDataProvider<EntityAccessor> {
        INSTANCE;
        @Override public Identifier getUid() { return PetBees.id("owner"); }
        @Override public void appendServerData(CompoundTag data, EntityAccessor accessor) {
            BeeState s = GuardService.state((Bee) accessor.getEntity());
            data.putBoolean("PetBeeTamed", s.tamed());
            if (s.tamed()) {
                var online = accessor.getLevel().getPlayerByUUID(s.owner);
                if (online != null) s.ownerName = online.getGameProfile().name();
                data.putString("PetBeeOwner", s.ownerName.isBlank() ? s.owner.toString() : s.ownerName);
            }
        }
    }

    public enum BeeClient implements IEntityComponentProvider {
        INSTANCE;
        @Override public Identifier getUid() { return PetBees.id("owner"); }
        @Override public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            var data = accessor.getServerData();
            // A missing packet is unknown, not an untamed bee.
            if (!data.contains("PetBeeTamed")) return;
            if (!data.getBooleanOr("PetBeeTamed", false)) {
                tooltip.add(Component.translatable("tooltip.petbees.untamed").withStyle(ChatFormatting.GRAY));
                return;
            }
            tooltip.add(Component.translatable("tooltip.petbees.tamed").withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.petbees.owner", data.getStringOr("PetBeeOwner", "?")));
        }
    }

    public enum HiveProvider implements IServerDataProvider<BlockAccessor> {
        INSTANCE;
        @Override public Identifier getUid() { return PetBees.id("hive_owners"); }
        @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof BeehiveBlockEntity hive)) return;
            int count = 0;
            int wild = 0;
            for (var occupant : ((HiveAccessor) hive).petbees$bees()) {
                var ownership = BeeOwnership.fromTag(occupant.entityData().getUnsafe());
                if (ownership == null) { wild++; continue; }
                var owner = ownership.owner();
                var online = accessor.getLevel().getPlayerByUUID(owner);
                String name = online == null ? ownership.name() : online.getGameProfile().name();
                if (name.isBlank()) name = owner.toString();
                data.putString("PetBeeOwner" + count++, name);
            }
            data.putInt("PetBeeCount", count);
            data.putInt("PetBeeWildCount", wild);
        }
    }

    public enum HiveClient implements IBlockComponentProvider {
        INSTANCE;
        @Override public Identifier getUid() { return PetBees.id("hive_owners"); }
        @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            var data = accessor.getServerData();
            if (!data.contains("PetBeeCount")) return;
            int count = Math.clamp(data.getIntOr("PetBeeCount", 0), 0, 3);
            tooltip.add(Component.translatable("tooltip.petbees.hive_count", count).withStyle(ChatFormatting.GREEN));
            if (data.contains("PetBeeWildCount")) {
                int wild = Math.clamp(data.getIntOr("PetBeeWildCount", 0), 0, 3);
                tooltip.add(Component.translatable("tooltip.petbees.hive_wild_count", wild).withStyle(ChatFormatting.GRAY));
            }
            for (int i = 0; i < count; i++)
                tooltip.add(Component.translatable("tooltip.petbees.hive_owner", i + 1, data.getStringOr("PetBeeOwner" + i, "?")));
        }
    }
}

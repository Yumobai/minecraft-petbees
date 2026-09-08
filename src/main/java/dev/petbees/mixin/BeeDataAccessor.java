package dev.petbees.mixin;

import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.world.level.block.entity.BeehiveBlockEntity$BeeData")
public interface BeeDataAccessor {
    @Invoker("toOccupant") BeehiveBlockEntity.Occupant petbees$occupant();
}

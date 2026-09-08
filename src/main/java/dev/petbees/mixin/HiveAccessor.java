package dev.petbees.mixin;

import java.util.List;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BeehiveBlockEntity.class)
public interface HiveAccessor {
    @Accessor("stored") List<?> petbees$stored();
    @Invoker("getBees") List<BeehiveBlockEntity.Occupant> petbees$bees();
}

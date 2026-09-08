package dev.petbees.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Bee.class)
public interface BeeAccessor {
    @Invoker("pathfindRandomlyTowards") void petbees$pathfind(BlockPos target);
    @Invoker("setHasStung") void petbees$setHasStung(boolean value);
}

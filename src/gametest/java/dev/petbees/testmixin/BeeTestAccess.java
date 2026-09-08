package dev.petbees.testmixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Bee.class)
public interface BeeTestAccess {
    @Accessor("timeSinceSting") void petbees$timer(int value);
    @Invoker("customServerAiStep") void petbees$tick(ServerLevel level);
}

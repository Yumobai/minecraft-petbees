package dev.petbees.mixin;

import dev.petbees.GuardService;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class AnimalMixin {
    @Unique private static long petbees$sequence;
    @Inject(method = "setInLove", at = @At("TAIL"))
    private void petbees$breeder(Player player, CallbackInfo ci) {
        if ((Object)this instanceof Bee bee && player != null && !bee.level().isClientSide()) {
            var s = GuardService.state(bee);
            s.breeder = player.getUUID();
            s.breederName = player.getGameProfile().name();
            s.loveOrder = Math.max(System.currentTimeMillis() * 1024, ++petbees$sequence);
            petbees$sequence = s.loveOrder;
        }
    }
}

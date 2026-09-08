package dev.petbees.mixin;

import dev.petbees.GuardService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void petbees$target(LivingEntity target, CallbackInfo ci) {
        if (!((Object)this instanceof Bee bee) || target == null) return;
        var s = GuardService.state(bee);
        if (!s.tamed()) return;
        if (bee.isBaby() || s.returning || !GuardService.validEnemy(bee, target)) { ci.cancel(); return; }
        if (s.origin == null) {
            s.origin = bee.position();
            s.charged = false;
        }
        if (s.origin.distanceToSqr(target.position()) > GuardService.RADIUS_SQR) { ci.cancel(); return; }
        s.target = target.getUUID();
    }
}

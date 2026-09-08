package dev.petbees.mixin;

import dev.petbees.GuardService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.bee.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "die", at = @At("HEAD"))
    private void petbees$deathRecord(DamageSource source, CallbackInfo ci) {
        if ((Object)this instanceof Bee bee && !bee.level().isClientSide() && GuardService.state(bee).tamed())
            dev.petbees.BeeOwnership.logDeath(bee, "Death damage source: " + source);
    }
    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void petbees$combat(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !(source.getEntity() instanceof LivingEntity attacker)) return;
        LivingEntity victim = (LivingEntity)(Object)this;
        if (victim instanceof ServerPlayer player) GuardService.queue(player, attacker);
        if (attacker instanceof ServerPlayer player) GuardService.queue(player, victim);
    }

    @Inject(method = "setLastHurtByMob", at = @At("HEAD"), cancellable = true)
    private void petbees$ignoreOwner(LivingEntity attacker, CallbackInfo ci) {
        if ((Object)this instanceof Bee bee && attacker != null && GuardService.state(bee).owns(attacker.getUUID()))
            ci.cancel();
    }
}

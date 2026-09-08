package dev.petbees.mixin;

import dev.petbees.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Bee.class)
public abstract class BeeMixin extends Animal implements PetBee {
    @Unique private final BeeState petbees$data = new BeeState();
    @Shadow private int timeSinceSting;
    @Shadow private void setHasStung(boolean value) {}
    protected BeeMixin(EntityType<? extends Animal> type, Level level) { super(type, level); }
    @Override public BeeState petbees$state() { return petbees$data; }
    @Unique private Bee petbees$self() { return (Bee)(Object)this; }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void petbees$goals(CallbackInfo ci) {
        goalSelector.addGoal(-1, new ReturnGoal(petbees$self()));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void petbees$save(ValueOutput output, CallbackInfo ci) {
        petbees$data.stingTicks = timeSinceSting;
        petbees$data.save(output);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void petbees$load(ValueInput input, CallbackInfo ci) {
        boolean missingKnownOwner = petbees$data.tamed()
                && BeeState.uuid(input.childOrEmpty(BeeState.TAG).getStringOr("Owner", "")) == null;
        petbees$data.load(input);
        if (missingKnownOwner && !level().isClientSide()) BeeOwnership.warn(petbees$self(), "Retained existing owner during incomplete NBT reload");
        // Fabric reads its attachments before this method is invoked.
        BeeOwnership.reconcile(petbees$self());
        if (petbees$data.tamed()) {
            timeSinceSting = petbees$data.stingTicks;
            setHasStung(false);
        }
    }

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void petbees$feed(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        var stack = player.getItemInHand(hand);
        if (!stack.is(PetBees.BEE_SNACK)) return;
        cir.setReturnValue(InteractionResult.SUCCESS);
        if (!(level() instanceof ServerLevel server)) return;
        boolean success;
        if (!petbees$data.tamed()) {
            int attempt = petbees$data.attempts.merge(player.getUUID(), 1, Integer::sum);
            success = BeeRules.tames(attempt, random.nextFloat());
            if (success) {
                petbees$data.tame(player.getUUID(), player.getGameProfile().name());
                BeeOwnership.reconcile(petbees$self());
                setHasStung(false);
                timeSinceSting = 0;
                petbees$self().stopBeingAngry();
                setPersistenceRequired();
            }
        } else {
            if (getHealth() >= getMaxHealth() && petbees$data.pendingStings == 0) return;
            setHealth(getMaxHealth());
            petbees$data.pendingStings = 0;
            timeSinceSting = 0;
            setHasStung(false);
            success = true;
        }
        stack.consume(1, player);
        server.sendParticles(success ? ParticleTypes.HEART : ParticleTypes.SMOKE,
                getX(), getY() + 0.5, getZ(), 7, 0.25, 0.2, 0.25, 0.02);
    }

    // Keep vanilla damage, poison, enchantments and sting sound. Only replace the exhaustion state.
    @Redirect(method = "doHurtTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/bee/Bee;setHasStung(Z)V"))
    private void petbees$stingFlag(Bee bee, boolean value) {
        setHasStung(petbees$data.tamed() ? false : value);
    }

    @Redirect(method = "doHurtTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/bee/Bee;stopBeingAngry()V"))
    private void petbees$keepFighting(Bee bee) {
        if (!petbees$data.tamed()) bee.stopBeingAngry();
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void petbees$safeAttack(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (petbees$data.tamed() && (isBaby() || petbees$data.returning || petbees$data.owns(target.getUUID())))
            cir.setReturnValue(false);
    }

    @Inject(method = "doHurtTarget", at = @At("RETURN"))
    private void petbees$charge(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (petbees$data.tamed() && cir.getReturnValueZ() && target instanceof Player && !petbees$data.charged) {
            petbees$data.charged = true;
            if (petbees$data.pendingStings++ == 0) timeSinceSting = 0;
        }
    }

    // Run the original random death countdown for pending pet costs, without disabling their sting AI.
    @Redirect(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/bee/Bee;hasStung()Z"))
    private boolean petbees$pendingCountdown(Bee bee) {
        return petbees$data.tamed() ? petbees$data.pendingStings > 0 : bee.hasStung();
    }

    @Redirect(method = "customServerAiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/animal/bee/Bee;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z", ordinal = 1))
    private boolean petbees$settleSting(Bee bee, ServerLevel level, DamageSource source, float amount) {
        if (!petbees$data.tamed()) return bee.hurtServer(level, source, amount);
        petbees$data.pendingStings = Math.max(0, petbees$data.pendingStings - 1);
        timeSinceSting = 0;
        float health = BeeRules.healthAfterSting(getHealth());
        if (health <= 0) {
            BeeOwnership.logDeath(bee, "Delayed player-sting cost exhausted health; this bee dies, it does not become untamed");
            setHealth(0);
            die(source);
        } else {
            // Exact cost, independent of damage invulnerability frames or armour added by other mods.
            setHealth(health);
        }
        return true;
    }

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void petbees$guardTick(ServerLevel level, CallbackInfo ci) {
        GuardService.tick(petbees$self(), level);
    }

    @Inject(method = "getBreedOffspring", at = @At("RETURN"))
    private void petbees$inherit(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Bee> cir) {
        Bee baby = cir.getReturnValue();
        if (baby == null || !(partner instanceof Bee other)) return;
        BeeState a = petbees$data, b = GuardService.state(other), child = GuardService.state(baby);
        if (!a.tamed() && !b.tamed()) return;
        if (!a.tamed()) child.tame(b.owner, b.ownerName);
        else if (!b.tamed() || a.owner.equals(b.owner)) child.tame(a.owner, a.ownerName);
        else {
            BeeState last = a.loveOrder >= b.loveOrder ? a : b;
            if (last.breeder != null) child.tame(last.breeder, last.breederName);
            else child.tame(a.owner, a.ownerName);
        }
        baby.setPersistenceRequired();
        BeeOwnership.reconcile(baby);
    }
}

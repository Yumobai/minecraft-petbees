package dev.petbees.mixin;

import dev.petbees.BeeOwnership;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityPersistenceMixin {
    // Fabric writes attachments before Bee.addAdditionalSaveData; prepare at the outer save entry.
    @Inject(method = "saveWithoutId", at = @At("HEAD"))
    private void petbees$prepareOwnershipBackup(ValueOutput output, CallbackInfo ci) {
        if ((Object)this instanceof Bee bee) BeeOwnership.reconcile(bee);
    }
}

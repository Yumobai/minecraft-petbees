package dev.petbees;

import java.util.UUID;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.animal.bee.Bee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Independent, Fabric-managed ownership backup. No world scans or global per-bee cache. */
public record BeeOwnership(UUID owner, String name) {
    private static final Logger LOGGER = LoggerFactory.getLogger("petbees");
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.comapFlatMap(value -> {
        UUID parsed = BeeState.uuid(value);
        return parsed == null ? DataResult.error(() -> "Invalid bee owner UUID") : DataResult.success(parsed);
    }, UUID::toString);
    public static final Codec<BeeOwnership> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("Owner").forGetter(BeeOwnership::owner),
            Codec.STRING.optionalFieldOf("OwnerName", "").forGetter(BeeOwnership::name)
    ).apply(i, BeeOwnership::new));
    public static final AttachmentType<BeeOwnership> BACKUP = AttachmentRegistry.createPersistent(PetBees.id("ownership"), CODEC);

    public static void initialize() { /* Register before worlds are loaded. */ }

    /** The exact occupant being released is authoritative; never infer an owner from neighbours. */
    public static void verifyRelease(Bee bee, CompoundTag stored) {
        BeeOwnership expected = fromTag(stored);
        if (expected == null) return;
        var state = GuardService.state(bee);
        if (!state.owns(expected.owner())) {
            state.load(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, bee.registryAccess(), stored));
            state.tame(expected.owner(), expected.name());
            warn(bee, "Restored ownership from exact hive occupant before release");
        } else if (state.ownerName.isEmpty()) state.ownerName = expected.name();
        bee.setPersistenceRequired();
        reconcile(bee);
    }

    public static void reconcile(Bee bee) {
        if (bee.level().isClientSide()) return;
        BeeState state = GuardService.state(bee);
        AttachmentTarget target = (AttachmentTarget)bee;
        BeeOwnership backup = target.getAttached(BACKUP);
        if (!state.tamed() && backup != null) {
            state.tame(backup.owner(), backup.name());
            warn(bee, "Recovered missing primary ownership from Fabric attachment");
        }
        if (state.tamed()) {
            BeeOwnership current = new BeeOwnership(state.owner, state.ownerName);
            if (!current.equals(backup)) target.setAttached(BACKUP, current);
        }
    }

    /** Read the exact same ownership rules in Jade and hive guard selection. */
    public static BeeOwnership fromTag(CompoundTag entityData) {
        var primary = CODEC.parse(NbtOps.INSTANCE, entityData.getCompoundOrEmpty(BeeState.TAG)).result();
        if (primary.isPresent()) return primary.get();
        return CODEC.parse(NbtOps.INSTANCE, entityData.getCompoundOrEmpty(AttachmentTarget.NBT_ATTACHMENT_KEY)
                .getCompoundOrEmpty(BACKUP.identifier().toString())).result().orElse(null);
    }

    public static void warn(Bee bee, String reason) {
        LOGGER.warn("[PetBees ownership] {}: entity={}, dimension={}, pos={}, owner={}",
                reason, bee.getUUID(), bee.level().dimension().identifier(), bee.blockPosition(), GuardService.state(bee).owner);
    }

    public static void logDeath(Bee bee, String reason) {
        var state = GuardService.state(bee);
        LOGGER.info("[PetBees death] {}: entity={}, dimension={}, pos={}, owner={}, name={}, pendingPlayerStings={}",
                reason, bee.getUUID(), bee.level().dimension().identifier(), bee.blockPosition(),
                state.owner, state.ownerName, state.pendingStings);
    }
}

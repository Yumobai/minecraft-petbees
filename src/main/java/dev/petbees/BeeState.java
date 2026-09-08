package dev.petbees;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Per-bee persistent data; no global entity references or background scans. */
public final class BeeState {
    public static final String TAG = "PetBees";
    public UUID owner;
    public String ownerName = "";
    public final Map<UUID, Integer> attempts = new HashMap<>();
    public int pendingStings;
    public int stingTicks;
    public Vec3 origin;
    public BlockPos returnHive;
    public boolean returning;
    public boolean charged;
    public UUID target;
    public int returnTicks;
    public long returnAfter;
    public UUID breeder;
    public String breederName = "";
    public long loveOrder;

    public boolean tamed() { return owner != null; }
    public boolean owns(UUID id) { return owner != null && owner.equals(id); }
    public boolean active() { return origin != null && !returning; }

    public void tame(UUID id, String name) {
        owner = id;
        ownerName = name;
        attempts.clear();
    }

    public void save(ValueOutput root) {
        if (!tamed() && attempts.isEmpty() && breeder == null) return;
        ValueOutput out = root.child(TAG);
        if (owner != null) out.putString("Owner", owner.toString());
        out.putString("OwnerName", ownerName);
        var list = out.childrenList("Attempts");
        attempts.forEach((id, n) -> {
            var row = list.addChild();
            row.putString("Player", id.toString());
            row.putInt("Count", n);
        });
        out.putInt("PendingStings", pendingStings);
        out.putInt("StingTicks", stingTicks);
        if (origin != null) out.store("Origin", Vec3.CODEC, origin);
        out.storeNullable("ReturnHive", BlockPos.CODEC, returnHive);
        out.putBoolean("Returning", returning);
        out.putBoolean("Charged", charged);
        if (target != null) out.putString("Target", target.toString());
        out.putLong("ReturnAfter", returnAfter);
        if (breeder != null) out.putString("Breeder", breeder.toString());
        out.putString("BreederName", breederName);
        out.putLong("LoveOrder", loveOrder);
    }

    public void load(ValueInput root) {
        var in = root.childOrEmpty(TAG);
        UUID incomingOwner = uuid(in.getStringOr("Owner", ""));
        // There is no untame operation: an incomplete reload must not erase a known owner.
        if (incomingOwner == null && tamed()) return;
        owner = incomingOwner;
        ownerName = in.getStringOr("OwnerName", "");
        attempts.clear();
        for (var row : in.childrenListOrEmpty("Attempts")) {
            UUID player = uuid(row.getStringOr("Player", ""));
            if (player != null) attempts.put(player, Math.clamp(row.getIntOr("Count", 0), 0, 2));
        }
        pendingStings = Math.max(0, in.getIntOr("PendingStings", 0));
        stingTicks = Math.clamp(in.getIntOr("StingTicks", 0), 0, 1200);
        origin = in.read("Origin", Vec3.CODEC).orElse(null);
        returnHive = in.read("ReturnHive", BlockPos.CODEC).orElse(null);
        returning = in.getBooleanOr("Returning", false);
        charged = in.getBooleanOr("Charged", false);
        target = uuid(in.getStringOr("Target", ""));
        returnAfter = in.getLongOr("ReturnAfter", 0);
        breeder = uuid(in.getStringOr("Breeder", ""));
        breederName = in.getStringOr("BreederName", "");
        loveOrder = in.getLongOr("LoveOrder", 0);
    }

    public static UUID uuid(String value) {
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return null; }
    }
}

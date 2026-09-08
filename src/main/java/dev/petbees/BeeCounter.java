package dev.petbees;

import java.util.*;
import dev.petbees.mixin.HiveAccessor;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;

/** Debug-only aggregation over lifecycle indexes, never over all entities or loaded chunks. */
public final class BeeCounter {
    private static final Set<Bee> BEES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<BeehiveBlockEntity> HIVES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<UUID, Integer> WATCHERS = new HashMap<>();
    private static final Set<UUID> NEARBY = new HashSet<>();
    private record Location(UUID owner, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                            net.minecraft.world.phys.Vec3 pos, int outside, int inside) {}
    private static List<Location> locations = List.of();
    private static Map<UUID, Count> cached = Map.of();
    private static long tick;
    private static long cacheTick = Long.MIN_VALUE;
    public record Count(int outside, int inside) {
        public int total() { return outside + inside; }
    }
    private static final Count ZERO = new Count(0, 0);
    private BeeCounter() {}

    public static void initialize() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> { if (entity instanceof Bee bee) BEES.add(bee); });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> { if (entity instanceof Bee bee) BEES.remove(bee); });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> { if (entity instanceof BeehiveBlockEntity hive) HIVES.add(hive); });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> { if (entity instanceof BeehiveBlockEntity hive) HIVES.remove(hive); });
        ServerTickEvents.END_SERVER_TICK.register(BeeCounter::onTick);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { WATCHERS.remove(handler.player.getUUID()); NEARBY.remove(handler.player.getUUID()); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
                Commands.literal("petbees")
                    .executes(context -> {
                        context.getSource().sendSuccess(() -> Component.translatable("commands.petbees.help"), false);
                        return 1;
                    })
                    .then(Commands.literal("count").executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        Count count = current(player.getUUID());
                        context.getSource().sendSuccess(() -> description(count), false);
                        context.getSource().sendSuccess(() -> Component.translatable("commands.petbees.scope"), false);
                        return count.total();
                    }).then(Commands.literal("nearby").executes(context -> {
                        var player = context.getSource().getPlayerOrException();
                        var count = nearby(player);
                        context.getSource().sendSuccess(() -> nearbyDescription(count), false);
                        return count.total();
                    })))
                    .then(Commands.literal("watch")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            Count count = current(player.getUUID());
                            NEARBY.remove(player.getUUID());
                            WATCHERS.put(player.getUUID(), count.total());
                            player.sendOverlayMessage(description(count));
                            context.getSource().sendSuccess(() -> Component.translatable("commands.petbees.watch_started"), false);
                            context.getSource().sendSuccess(() -> Component.translatable("commands.petbees.scope"), false);
                            return 1;
                        })
                        .then(Commands.literal("nearby").executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var count = nearby(player);
                            NEARBY.add(player.getUUID());
                            WATCHERS.put(player.getUUID(), count.total());
                            player.sendOverlayMessage(nearbyDescription(count));
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            WATCHERS.remove(player.getUUID());
                            NEARBY.remove(player.getUUID());
                            player.sendOverlayMessage(Component.empty());
                            context.getSource().sendSuccess(() -> Component.translatable("commands.petbees.watch_stopped"), false);
                            return 1;
                        })))));
    }

    private static Component description(Count count) {
        return Component.translatable("commands.petbees.count", count.total(), count.outside(), count.inside());
    }

    public static Count current(UUID owner) {
        // Share one snapshot across all users and repeated commands for 20 ticks.
        if (cacheTick == Long.MIN_VALUE || tick - cacheTick >= 20) {
            cached = scanLoaded();
            cacheTick = tick;
        }
        return cached.getOrDefault(owner, ZERO);
    }

    private static Component nearbyDescription(Count count) {
        return Component.translatable("commands.petbees.nearby", count.total(), count.outside(), count.inside());
    }

    public static Count nearby(ServerPlayer player) {
        current(player.getUUID());
        return nearbyAt(player.getUUID(), player.level().dimension(), player.position());
    }

    static Count nearbyAt(UUID owner, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                          net.minecraft.world.phys.Vec3 pos) {
        int outside = 0, inside = 0;
        for (var row : locations) if (row.owner.equals(owner) && row.dimension.equals(dimension) && row.pos.distanceToSqr(pos) <= 1024) {
            outside += row.outside; inside += row.inside;
        }
        return new Count(outside, inside);
    }

    static boolean registered(Bee bee) {
        return bee.level() instanceof net.minecraft.server.level.ServerLevel level
                && !bee.isRemoved() && level.getEntity(bee.getUUID()) == bee;
    }

    static Map<UUID, Count> scanLoaded() {
        List<Location> snapshot = new ArrayList<>();
        Map<UUID, Count> counts = new HashMap<>();
        for (Bee bee : BEES) {
            if (!bee.isAlive() || !registered(bee)) continue;
            var state = GuardService.state(bee);
            if (state.tamed()) {
                snapshot.add(new Location(state.owner, bee.level().dimension(), bee.position(), 1, 0));
                Count old = counts.getOrDefault(state.owner, ZERO);
                counts.put(state.owner, new Count(old.outside() + 1, old.inside()));
            }
        }
        for (BeehiveBlockEntity hive : HIVES) {
            if (hive.isRemoved() || !(hive.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) continue;
            var chunk = level.getChunkSource().getChunkNow(hive.getBlockPos().getX() >> 4, hive.getBlockPos().getZ() >> 4);
            if (chunk == null || chunk.getBlockEntities().get(hive.getBlockPos()) != hive) continue;
            for (var occupant : ((HiveAccessor)hive).petbees$bees()) {
                var owner = BeeOwnership.fromTag(occupant.entityData().getUnsafe());
                if (owner == null) continue;
                snapshot.add(new Location(owner.owner(), level.dimension(), net.minecraft.world.phys.Vec3.atCenterOf(hive.getBlockPos()), 0, 1));
                Count old = counts.getOrDefault(owner.owner(), ZERO);
                counts.put(owner.owner(), new Count(old.outside(), old.inside() + 1));
            }
        }
        locations = List.copyOf(snapshot);
        return counts;
    }

    private static void onTick(MinecraftServer server) {
        tick++;
        if (WATCHERS.isEmpty() || tick % 20 != 0) return;
        // END_SERVER_TICK observes hive entry/release only after both sides have completed.
        cached = scanLoaded();
        cacheTick = tick;
        var iterator = WATCHERS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { NEARBY.remove(entry.getKey()); iterator.remove(); continue; }
            Count count = NEARBY.contains(entry.getKey()) ? nearbyAt(entry.getKey(), player.level().dimension(), player.position()) : cached.getOrDefault(entry.getKey(), ZERO);
            if (NEARBY.contains(entry.getKey())) {
                player.sendOverlayMessage(nearbyDescription(count));
                entry.setValue(count.total());
                continue;
            }
            int delta = count.total() - entry.getValue();
            player.sendOverlayMessage(Component.translatable("commands.petbees.live", count.total(), count.outside(), count.inside(),
                    delta > 0 ? "+" + delta : Integer.toString(delta)));
            entry.setValue(count.total());
        }
    }

    private static void clear() {
        BEES.clear(); HIVES.clear(); WATCHERS.clear(); NEARBY.clear(); locations = List.of(); cached = Map.of(); tick = 0; cacheTick = Long.MIN_VALUE;
    }
}

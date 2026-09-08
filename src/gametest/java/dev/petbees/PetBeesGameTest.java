package dev.petbees;

import java.util.UUID;
import dev.petbees.mixin.BeeAccessor;
import dev.petbees.mixin.HiveAccessor;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.phys.Vec3;

public class PetBeesGameTest {
    @GameTest public void counterRejectsStaleEntriesAndScopes(GameTestHelper h) {
        UUID owner = UUID.randomUUID();
        Bee real = tame(h);
        GuardService.state(real).tame(owner, "IndexOwner");
        var center = real.position();
        for (int i = 0; i < 250; i++) {
            var stale = EntityTypes.BEE.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
            GuardService.state(stale).tame(owner, "IndexOwner");
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(stale, h.getLevel());
            check(h, BeeCounter.scanLoaded().get(owner).outside() == 1, "Unregistered ghost counted");
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(stale, h.getLevel());
        }
        Bee far = tame(h);
        GuardService.state(far).tame(owner, "IndexOwner");
        far.setPos(center.add(0, 40, 0));
        check(h, BeeCounter.scanLoaded().get(owner).outside() == 2, "Real distant bee missing from global count");
        check(h, BeeCounter.nearbyAt(owner, h.getLevel().dimension(), center).outside() == 1, "Distant bee counted locally");
        check(h, BeeCounter.nearbyAt(owner, net.minecraft.world.level.Level.NETHER, center).total() == 0, "Other dimension counted locally");
        real.discard(); far.discard();
        check(h, !BeeCounter.scanLoaded().containsKey(owner), "Discarded bees counted");
        h.succeed();
    }

    @GameTest public void hiveReleaseOwnershipRepair(GameTestHelper h) {
        Bee original = tame(h);
        GuardService.state(original).pendingStings = 2;
        var record = BeehiveBlockEntity.Occupant.of(original).entityData().getUnsafe();
        var missing = EntityTypes.BEE.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        BeeOwnership.verifyRelease(missing, record);
        check(h, GuardService.state(missing).owns(OWNER), "Exact occupant failed to restore owner");
        check(h, GuardService.state(missing).pendingStings == 2, "Recovery lost pending costs");
        var wild = EntityTypes.BEE.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        BeeOwnership.verifyRelease(wild, new net.minecraft.nbt.CompoundTag());
        check(h, !GuardService.state(wild).tamed(), "Wild bee assigned an owner");
        h.succeed();
    }

    @GameTest public void incidentNaturalDawn(GameTestHelper h) throws Exception {
        DawnRegression.verify(h);
    }
    @GameTest(maxTicks = 100) public void personalBeeCounter(GameTestHelper h) {
        var owner = h.makeMockServerPlayerInLevel();
        UUID id = owner.getUUID();
        Bee pet = tame(h);
        GuardService.state(pet).tame(id, "CounterOwner");
        Bee baby = tame(h);
        baby.setBaby(true);
        GuardService.state(baby).tame(id, "CounterOwner");
        Bee other = tame(h);
        Bee wild = h.spawn(EntityTypes.BEE, 2, 2, 2);
        var initial = BeeCounter.scanLoaded().get(id);
        check(h, initial != null && initial.outside() == 2 && initial.inside() == 0, "Counter must include own baby and exclude other owners/wild bees");
        BlockPos pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, Blocks.BEEHIVE);
        var hive = (BeehiveBlockEntity)h.getLevel().getBlockEntity(h.absolutePos(pos));
        hive.addOccupant(pet);
        hive.addOccupant(wild);
        var housed = BeeCounter.scanLoaded().get(id);
        check(h, housed.outside() == 1 && housed.inside() == 1 && housed.total() == 2, "Hive entry double-counted or lost a pet");
        hive.emptyAllLivingFromHive(null, hive.getBlockState(), BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY);
        var released = BeeCounter.scanLoaded().get(id);
        check(h, released.outside() == 2 && released.inside() == 0, "Hive exit did not update count");
        baby.setHealth(0);
        check(h, BeeCounter.scanLoaded().get(id).total() == 1, "Dead bee still counted");
        var grown = h.getLevel().getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(h.absolutePos(pos)).inflate(5),
                b -> GuardService.state(b).owns(id) && b.isAlive()).getFirst();
        hive.addOccupant(grown);
        check(h, BeeCounter.scanLoaded().get(id).inside() == 1, "Reentered bee not counted");
        h.getLevel().removeBlockEntity(h.absolutePos(pos));
        check(h, !BeeCounter.scanLoaded().containsKey(id), "Removed hive still counted");
        // Verify personal command parsing and source selection using the real dispatcher.
        var commands = h.getLevel().getServer().getCommands();
        var source = owner.createCommandSourceStack();
        h.runAfterDelay(21, () -> {
            try {
                int result = commands.getDispatcher().execute("petbees count", source);
                  check(h, result == 0, "Personal command count wrong");
                  check(h, commands.getDispatcher().execute("petbees count nearby", source) == 0, "Nearby count command failed");
                  check(h, commands.getDispatcher().execute("petbees watch", source) == 1, "Watch command failed");
                  check(h, commands.getDispatcher().execute("petbees watch nearby", source) == 1, "Nearby watch command failed");
                h.runAfterDelay(21, () -> {
                    try {
                        check(h, commands.getDispatcher().execute("petbees watch off", source) == 1, "Watch off command failed");
                        h.succeed();
                    } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
                });
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
        });
    }
    @GameTest(maxTicks = 400) public void manyBeesBinaryPersistence(GameTestHelper h) throws java.io.IOException {
        PersistenceRegression.verify(h);
        h.succeed();
    }
    @GameTest public void ownershipRecoveryAndWildSafety(GameTestHelper h) {
        PersistenceRegression.recovery(h);
        h.succeed();
    }
    @GameTest public void combatHiveOwnershipPersistence(GameTestHelper h) throws java.io.IOException {
        PersistenceRegression.combat(h);
        h.succeed();
    }
    @GameTest public void jadeOwnershipProviders(GameTestHelper h) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("jade")) JadeRegression.verify(h);
        h.succeed();
    }
    private static final UUID OWNER = UUID.fromString("ba629d24-3170-4f97-95cd-8937fbce1c9b");
    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, Component.literal(message));
    }
    private static Bee tame(GameTestHelper h) {
        Bee bee = h.spawn(EntityTypes.BEE, 1, 2, 1);
        GuardService.state(bee).tame(OWNER, "OfflineOwner");
        return bee;
    }

    @GameTest public void stateSurvivesHive(GameTestHelper h) {
        Bee bee = tame(h);
        var s = GuardService.state(bee);
        s.pendingStings = 1;
        bee.setHealth(5);
        var occupant = BeehiveBlockEntity.Occupant.of(bee);
        Bee restored = (Bee) occupant.createEntity(h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1)));
        check(h, restored != null, "Bee failed to leave serialized hive");
        check(h, GuardService.state(restored).owns(OWNER), "Owner lost in hive");
        check(h, GuardService.state(restored).ownerName.equals("OfflineOwner"), "Offline name lost");
        check(h, GuardService.state(restored).pendingStings == 1, "Pending cost lost in hive");
        check(h, restored.getHealth() == 5, "Hive unexpectedly healed bee");
        h.succeed();
    }

    @GameTest public void independentTamingAndBaby(GameTestHelper h) {
        Bee bee = h.spawn(EntityTypes.BEE, 1, 2, 1);
        bee.setBaby(true);
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var s = GuardService.state(bee);
        s.attempts.put(OWNER, 2);
        s.attempts.put(player.getUUID(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PetBees.BEE_SNACK, 3));
        bee.mobInteract(player, InteractionHand.MAIN_HAND);
        check(h, s.owns(player.getUUID()), "Third snack must tame even a baby");
        check(h, s.attempts.isEmpty(), "Attempts not cleared after tame");
        check(h, player.getMainHandItem().getCount() == 2, "Snack count incorrect");
        bee.setTarget(h.spawn(EntityTypes.ZOMBIE, 2, 2, 1));
        check(h, bee.getTarget() == null, "Baby acquired combat target");
        h.succeed();
    }

    @GameTest public void attemptsSurviveHive(GameTestHelper h) {
        Bee bee = h.spawn(EntityTypes.BEE, 1, 2, 1);
        UUID second = UUID.randomUUID();
        GuardService.state(bee).attempts.put(OWNER, 2);
        GuardService.state(bee).attempts.put(second, 1);
        Bee restored = (Bee) BeehiveBlockEntity.Occupant.of(bee).createEntity(h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1)));
        var s = GuardService.state(restored);
        check(h, s.attempts.get(OWNER) == 2 && s.attempts.get(second) == 1, "Per-player attempts merged or lost");
        h.succeed();
    }

    @GameTest public void breedingOwnership(GameTestHelper h) {
        Bee a = tame(h), b = h.spawn(EntityTypes.BEE, 2, 2, 1);
        Bee child = a.getBreedOffspring(h.getLevel(), b);
        check(h, GuardService.state(child).owns(OWNER), "Tame/wild child did not inherit");
        Player breeder = h.makeMockPlayer(GameType.SURVIVAL);
        GuardService.state(b).tame(UUID.randomUUID(), "OtherOwner");
        a.setInLove(breeder);
        b.setInLove(breeder);
        child = a.getBreedOffspring(h.getLevel(), b);
        check(h, GuardService.state(child).owns(breeder.getUUID()), "Different-owner child has wrong breeder");
        h.succeed();
    }

    @GameTest public void ownerIsSafeAndHealing(GameTestHelper h) {
        Bee bee = tame(h);
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        GuardService.state(bee).tame(owner.getUUID(), "Owner");
        bee.setTarget(owner);
        bee.setLastHurtByMob(owner);
        check(h, bee.getTarget() == null && bee.getLastHurtByMob() == null, "Owner provokes own bee");
        check(h, !bee.doHurtTarget(h.getLevel(), owner), "Bee stung its owner");
        bee.setHealth(1);
        GuardService.state(bee).pendingStings = 1;
        Player helper = h.makeMockPlayer(GameType.SURVIVAL);
        helper.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(PetBees.BEE_SNACK));
        bee.mobInteract(helper, InteractionHand.MAIN_HAND);
        check(h, bee.getHealth() == bee.getMaxHealth(), "Snack did not fully heal");
        check(h, GuardService.state(bee).pendingStings == 0, "Healing left delayed injury");
        h.succeed();
    }

    @GameTest public void nonPlayerCombatKeepsSting(GameTestHelper h) {
        Bee bee = tame(h);
        var zombie = h.spawn(EntityTypes.ZOMBIE, 2, 2, 1);
        GuardService.start(bee, zombie, null);
        check(h, bee.doHurtTarget(h.getLevel(), zombie), "Bee attack failed");
        check(h, !bee.hasStung(), "Pet bee lost sting on non-player attack");
        check(h, GuardService.state(bee).pendingStings == 0, "Non-player caused health cost");
        check(h, bee.getHealth() == 10 && bee.getTarget() == zombie, "Attack damaged bee or cleared target");
        h.succeed();
    }

    @GameTest public void wildBeeUnchanged(GameTestHelper h) {
        Bee wild = h.spawn(EntityTypes.BEE, 1, 2, 1);
        var zombie = h.spawn(EntityTypes.ZOMBIE, 2, 2, 1);
        check(h, wild.doHurtTarget(h.getLevel(), zombie), "Wild sting failed");
        check(h, wild.hasStung(), "Wild bee no longer follows vanilla sting logic");
        check(h, !GuardService.state(wild).tamed(), "Wild bee became tame");
        h.succeed();
    }

    @GameTest public void healthRuleBoundaries(GameTestHelper h) {
        check(h, BeeRules.healthAfterSting(10) == 5, "10 -> 5");
        for (int n = 2; n <= 5; n++) check(h, BeeRules.healthAfterSting(n) == 1, n + " -> 1");
        check(h, BeeRules.healthAfterSting(1) == 0, "1 -> death");
        check(h, BeeRules.tames(3, 0.99F) && !BeeRules.tames(2, 0.30F) && BeeRules.tames(1, 0.29F), "Taming boundary wrong");
        h.succeed();
    }

    @GameTest public void playerStingDelayedOncePerTrip(GameTestHelper h) {
        Bee bee = tame(h);
        Player enemy = h.makeMockPlayer(GameType.SURVIVAL);
        enemy.setPos(bee.position().add(1, 0, 0));
        GuardService.start(bee, enemy, null);
        check(h, bee.doHurtTarget(h.getLevel(), enemy), "Player sting failed");
        var s = GuardService.state(bee);
        check(h, s.pendingStings == 1 && bee.getHealth() == 10, "Cost must be delayed");
        enemy.invulnerableTime = 0;
        check(h, bee.doHurtTarget(h.getLevel(), enemy), "Repeated sting failed");
        check(h, s.pendingStings == 1, "Same trip charged twice");
        var access = (dev.petbees.testmixin.BeeTestAccess) bee;
        access.petbees$timer(1199);
        access.petbees$tick(h.getLevel());
        check(h, s.pendingStings == 0 && bee.getHealth() == 5, "Vanilla delay did not settle 5 HP");
        s.pendingStings = 1;
        access.petbees$timer(1199);
        access.petbees$tick(h.getLevel());
        check(h, bee.getHealth() == 1 && bee.isAlive(), "Second cost must leave 1 HP");
        s.pendingStings = 1;
        access.petbees$timer(1199);
        access.petbees$tick(h.getLevel());
        check(h, !bee.isAlive(), "Third cost at 1 HP must kill");
        h.succeed();
    }

    @GameTest public void selectiveHiveReleaseAtNight(GameTestHelper h) {
        h.setTime(18000);
        var owner = h.makeMockServerPlayerInLevel();
        BlockPos pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, Blocks.BEEHIVE);
        var hive = (BeehiveBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(pos));
        Bee adult = tame(h), wild = h.spawn(EntityTypes.BEE, 2, 2, 1), baby = tame(h);
        GuardService.state(adult).tame(owner.getUUID(), "Owner");
        GuardService.state(baby).tame(owner.getUUID(), "Owner");
        baby.setBaby(true);
        hive.addOccupant(adult);
        hive.addOccupant(wild);
        hive.addOccupant(baby);
        var enemy = h.spawn(EntityTypes.ZOMBIE, 2, 2, 1);
        ((HiveAccess) hive).petbees$releaseGuards(owner, enemy);
        check(h, hive.getOccupantCount() == 2, "Hive must release only owned adult");
        var remaining = ((HiveAccessor) hive).petbees$bees();
        check(h, remaining.stream().anyMatch(o -> o.entityData().getUnsafe().getIntOr("Age", 0) < 0), "Baby was forced out");
        var guards = h.getLevel().getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(h.absolutePos(pos)).inflate(5),
                b -> GuardService.state(b).owns(owner.getUUID()) && !b.isBaby());
        check(h, guards.size() == 1 && guards.getFirst().getTarget() == enemy, "Released adult did not engage");
        check(h, GuardService.state(guards.getFirst()).returnHive.equals(h.absolutePos(pos)), "Return home not recorded");
        h.succeed();
    }

    @GameTest public void damageEventAndReturnBoundary(GameTestHelper h) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "BeeTestOwner"), false);
        var owner = new net.minecraft.server.level.ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, owner, cookie);
        owner.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        owner.setGameMode(GameType.SURVIVAL);
        owner.getAbilities().invulnerable = false;
        Bee bee = tame(h);
        GuardService.state(bee).tame(owner.getUUID(), "Owner");
        owner.setPos(bee.position().add(0, 0, 1));
        var zombie = h.spawn(EntityTypes.ZOMBIE, 2, 2, 1);
        zombie.setNoAi(true);
        owner.invulnerableTime = 0;
        check(h, owner.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(zombie), 1), "Owner damage failed");
        GuardService.flush(h.getLevel().getServer());
        var s = GuardService.state(bee);
        check(h, s.active() && bee.getTarget() == zombie, "Real damage event did not summon bee");
        Vec3 origin = s.origin;
        var second = h.spawn(EntityTypes.ZOMBIE, 3, 2, 1);
        second.setNoAi(true);
        second.setTarget(owner);
        zombie.discard();
        GuardService.tick(bee, h.getLevel());
        check(h, bee.getTarget() == second && s.origin.equals(origin), "Bee did not continue against next attacker");
        second.setPos(origin.add(33, 0, 0));
        GuardService.tick(bee, h.getLevel());
        check(h, s.returning && bee.getTarget() == null, "Bee chased outside 32-block boundary");
        bee.setPos(origin);
        new ReturnGoal(bee).tick();
        check(h, s.origin == null && !s.returning, "Returning bee did not restore normal AI");
        second.setPos(origin.add(1, 0, 0));
        GuardService.start(bee, second, null);
        owner.setPos(origin.add(33, 0, 0));
        GuardService.tick(bee, h.getLevel());
        check(h, s.returning, "Bee did not retreat when owner left");
        second.discard();
        h.succeed();
    }

    @GameTest public void ownerHarvestAndReturnHome(GameTestHelper h) {
        Player owner = h.makeMockPlayer(GameType.SURVIVAL);
        BlockPos pos = new BlockPos(1, 1, 1);
        h.setBlock(pos, Blocks.BEEHIVE);
        var hive = (BeehiveBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(pos));
        Bee pet = tame(h), wild = h.spawn(EntityTypes.BEE, 2, 2, 1);
        GuardService.state(pet).tame(owner.getUUID(), "Owner");
        hive.addOccupant(pet);
        hive.addOccupant(wild);
        owner.setPos(Vec3.atCenterOf(h.absolutePos(pos)).add(0, 0, -1));
        hive.emptyAllLivingFromHive(owner, hive.getBlockState(), BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY);
        var bees = h.getLevel().getEntitiesOfClass(Bee.class, new net.minecraft.world.phys.AABB(h.absolutePos(pos)).inflate(3));
        var releasedPet = bees.stream().filter(b -> GuardService.state(b).owns(owner.getUUID())).findFirst().orElseThrow();
        var releasedWild = bees.stream().filter(b -> !GuardService.state(b).tamed()).findFirst().orElseThrow();
        check(h, releasedPet.getTarget() == null && GuardService.state(releasedPet).returning, "Pet angry after harvest");
        check(h, releasedWild.getTarget() == owner, "Wild hive bee anger changed");
        GuardService.state(releasedPet).returnAfter = 0;
        releasedPet.setPos(Vec3.atCenterOf(h.absolutePos(pos)));
        new ReturnGoal(releasedPet).tick();
        check(h, hive.getOccupantCount() == 1 && releasedPet.isRemoved(), "Pet failed to reenter original hive");
        check(h, ((HiveAccessor)hive).petbees$bees().getFirst().entityData().getUnsafe().getCompoundOrEmpty(BeeState.TAG)
                .getStringOr("Owner", "").equals(owner.getUUID().toString()), "Hive return lost ownership");
        h.succeed();
    }

    @GameTest public void shapelessRecipesReturnBottles(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 2, java.util.List.of(
                ItemStack.EMPTY, new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE),
                new ItemStack(net.minecraft.world.item.Items.HONEYCOMB), ItemStack.EMPTY));
        var recipe = manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel()).orElseThrow().value();
        check(h, recipe.assemble(input).is(PetBees.BEE_SNACK), "Small recipe missing");
        check(h, recipe.getRemainingItems(input).stream().filter(s -> s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)).count() == 1, "Small recipe bottle missing");
        var bulk = net.minecraft.world.item.crafting.CraftingInput.of(3, 2, java.util.List.of(
                new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE), new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE), ItemStack.EMPTY,
                new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE), new ItemStack(net.minecraft.world.item.Items.HONEY_BLOCK), new ItemStack(net.minecraft.world.item.Items.HONEY_BOTTLE)));
        var bulkRecipe = manager.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, bulk, h.getLevel()).orElseThrow().value();
        var output = bulkRecipe.assemble(bulk);
        check(h, output.is(PetBees.BEE_SNACK) && output.getCount() == 4, "Bulk recipe output wrong");
        check(h, bulkRecipe.getRemainingItems(bulk).stream().filter(s -> s.is(net.minecraft.world.item.Items.GLASS_BOTTLE)).count() == 4, "Bulk bottles missing");
        h.succeed();
    }
}

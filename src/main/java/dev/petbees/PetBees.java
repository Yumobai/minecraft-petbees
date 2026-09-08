package dev.petbees;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class PetBees implements ModInitializer {
    public static final String ID = "petbees";
    public static Item BEE_SNACK;
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }

    @Override public void onInitialize() {
        BeeOwnership.initialize();
        BeeCounter.initialize();
        var key = ResourceKey.create(Registries.ITEM, id("bee_snack"));
        BEE_SNACK = Registry.register(BuiltInRegistries.ITEM, key,
                new Item(new Item.Properties().setId(key)));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS)
                .register(entries -> entries.insertAfter(Items.HONEYCOMB, BEE_SNACK));
        ServerTickEvents.END_SERVER_TICK.register(GuardService::flush);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> GuardService.clear());
    }
}

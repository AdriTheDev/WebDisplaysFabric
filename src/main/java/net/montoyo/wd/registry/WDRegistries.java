package net.montoyo.wd.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.montoyo.wd.block.KeyboardBlockLeft;
import net.montoyo.wd.block.KeyboardBlockRight;
import net.montoyo.wd.block.ScreenBlock;
import net.montoyo.wd.entity.KeyboardBlockEntity;
import net.montoyo.wd.entity.ScreenBlockEntity;

import java.util.function.Function;

public class WDRegistries {

    // === BLOCKS ===
    public static final ScreenBlock SCREEN_BLOCK = registerBlock("screen", ScreenBlock::new,
            BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL));
    public static final KeyboardBlockLeft KEYBOARD_LEFT = registerBlock("kb_left", KeyboardBlockLeft::new,
            BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL));
    public static final KeyboardBlockRight KEYBOARD_RIGHT = registerBlock("kb_right", KeyboardBlockRight::new,
            BlockBehaviour.Properties.of().strength(2.0f).sound(SoundType.METAL));

    // === BLOCK ITEMS ===
    // useBlockDescriptionPrefix() makes these resolve "block.webdisplays.*" translation keys
    // rather than "item.webdisplays.*", which is where the block names live in the lang files.
    public static final Item SCREEN_ITEM = registerBlockItem("screen", SCREEN_BLOCK);
    public static final Item KEYBOARD_LEFT_ITEM = registerBlockItem("kb_left", KEYBOARD_LEFT);
    public static final Item KEYBOARD_RIGHT_ITEM = registerBlockItem("kb_right", KEYBOARD_RIGHT);

    // === ITEMS ===
    public static final Item CONFIGURATOR = registerItem("screencfg", net.montoyo.wd.item.ItemScreenConfigurator::new);
    public static final Item LINKER = registerItem("linker", net.montoyo.wd.item.ItemLinker::new);

    // === BLOCK ENTITIES ===
    public static BlockEntityType<ScreenBlockEntity> SCREEN_BLOCK_ENTITY;
    public static BlockEntityType<KeyboardBlockEntity> KEYBOARD_BLOCK_ENTITY;

    // === SOUNDS ===
    public static SoundEvent KEYBOARD_TYPE;
    public static SoundEvent SCREENCFG_OPEN;

    // === CREATIVE TAB ===
    public static final ResourceKey<CreativeModeTab> CREATIVE_TAB_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("main"));
    public static CreativeModeTab CREATIVE_TAB;

    private static <T extends Block> T registerBlock(String name, Function<BlockBehaviour.Properties, T> factory,
                                                     BlockBehaviour.Properties properties) {
        Identifier blockId = id(name);
        T block = factory.apply(properties.setId(ResourceKey.create(Registries.BLOCK, blockId)));
        return Registry.register(BuiltInRegistries.BLOCK, blockId, block);
    }

    private static Item registerBlockItem(String name, Block block) {
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(name));
        Item.Properties props = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
        return Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, props));
    }

    private static <T extends Item> T registerItem(String name, Function<Item.Properties, T> factory) {
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(name));
        return Registry.register(BuiltInRegistries.ITEM, itemKey, factory.apply(new Item.Properties().setId(itemKey)));
    }

    private static SoundEvent registerSound(String name) {
        Identifier soundId = id(name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, soundId, SoundEvent.createVariableRangeEvent(soundId));
    }

    public static void register() {
        // Register sounds
        KEYBOARD_TYPE = registerSound("keyboard_type");
        SCREENCFG_OPEN = registerSound("screencfg_open");

        // Register block entities
        SCREEN_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("screen"),
                FabricBlockEntityTypeBuilder.create(ScreenBlockEntity::new, SCREEN_BLOCK).build());
        KEYBOARD_BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("kb_left"),
                FabricBlockEntityTypeBuilder.create(KeyboardBlockEntity::new, KEYBOARD_LEFT, KEYBOARD_RIGHT).build());

        // Register creative tab
        CREATIVE_TAB = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                CREATIVE_TAB_KEY,
                FabricCreativeModeTab.builder()
                        .title(Component.translatable("itemGroup.webdisplays"))
                        .icon(() -> new ItemStack(SCREEN_BLOCK))
                        .displayItems((params, output) -> {
                            output.accept(SCREEN_ITEM);
                            output.accept(KEYBOARD_LEFT_ITEM);
                            output.accept(KEYBOARD_RIGHT_ITEM);
                            output.accept(CONFIGURATOR);
                            output.accept(LINKER);
                        })
                        .build());
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("webdisplays", path);
    }
}

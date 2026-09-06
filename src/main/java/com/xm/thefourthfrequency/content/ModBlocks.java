package com.xm.thefourthfrequency.content;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

/**
 * The encounter owns three blocks and no decoration.
 *
 * <p>It used to own two more: a cage ringing every stability anchor and a core for each of the
 * twenty warp gates. The gate structures stopped being built long before this, and the cage was a
 * bright band wrapped around the one thing in the arena a player is supposed to look at. Both are
 * gone rather than retextured. The resonance core alone owns a restrained custom surface: its top
 * identifies the terminal socket, while the altar around it remains built from familiar vanilla
 * materials.</p>
 */
public final class ModBlocks {
	public static final ResonanceCoreBlock RESONANCE_CORE = registerCustom("resonance_core",
			BlockBehaviour.Properties.ofFullCopy(Blocks.CRYING_OBSIDIAN)
					.strength(Block.INDESTRUCTIBLE, 3_600_000.0F).noLootTable().lightLevel(state -> 12),
			ResonanceCoreBlock::new);
	public static final WorldInterfaceExitPortalBlock WORLD_INTERFACE_EXIT_PORTAL = registerCustom(
			"world_interface_exit_portal", BlockBehaviour.Properties.ofFullCopy(Blocks.END_PORTAL)
					.strength(Block.INDESTRUCTIBLE, 3_600_000.0F).noLootTable().lightLevel(state -> 15),
			WorldInterfaceExitPortalBlock::new);
	/** Client-side visual proxy for the missing-texture anomaly. Never placed in the real world. */
	public static final Block MISSING_TEXTURE_PROXY = register("missing_texture_proxy", Blocks.BLACK_CONCRETE, -1.0F);

	/**
	 * The unrendered layer's four surfaces, and the two of them that are lying.
	 *
	 * <p>The layer owns its wall and floor rather than borrowing vanilla blocks, because the way out
	 * is a panel that is <em>slightly the wrong shade</em> and "slightly" has to be a number somebody
	 * chose. Generating both halves of each pair from one recipe (see
	 * {@code tools/generate_unrendered_textures.py}) makes the mottling identical and the colour the
	 * only difference, which is not something matching a hand-authored vanilla texture could promise.
	 *
	 * <p>The false pair is {@code noCollission} and nothing else: they are full opaque cubes that draw
	 * and occlude exactly like their solid twins, so the region reads as a solid block of building
	 * from every angle and cannot be seen into. Walking into one passes through it. That is the entire
	 * exit mechanism, and it is the same trick the layer opened with - getting in was the floor
	 * declining to be solid, getting out is a wall doing the same.
	 *
	 * <p>All four are indestructible and drop nothing. {@code UnrenderedBlockPolicy} already refuses
	 * every break in the dimension, so this is the second lock rather than the first.
	 */
	public static final Block UNRENDERED_WALL = registerLayerSurface("unrendered_wall", true);
	public static final Block UNRENDERED_FALSE_WALL = registerLayerSurface("unrendered_false_wall", false);
	public static final Block UNRENDERED_FLOOR = registerLayerSurface("unrendered_floor", true);
	public static final Block UNRENDERED_FALSE_FLOOR = registerLayerSurface("unrendered_false_floor", false);

	private ModBlocks() {
	}

	public static void initialize() {
		TheFourthFrequency.LOGGER.info("Registered World Interface and anomaly proxy blocks");
	}

	private static Block register(String path, Block copy, float strength) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		Block block = new Block(BlockBehaviour.Properties.ofFullCopy(copy).strength(strength).setId(key));
		return Registry.register(BuiltInRegistries.BLOCK, key, block);
	}

	/**
	 * A layer surface: an indestructible full cube that either stops you or does not.
	 *
	 * <p>{@code noCollission} is the whole difference between the pair. It removes the collision box
	 * and leaves everything else - model, occlusion, light blocking - identical, which is what lets a
	 * false panel sit in a wall without being visible as one.
	 */
	private static Block registerLayerSurface(String path, boolean solid) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
				.strength(Block.INDESTRUCTIBLE, 3_600_000.0F)
				.noLootTable()
				.setId(key);
		// forceSolidOn is not optional beside noCollision. Without it a collision-less block is treated
		// as non-solid for occlusion and light, so the false panel would let light bleed through and
		// stop culling what is behind it - both of which are exactly the tell it must not have.
		if (!solid) properties = properties.noCollision().forceSolidOn();
		return Registry.register(BuiltInRegistries.BLOCK, key, new Block(properties));
	}

	private static <T extends Block> T registerCustom(String path, BlockBehaviour.Properties properties,
			Function<BlockBehaviour.Properties, T> factory) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id);
		T block = factory.apply(properties.setId(key));
		return Registry.register(BuiltInRegistries.BLOCK, key, block);
	}
}

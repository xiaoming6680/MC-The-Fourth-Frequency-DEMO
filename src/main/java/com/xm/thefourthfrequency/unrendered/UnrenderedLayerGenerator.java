package com.xm.thefourthfrequency.unrendered;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import com.xm.thefourthfrequency.content.ModBlocks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Builds the unrendered layer: six blocks of fixed geometry per column, no noise, no features.
 *
 * <p>Cheaper than any vanilla generator by a wide margin. There is no density function, no aquifer,
 * no surface rule and no carver - a chunk is one thousand five hundred and thirty-six writes decided
 * by four hash lookups per column, and it completes without reading a neighbour. That is what makes
 * an endless floor plan affordable at all, and it is the reason this is a generator rather than a
 * very large structure.
 */
public final class UnrenderedLayerGenerator extends ChunkGenerator {
	/** @see UnrenderedLayerLayout#DEFAULT_SEED */
	public static final long DEFAULT_SEED = UnrenderedLayerLayout.DEFAULT_SEED;

	public static final MapCodec<UnrenderedLayerGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
			instance.group(
					BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
					Codec.LONG.optionalFieldOf("seed", DEFAULT_SEED).forGetter(generator -> generator.seed))
					.apply(instance, UnrenderedLayerGenerator::new));

	private static final BlockState FLOOR = ModBlocks.UNRENDERED_FLOOR.defaultBlockState();
	private static final BlockState WALL = ModBlocks.UNRENDERED_WALL.defaultBlockState();
	/** Draws as {@link #WALL} and stops nobody. The way out. */
	private static final BlockState FALSE_WALL = ModBlocks.UNRENDERED_FALSE_WALL.defaultBlockState();
	/** Draws as {@link #FLOOR} and holds nobody up. Behind the false wall. */
	private static final BlockState FALSE_FLOOR = ModBlocks.UNRENDERED_FALSE_FLOOR.defaultBlockState();
	private static final BlockState CEILING = Blocks.SMOOTH_QUARTZ.defaultBlockState();
	private static final BlockState PANEL = Blocks.OCHRE_FROGLIGHT.defaultBlockState();

	private final long seed;

	public UnrenderedLayerGenerator(BiomeSource biomeSource, long seed) {
		super(biomeSource);
		this.seed = seed;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
			StructureManager structures, ChunkAccess chunk) {
		ChunkPos chunkPos = chunk.getPos();
		int originX = chunkPos.getMinBlockX();
		int originZ = chunkPos.getMinBlockZ();
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int offsetX = 0; offsetX < 16; offsetX++) {
			for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
				int x = originX + offsetX;
				int z = originZ + offsetZ;
				// There is always a floor block here; the only question is whether it holds anyone up.
				// An actual gap would be visible as one from across the room, which is the tell the
				// whole exit design exists to avoid.
				BlockState floor = UnrenderedMazePolicy.falseFloor(seed, x, z) ? FALSE_FLOOR : FLOOR;
				place(chunk, cursor, offsetX, UnrenderedLayerLayout.FLOOR_Y, offsetZ, floor,
						oceanFloor, worldSurface);
				BlockState column = UnrenderedMazePolicy.falseWall(seed, x, z) ? FALSE_WALL
						: UnrenderedMazePolicy.wall(seed, x, z) ? WALL : null;
				if (column != null) {
					for (int y = UnrenderedLayerLayout.INTERIOR_BOTTOM_Y;
							y <= UnrenderedLayerLayout.INTERIOR_TOP_Y; y++) {
						place(chunk, cursor, offsetX, y, offsetZ, column, oceanFloor, worldSurface);
					}
				}
				BlockState ceiling = UnrenderedMazePolicy.ceilingLight(seed, x, z) ? PANEL : CEILING;
				place(chunk, cursor, offsetX, UnrenderedLayerLayout.CEILING_Y, offsetZ, ceiling,
						oceanFloor, worldSurface);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private static void place(ChunkAccess chunk, BlockPos.MutableBlockPos cursor, int offsetX, int y,
			int offsetZ, BlockState state, Heightmap oceanFloor, Heightmap worldSurface) {
		chunk.setBlockState(cursor.set(offsetX, y, offsetZ), state);
		oceanFloor.update(offsetX, y, offsetZ, state);
		worldSurface.update(offsetX, y, offsetZ, state);
	}

	/** Nothing is carved. The layer has no terrain for a carver to cut into. */
	@Override
	public void applyCarvers(WorldGenRegion region, long carverSeed, RandomState randomState,
			BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {
	}

	/** Every block is placed by {@link #fillFromNoise}; there is no surface layer to build. */
	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState randomState,
			ChunkAccess chunk) {
	}

	/**
	 * No decoration, ever.
	 *
	 * <p>The biome carries no features, so the inherited pass would find nothing to place - but this
	 * override is what keeps that true if some other mod adds a feature to every biome. One
	 * unexpected tree in the floor plan would say more about how the layer is made than the whole
	 * rest of the presentation manages to hide.
	 */
	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
	}

	/** Nothing spawns here on its own. The biome's spawner lists are empty for the same reason. */
	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getGenDepth() {
		return UnrenderedLayerLayout.WORLD_HEIGHT;
	}

	@Override
	public int getMinY() {
		return UnrenderedLayerLayout.MIN_Y;
	}

	@Override
	public int getSeaLevel() {
		return UnrenderedLayerLayout.MIN_Y;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
			RandomState randomState) {
		if (UnrenderedMazePolicy.falseFloor(seed, x, z)) return UnrenderedLayerLayout.MIN_Y;
		return UnrenderedMazePolicy.wall(seed, x, z)
				? UnrenderedLayerLayout.CEILING_Y + 1
				: UnrenderedLayerLayout.FLOOR_Y + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState[] column = new BlockState[UnrenderedLayerLayout.WORLD_HEIGHT];
		BlockState solidColumn = UnrenderedMazePolicy.falseWall(seed, x, z) ? FALSE_WALL
				: UnrenderedMazePolicy.wall(seed, x, z) ? WALL : null;
		BlockState floor = UnrenderedMazePolicy.falseFloor(seed, x, z) ? FALSE_FLOOR : FLOOR;
		for (int index = 0; index < column.length; index++) {
			int y = UnrenderedLayerLayout.MIN_Y + index;
			if (y == UnrenderedLayerLayout.FLOOR_Y) {
				column[index] = floor;
			} else if (y == UnrenderedLayerLayout.CEILING_Y) {
				column[index] = UnrenderedMazePolicy.ceilingLight(seed, x, z) ? PANEL : CEILING;
			} else if (y >= UnrenderedLayerLayout.INTERIOR_BOTTOM_Y
					&& y <= UnrenderedLayerLayout.INTERIOR_TOP_Y) {
				column[index] = solidColumn == null ? air : solidColumn;
			} else {
				column[index] = air;
			}
		}
		return new NoiseColumn(UnrenderedLayerLayout.MIN_Y, column);
	}

	@Override
	public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos) {
		lines.add("Unrendered layer cell " + UnrenderedLayerLayout.cell(pos.getX())
				+ ", " + UnrenderedLayerLayout.cell(pos.getZ()));
	}
}

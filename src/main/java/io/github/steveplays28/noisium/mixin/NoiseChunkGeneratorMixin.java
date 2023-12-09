package io.github.steveplays28.noisium.mixin;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import org.spongepowered.asm.mixin.*;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin extends ChunkGenerator {
	@Shadow
	@Final
	public Holder<NoiseGeneratorSettings> settings;

	public NoiseChunkGeneratorMixin(BiomeSource biomeSource) {
		super(biomeSource);
	}

	/**
	 * @author Steveplays28
	 * @reason Direct palette storage blockstate set optimisation
	 */
	@Overwrite
	private ChunkAccess doFill(Blender blender, StructureManager structureAccessor, RandomState noiseConfig, ChunkAccess chunk, int minimumCellY, int cellHeight) {
		final NoiseChunk chunkNoiseSampler = chunk.getOrCreateNoiseChunk(
				chunk2 -> ((NoiseBasedChunkGenerator) (Object) this).createNoiseChunk(chunk2, structureAccessor, blender, noiseConfig));
		final Heightmap oceanFloorHeightMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		final Heightmap worldSurfaceHeightMap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		final ChunkPos chunkPos = chunk.getPos();
		final int chunkPosStartX = chunkPos.getMinBlockX();
		final int chunkPosStartZ = chunkPos.getMinBlockZ();
		final var aquiferSampler = chunkNoiseSampler.aquifer();

		chunkNoiseSampler.initializeForFirstCellX();

		final int horizontalCellBlockCount = chunkNoiseSampler.cellWidth();
		final int verticalCellBlockCount = chunkNoiseSampler.cellHeight();
		final int horizontalCellCount = 16 / horizontalCellBlockCount;
		final var mutableBlockPos = new BlockPos.Mutable();

		for (int baseHorizontalWidthCellIndex = 0; baseHorizontalWidthCellIndex < horizontalCellCount; ++baseHorizontalWidthCellIndex) {
			chunkNoiseSampler.sampleEndDensity(baseHorizontalWidthCellIndex);

			for (int baseHorizontalLengthCellIndex = 0; baseHorizontalLengthCellIndex < horizontalCellCount; ++baseHorizontalLengthCellIndex) {
				var nextChunkSectionIndex = chunk.countVerticalSections() - 1;
				var chunkSection = chunk.getSection(nextChunkSectionIndex);

				for (int verticalCellHeightIndex = cellHeight - 1; verticalCellHeightIndex >= 0; --verticalCellHeightIndex) {
					chunkNoiseSampler.onSampledCellCorners(verticalCellHeightIndex, baseHorizontalLengthCellIndex);

					for (int verticalCellBlockIndex = verticalCellBlockCount - 1; verticalCellBlockIndex >= 0; --verticalCellBlockIndex) {
						int blockPosY = (minimumCellY + verticalCellHeightIndex) * verticalCellBlockCount + verticalCellBlockIndex;
						int chunkSectionBlockPosY = blockPosY & 0xF;
						int chunkSectionIndex = chunk.getSectionIndex(blockPosY);

						if (nextChunkSectionIndex != chunkSectionIndex) {
							nextChunkSectionIndex = chunkSectionIndex;
							chunkSection = chunk.getSection(chunkSectionIndex);
						}

						double deltaY = (double) verticalCellBlockIndex / verticalCellBlockCount;
						chunkNoiseSampler.interpolateY(blockPosY, deltaY);

						for (int horizontalWidthCellBlockIndex = 0; horizontalWidthCellBlockIndex < horizontalCellBlockCount; ++horizontalWidthCellBlockIndex) {
							int blockPosX = chunkPosStartX + baseHorizontalWidthCellIndex * horizontalCellBlockCount + horizontalWidthCellBlockIndex;
							int chunkSectionBlockPosX = blockPosX & 0xF;
							double deltaX = (double) horizontalWidthCellBlockIndex / horizontalCellBlockCount;

							chunkNoiseSampler.interpolateX(blockPosX, deltaX);

							for (int horizontalLengthCellBlockIndex = 0; horizontalLengthCellBlockIndex < horizontalCellBlockCount; ++horizontalLengthCellBlockIndex) {
								int blockPosZ = chunkPosStartZ + baseHorizontalLengthCellIndex * horizontalCellBlockCount + horizontalLengthCellBlockIndex;
								int chunkSectionBlockPosZ = blockPosZ & 0xF;
								double deltaZ = (double) horizontalLengthCellBlockIndex / horizontalCellBlockCount;

								chunkNoiseSampler.interpolateZ(blockPosZ, deltaZ);
								BlockState blockState = chunkNoiseSampler.sampleBlockState();

								if (blockState == null) {
									blockState = ((NoiseChunkGenerator) (Object) this).settings.value().defaultBlock();
								}

								if (blockState == NoiseChunkGenerator.AIR || SharedConstants.isOutsideGenerationArea(chunk.getPos())) {
									continue;
								}

								// Update the non empty block count to avoid issues with MC's lighting engine and other systems not recognising the direct palette storage set
								// See ChunkSection#setBlockState
								chunkSection.nonEmptyBlockCount += 1;

								if (!blockState.getFluidState().isEmpty()) {
									chunkSection.nonEmptyFluidCount += 1;
								}

								if (blockState.hasRandomTicks()) {
									chunkSection.randomTickableBlockCount += 1;
								}

								// Set the blockstate in the palette storage directly to improve performance
								var blockStateId = chunkSection.blockStateContainer.data.palette.index(blockState);
								chunkSection.blockStateContainer.data.storage().set(
										chunkSection.blockStateContainer.paletteProvider.computeIndex(chunkSectionBlockPosX,
												chunkSectionBlockPosY, chunkSectionBlockPosZ
										), blockStateId);

								oceanFloorHeightMap.trackUpdate(chunkSectionBlockPosX, blockPosY, chunkSectionBlockPosZ, blockState);
								worldSurfaceHeightMap.trackUpdate(chunkSectionBlockPosX, blockPosY, chunkSectionBlockPosZ, blockState);

								if (!aquiferSampler.needsFluidTick() || blockState.getFluidState().isEmpty()) {
									continue;
								}

								mutableBlockPos.set(blockPosX, blockPosY, blockPosZ);
								chunk.markBlockForPostProcessing(mutableBlockPos);
							}
						}
					}
				}
			}

			chunkNoiseSampler.swapBuffers();
		}

		chunkNoiseSampler.stopInterpolation();
		return chunk;
	}

	/**
	 * @author Steveplays28
	 * @reason Micro-optimisation
	 */
	@Overwrite
	@SuppressWarnings("ForLoopReplaceableByForEach")
	public CompletableFuture<Chunk> populateNoise(Executor executor, Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
		GenerationShapeConfig generationShapeConfig = this.settings.value().generationShapeConfig().trimHeight(chunk.getHeightLimitView());
		int minimumY = generationShapeConfig.minimumY();
		int generationShapeHeightFloorDiv = Math.floorDiv(generationShapeConfig.height(), generationShapeConfig.verticalCellBlockCount());

		if (generationShapeHeightFloorDiv <= 0) {
			return CompletableFuture.completedFuture(chunk);
		}

		int minimumYFloorDiv = Math.floorDiv(minimumY, generationShapeConfig.verticalCellBlockCount());
		int startingChunkSectionIndex = chunk.getSectionIndex(
				generationShapeHeightFloorDiv * generationShapeConfig.verticalCellBlockCount() - 1 + minimumY);
		int minimumYChunkSectionIndex = chunk.getSectionIndex(minimumY);
		ArrayList<ChunkSection> chunkSections = new ArrayList<>();

		for (int chunkSectionIndex = startingChunkSectionIndex; chunkSectionIndex >= minimumYChunkSectionIndex; --chunkSectionIndex) {
			ChunkSection chunkSection = chunk.getSection(chunkSectionIndex);

			chunkSection.lock();
			chunkSections.add(chunkSection);
		}

		return CompletableFuture.supplyAsync(
				Util.debugSupplier("wgen_fill_noise",
						() -> this.populateNoise(blender, structureAccessor, noiseConfig, chunk, minimumYFloorDiv,
								generationShapeHeightFloorDiv
						)
				), Util.getMainWorkerExecutor()).whenCompleteAsync((chunk2, throwable) -> {
			// Replace an enhanced for loop with a fori loop
			for (int i = 0; i < chunkSections.size(); i++) {
				chunkSections.get(i).unlock();
			}
		}, executor);
	}
}

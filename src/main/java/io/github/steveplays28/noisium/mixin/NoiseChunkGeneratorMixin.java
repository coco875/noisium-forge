package io.github.steveplays28.noisium.mixin;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.Util;

import org.spongepowered.asm.mixin.*;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin extends ChunkGenerator {
	@Shadow
	@Final
	public Holder<NoiseGeneratorSettings> settings;
	
	public NoiseChunkGeneratorMixin(Registry<StructureSet> p_207960_, Optional<HolderSet<StructureSet>> p_207961_,
			BiomeSource p_207962_) {
		super(p_207960_, p_207961_, p_207962_);
		//TODO Auto-generated constructor stub
	}

	// public NoiseChunkGeneratorMixin(BiomeSource biomeSource) {
	// 	super(biomeSource);
	// }

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
		final var mutableBlockPos = new BlockPos.MutableBlockPos();

		for (int baseHorizontalWidthCellIndex = 0; baseHorizontalWidthCellIndex < horizontalCellCount; ++baseHorizontalWidthCellIndex) {
			chunkNoiseSampler.advanceCellX(baseHorizontalWidthCellIndex);

			for (int baseHorizontalLengthCellIndex = 0; baseHorizontalLengthCellIndex < horizontalCellCount; ++baseHorizontalLengthCellIndex) {
				var nextChunkSectionIndex = chunk.getSectionsCount() - 1;
				var chunkSection = chunk.getSection(nextChunkSectionIndex);

				for (int verticalCellHeightIndex = cellHeight - 1; verticalCellHeightIndex >= 0; --verticalCellHeightIndex) {
					chunkNoiseSampler.selectCellYZ(verticalCellHeightIndex, baseHorizontalLengthCellIndex);

					for (int verticalCellBlockIndex = verticalCellBlockCount - 1; verticalCellBlockIndex >= 0; --verticalCellBlockIndex) {
						int blockPosY = (minimumCellY + verticalCellHeightIndex) * verticalCellBlockCount + verticalCellBlockIndex;
						int chunkSectionBlockPosY = blockPosY & 0xF;
						int chunkSectionIndex = chunk.getSectionIndex(blockPosY);

						if (nextChunkSectionIndex != chunkSectionIndex) {
							nextChunkSectionIndex = chunkSectionIndex;
							chunkSection = chunk.getSection(chunkSectionIndex);
						}

						double deltaY = (double) verticalCellBlockIndex / verticalCellBlockCount;
						chunkNoiseSampler.updateForY(blockPosY, deltaY);

						for (int horizontalWidthCellBlockIndex = 0; horizontalWidthCellBlockIndex < horizontalCellBlockCount; ++horizontalWidthCellBlockIndex) {
							int blockPosX = chunkPosStartX + baseHorizontalWidthCellIndex * horizontalCellBlockCount + horizontalWidthCellBlockIndex;
							int chunkSectionBlockPosX = blockPosX & 0xF;
							double deltaX = (double) horizontalWidthCellBlockIndex / horizontalCellBlockCount;

							chunkNoiseSampler.updateForX(blockPosX, deltaX);

							for (int horizontalLengthCellBlockIndex = 0; horizontalLengthCellBlockIndex < horizontalCellBlockCount; ++horizontalLengthCellBlockIndex) {
								int blockPosZ = chunkPosStartZ + baseHorizontalLengthCellIndex * horizontalCellBlockCount + horizontalLengthCellBlockIndex;
								int chunkSectionBlockPosZ = blockPosZ & 0xF;
								double deltaZ = (double) horizontalLengthCellBlockIndex / horizontalCellBlockCount;

								chunkNoiseSampler.updateForZ(blockPosZ, deltaZ);
								BlockState blockState = chunkNoiseSampler.getInterpolatedState();

								if (blockState == null) {
									blockState = ((NoiseBasedChunkGenerator) (Object) this).settings.value().defaultBlock();
								}

								if (blockState == NoiseBasedChunkGenerator.AIR || SharedConstants.debugVoidTerrain(chunk.getPos())) {
									continue;
								}

								// Update the non empty block count to avoid issues with MC's lighting engine and other systems not recognising the direct palette storage set
								// See ChunkSection#setBlockState
								chunkSection.nonEmptyBlockCount += 1;

								if (!blockState.getFluidState().isEmpty()) {
									chunkSection.tickingFluidCount += 1;
								}

								if (blockState.isRandomlyTicking()) {
									chunkSection.tickingBlockCount += 1;
								}

								// Set the blockstate in the palette storage directly to improve performance
								var blockStateId = chunkSection.states.data.palette.idFor(blockState);
								chunkSection.states.data.storage().set(
										chunkSection.states.strategy.getIndex(chunkSectionBlockPosX,
												chunkSectionBlockPosY, chunkSectionBlockPosZ
										), blockStateId);

								oceanFloorHeightMap.update(chunkSectionBlockPosX, blockPosY, chunkSectionBlockPosZ, blockState);
								worldSurfaceHeightMap.update(chunkSectionBlockPosX, blockPosY, chunkSectionBlockPosZ, blockState);

								if (!aquiferSampler.shouldScheduleFluidUpdate() || blockState.getFluidState().isEmpty()) {
									continue;
								}

								mutableBlockPos.set(blockPosX, blockPosY, blockPosZ);
								chunk.markPosForPostprocessing(mutableBlockPos);
							}
						}
					}
				}
			}

			chunkNoiseSampler.swapSlices();
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
	public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState noiseConfig, StructureManager structureAccessor, ChunkAccess chunk) {
		NoiseSettings generationShapeConfig = this.settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
		int minimumY = generationShapeConfig.minY();
		int generationShapeHeightFloorDiv = Math.floorDiv(generationShapeConfig.height(), generationShapeConfig.getCellHeight());

		if (generationShapeHeightFloorDiv <= 0) {
			return CompletableFuture.completedFuture(chunk);
		}

		int minimumYFloorDiv = Math.floorDiv(minimumY, generationShapeConfig.getCellHeight());
		int startingChunkSectionIndex = chunk.getSectionIndex(
				generationShapeHeightFloorDiv * generationShapeConfig.getCellHeight() - 1 + minimumY);
		int minimumYChunkSectionIndex = chunk.getSectionIndex(minimumY);
		ArrayList<LevelChunkSection> chunkSections = new ArrayList<>();

		for (int chunkSectionIndex = startingChunkSectionIndex; chunkSectionIndex >= minimumYChunkSectionIndex; --chunkSectionIndex) {
			LevelChunkSection chunkSection = chunk.getSection(chunkSectionIndex);

			chunkSection.acquire();
			chunkSections.add(chunkSection);
		}

		return CompletableFuture.supplyAsync(
				Util.wrapThreadWithTaskName("wgen_fill_noise",
						() -> this.doFill(blender, structureAccessor, noiseConfig, chunk, minimumYFloorDiv,
								generationShapeHeightFloorDiv
						)
				), Util.backgroundExecutor()).whenCompleteAsync((chunk2, throwable) -> {
			// Replace an enhanced for loop with a fori loop
			for (int i = 0; i < chunkSections.size(); i++) {
				chunkSections.get(i).release();
			}
		}, executor);
	}
}

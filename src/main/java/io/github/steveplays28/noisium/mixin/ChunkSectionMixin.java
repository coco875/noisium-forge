package io.github.steveplays28.noisium.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelChunkSection.class)
public class ChunkSectionMixin {
	@Unique
	private static final int sliceSize = 4;

	@Shadow
	private PalettedContainerRO<Holder<Biome>> biomes;

	/**
	 * @author Steveplays28
	 * @reason Axis order micro-optimisation
	 */
	@Overwrite
	public void fillBiomesFromNoise(BiomeResolver biomeSupplier, Climate.Sampler sampler, int x, int z) {
		PalettedContainer<Holder<Biome>> palettedContainer = this.biomes.recreate();
		int y = QuartPos.fromBlock(((LevelChunkSection) (Object) this).bottomBlockY());

		for (int posX = 0; posX < sliceSize; ++posX) {
			for (int posZ = 0; posZ < sliceSize; ++posZ) {
				for (int posY = 0; posY < sliceSize; ++posY) {
					palettedContainer.getAndSetUnchecked(posX, posY, posZ, biomeSupplier.getNoiseBiome(x + posX, y + posY, z + posZ, sampler));
				}
			}
		}

		this.biomes = palettedContainer;
	}
}

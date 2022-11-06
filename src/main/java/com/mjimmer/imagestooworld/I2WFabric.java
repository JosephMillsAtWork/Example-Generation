package com.mjimmer.imagestooworld;

import com.mjimmer.imagestooworld.world.level.levelgen.presets.I2WWorldPreset;
import com.mjimmer.imagestooworld.world.level.biome.I2WBiomeSource;
import com.mjimmer.imagestooworld.world.level.levelgen.I2WChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

public class I2WFabric implements ModInitializer {
	public static String MOD_NAME = "imagestooworld";
	public static String MOD_ID = "i2w";
	@Override
	public void onInitialize() {
		// register the custom preset
		I2WWorldPreset.register();

		// register the boime source
		Registry.register(
				Registry.BIOME_SOURCE, new ResourceLocation(I2WFabric.MOD_NAME, I2WFabric.MOD_ID), I2WBiomeSource.CODEC
		);

		// register the chunk generator
		Registry.register(
				Registry.CHUNK_GENERATOR, new ResourceLocation(I2WFabric.MOD_NAME, I2WFabric.MOD_ID), I2WChunkGenerator.CODEC
		);
	}
}

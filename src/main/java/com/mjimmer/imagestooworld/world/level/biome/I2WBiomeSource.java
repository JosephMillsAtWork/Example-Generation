package com.mjimmer.imagestooworld.world.level.biome;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.*;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.world.level.biome.*;
import com.mojang.serialization.Codec;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.level.levelgen.NoiseRouterData;

public class I2WBiomeSource extends BiomeSource {
    public static final Codec<I2WBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(RegistryOps.retrieveRegistry(Registry.BIOME_REGISTRY)
                    .forGetter(theEndBiomeSource -> null)).apply(instance, instance.stable(I2WBiomeSource::new)));

    public final Holder<Biome> biome;
    private final Registry<Biome> biomeRegistry;
    private static final List<ResourceKey<Biome>> SPAWN = Collections.singletonList(Biomes.PLAINS);

    private static Climate.ParameterList<Holder<Biome>> parameters = null;

    private static OverworldBiomeBuilder biomeBuilder = new OverworldBiomeBuilder();

    private static final List<ResourceKey<Biome>> BIOMES = ImmutableList.of(
            Biomes.THE_VOID                      ,
            Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS, Biomes.SNOWY_PLAINS,
            Biomes.ICE_SPIKES,
            Biomes.DESERT,
            Biomes.SWAMP, Biomes.MANGROVE_SWAMP,
            Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST, Biomes.DARK_FOREST,
            Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA,
            Biomes.TAIGA, Biomes.SNOWY_TAIGA,
            Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU,
            Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_GRAVELLY_HILLS, Biomes.WINDSWEPT_FOREST, Biomes.WINDSWEPT_SAVANNA,
            Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE,
            Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS,
            Biomes.MEADOW, Biomes.GROVE,
            Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.STONY_PEAKS,
            Biomes.RIVER, Biomes.FROZEN_RIVER,
            Biomes.BEACH, Biomes.SNOWY_BEACH, Biomes.STONY_SHORE,
            Biomes.WARM_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN, Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.COLD_OCEAN,
            Biomes.DEEP_COLD_OCEAN, Biomes.FROZEN_OCEAN, Biomes.DEEP_FROZEN_OCEAN,
            Biomes.MUSHROOM_FIELDS,
            Biomes.DRIPSTONE_CAVES, Biomes.LUSH_CAVES,
            Biomes.DEEP_DARK,
            Biomes.NETHER_WASTES, Biomes.WARPED_FOREST, Biomes.CRIMSON_FOREST, Biomes.SOUL_SAND_VALLEY, Biomes.BASALT_DELTAS,
            Biomes.THE_END, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS
    );

    public I2WBiomeSource(Registry<Biome> biomeRegistry) {
        super(getStartBiomes(biomeRegistry));
        this.biomeRegistry = biomeRegistry;
        biome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);

        ImmutableList.Builder builder = ImmutableList.builder();
        biomeBuilder.addBiomes(pair -> builder.add( pair.mapSecond( biomeRegistry::getOrCreateHolderOrThrow ) ) );
        this.parameters = new Climate.ParameterList(builder.build());

    }

    private static List<Holder<Biome>> getStartBiomes(Registry<Biome> registry) {
        return BIOMES.stream().map(
                s -> registry.getHolderOrThrow( ResourceKey.create(BuiltinRegistries.BIOME.key(), s.location() )
                )
        ).collect(Collectors.toList());
    }


    public  Registry<Biome> getBiomeRegistry() {
        return biomeRegistry;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int i, int j, int k, Climate.Sampler sampler) {
        return this.getNoiseBiome(sampler.sample(i, j, k));
    }

    @VisibleForDebug
    public Holder<Biome> getNoiseBiome(Climate.TargetPoint targetPoint) {
        // TargetPoint[temperature=3098, humidity=-471, continentalness=9954, erosion=4395, depth=-18135, weirdness=-5841]
        return this.parameters.findValue(targetPoint);
    }

    @Override
    public void addDebugInfo(List<String> list, BlockPos blockPos, Climate.Sampler sampler) {
        int i = QuartPos.fromBlock(blockPos.getX());
        int j = QuartPos.fromBlock(blockPos.getY());
        int k = QuartPos.fromBlock(blockPos.getZ());
        Climate.TargetPoint targetPoint = sampler.sample(i, j, k);
        float f = Climate.unquantizeCoord(targetPoint.continentalness());
        float g = Climate.unquantizeCoord(targetPoint.erosion());
        float h = Climate.unquantizeCoord(targetPoint.temperature());
        float l = Climate.unquantizeCoord(targetPoint.humidity());
        float m = Climate.unquantizeCoord(targetPoint.weirdness());
        double d = NoiseRouterData.peaksAndValleys(m);
        list.add("I2WBiome builder PV: " + this.biomeBuilder.getDebugStringForPeaksAndValleys(d)
                                + " C: " + this.biomeBuilder.getDebugStringForContinentalness(f)
                                + " E: " + this.biomeBuilder.getDebugStringForErosion(g)
                                + " T: " + this.biomeBuilder.getDebugStringForTemperature(h)
                                + " H: " + this.biomeBuilder.getDebugStringForHumidity(l)
    );
    }
}








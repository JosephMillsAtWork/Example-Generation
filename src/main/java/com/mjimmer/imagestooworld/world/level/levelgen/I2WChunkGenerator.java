package com.mjimmer.imagestooworld.world.level.levelgen;

import net.minecraft.*;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.DensityFunctions.BeardifierMarker;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.Nullable;

public class I2WChunkGenerator extends NoiseBasedChunkGenerator {
    public static final Codec<I2WChunkGenerator> CODEC = RecordCodecBuilder.create((instance) -> {
        return commonCodec(instance)
                .and(instance.group(
                        RegistryOps.retrieveRegistry(Registry.NOISE_REGISTRY).forGetter( (noiseBasedChunkGenerator) -> {
                            return noiseBasedChunkGenerator.noises;
                        }),
                        BiomeSource.CODEC.fieldOf("biome_source")
                                .forGetter( (noiseBasedChunkGenerator) -> {
                                    return noiseBasedChunkGenerator.biomeSource;
                                }),
                        NoiseGeneratorSettings.CODEC.fieldOf("settings")
                                .forGetter( (noiseBasedChunkGenerator) -> {
                                    return noiseBasedChunkGenerator.settings;
                                })
                        )
                ).apply(instance, instance.stable(I2WChunkGenerator::new));
    });
    private static final BlockState AIR;
    protected final BlockState defaultBlock;
    private final Registry<NormalNoise.NoiseParameters> noises;
    protected final Holder<NoiseGeneratorSettings> settings;
    private final Aquifer.FluidPicker globalFluidPicker;

    public I2WChunkGenerator(Registry<StructureSet> registry,
                             Registry<NormalNoise.NoiseParameters> registry2,
                             BiomeSource biomeSource,
                             Holder<NoiseGeneratorSettings> holder)
    {
        super(registry, registry2, biomeSource, holder);

        this.noises = registry2;
        this.settings = holder;
        NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
        this.defaultBlock = noiseGeneratorSettings.defaultBlock();

        Aquifer.FluidStatus fluidStatus = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sealevel = noiseGeneratorSettings.seaLevel();
        Aquifer.FluidStatus fluidStatus2 = new Aquifer.FluidStatus(sealevel, noiseGeneratorSettings.defaultFluid());
        // FIXME why is this here ? it is never used
        // Aquifer.FluidStatus fluidStatus3 = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        this.globalFluidPicker = (j, k, l) -> {
            return k < Math.min(-54, sealevel) ? fluidStatus : fluidStatus2;
        };
    }

    private BlockState debugPreliminarySurfaceLevel(NoiseChunk noiseChunk, int i, int j, int k, BlockState blockState) {
        return blockState;
    }

    @Override
    public int getGenDepth() {
        return this.settings.value().noiseSettings().height();
    }

    @Override
    public int getSeaLevel() {
        return this.settings.value().seaLevel();
    }

    @Override
    public int getMinY() {
        return this.settings.value().noiseSettings().minY();
    }

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState randomState, BlockPos blockPos) {
        DecimalFormat decimalFormat = new DecimalFormat("0.000");
        NoiseRouter noiseRouter = randomState.router();
        DensityFunction.SinglePointContext singlePointContext = new DensityFunction.SinglePointContext(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        double d = noiseRouter.ridges().compute(singlePointContext);
        String var10001 = decimalFormat.format(noiseRouter.temperature().compute(singlePointContext));
        list.add("NoiseRouter T: " + var10001 + " V: " + decimalFormat.format(noiseRouter.vegetation().compute(singlePointContext))
                + " C: " + decimalFormat.format(noiseRouter.continents().compute(singlePointContext))
                + " E: " + decimalFormat.format(noiseRouter.erosion().compute(singlePointContext))
                + " D: " + decimalFormat.format(noiseRouter.depth().compute(singlePointContext))
                + " W: " + decimalFormat.format(d) + " PV: "
                + decimalFormat.format((double) NoiseRouterData.peaksAndValleys((float) d))
                + " AS: " + decimalFormat.format(noiseRouter.initialDensityWithoutJaggedness().compute(singlePointContext))
                + " N: " + decimalFormat.format(noiseRouter.finalDensity().compute(singlePointContext)));
    }


    ///////////////////////////////
    // NOISE AND HEIGHTMAP
    ///////////////////////////////

    @Override
    public Holder<NoiseGeneratorSettings> generatorSettings() {
        return this.settings;
    }

    // start of noise per-chunk
    private NoiseChunk createNoiseChunk(ChunkAccess chunkAccess, StructureManager structureManager,
            Blender blender, RandomState randomState)
    {
        return NoiseChunk.forChunk(
                chunkAccess,
                randomState,
                Beardifier.forStructuresInChunk(structureManager, chunkAccess.getPos()),
                this.settings.value(),
                this.globalFluidPicker,
                blender
        );
    }

    @Override
    public boolean stable(ResourceKey<NoiseGeneratorSettings> resourceKey) {
        return this.settings.is(resourceKey);
    }

    @Override
    public int getBaseHeight(int i, int j, Heightmap.Types types, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        return this.iterateNoiseColumn(
                        levelHeightAccessor,
                        randomState,
                        i, // pos.x 
                        j, // pos.z
                        (MutableObject) null,
                        types.isOpaque()
                )
                .orElse(levelHeightAccessor.getMinBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int i, int j, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        MutableObject<NoiseColumn> mutableObject = new MutableObject();
        this.iterateNoiseColumn(levelHeightAccessor, randomState, i, j, mutableObject, (Predicate) null);
        return mutableObject.getValue();
    }


    private OptionalInt iterateNoiseColumn(LevelHeightAccessor levelHeightAccessor, RandomState randomState, int i, int j,
                                           @Nullable MutableObject<NoiseColumn> mutableObject, @Nullable Predicate<BlockState> predicate) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        int yBlkSize = noiseSettings.getCellHeight();
        int minY = noiseSettings.minY();
        int minYFloor = Mth.intFloorDiv(minY, yBlkSize);
        int zBlkSize = Mth.intFloorDiv(noiseSettings.height(), yBlkSize);
        if (zBlkSize > 0) {
            BlockState[] blockStates;
            if (mutableObject == null) {
                blockStates = null;
            } else {
                blockStates = new BlockState[noiseSettings.height()];
                mutableObject.setValue(new NoiseColumn(minY, blockStates));
            }

            int xBlkSize = noiseSettings.getCellWidth();

            int xPoint = Math.floorDiv(i, xBlkSize);
            int zPoint = Math.floorDiv(j, xBlkSize);

            int tmp_xPoint = Math.floorMod(i, xBlkSize);
            int tmp_zPoint = Math.floorMod(j, xBlkSize);

            int firstXPoint = xPoint * xBlkSize;
            int firstZPoint = zPoint * xBlkSize;

            double noiseX = (double) tmp_xPoint / (double) xBlkSize;
            double noiseZ = (double) tmp_zPoint / (double) xBlkSize;

            NoiseChunk noiseChunk = new NoiseChunk(
                    1,
                    randomState,
                    firstXPoint,
                    firstZPoint,
                    noiseSettings,
                    BeardifierMarker.INSTANCE,
                    this.settings.value(),
                    this.globalFluidPicker,
                    Blender.empty()
            );
            noiseChunk.initializeForFirstCellX();
            noiseChunk.advanceCellX(0);

            for (int currentZ = zBlkSize - 1; currentZ >= 0; --currentZ) {
                noiseChunk.selectCellYZ(currentZ, 0);

                for (int currentY = yBlkSize - 1; currentY >= 0; --currentY) {
                    int yblk = (minYFloor + currentZ) * yBlkSize + currentY;
                    double noiseY = (double) currentY / (double) yBlkSize;
                    noiseChunk.updateForY(yblk, noiseY);
                    noiseChunk.updateForX(i,    noiseX);
                    noiseChunk.updateForZ(j,    noiseZ);
                    BlockState blockState = noiseChunk.getInterpolatedState();
                    BlockState blockState2 = blockState == null ? this.defaultBlock : blockState;
                    if (blockStates != null) {
                        int y = currentZ * yBlkSize + currentY;
                        blockStates[y] = blockState2;
                    }

                    if (predicate != null && predicate.test(blockState2)) {
                        noiseChunk.stopInterpolation();
                        return OptionalInt.of(yblk + 1);
                    }
                }
            }

            noiseChunk.stopInterpolation();
        }
        return OptionalInt.empty();
    }


    ////////////////////////////
    // CAVES (HEIGHMAP)
    ////////////////////////
    @Override
    public void applyCarvers(WorldGenRegion worldGenRegion, long l, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunkAccess, GenerationStep.Carving carving) {
        BiomeManager biomeManager2 = biomeManager.withDifferentSource((ix, jx, kx) -> {
            return this.biomeSource.getNoiseBiome(ix, jx, kx, randomState.sampler());
        });
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        ChunkPos chunkPos = chunkAccess.getPos();
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk((chunkAccessx) -> {
            return this.createNoiseChunk(chunkAccessx, structureManager, Blender.of(worldGenRegion), randomState);
        });
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext carvingContext = new CarvingContext(
                this,
                worldGenRegion.registryAccess(),
                chunkAccess.getHeightAccessorForGeneration(),
                noiseChunk,
                randomState,
                this.settings.value().surfaceRule()
        );
        CarvingMask carvingMask = ((ProtoChunk) chunkAccess).getOrCreateCarvingMask(carving);

        for (int j = -8; j <= 8; ++j) {
            for (int k = -8; k <= 8; ++k) {
                ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + j, chunkPos.z + k);
                ChunkAccess chunkAccess2 = worldGenRegion.getChunk(chunkPos2.x, chunkPos2.z);
                BiomeGenerationSettings biomeGenerationSettings = chunkAccess2.carverBiome(() -> {
                    return this.getBiomeGenerationSettings(this.biomeSource.getNoiseBiome(QuartPos.fromBlock(chunkPos2.getMinBlockX()),
                            0, QuartPos.fromBlock(chunkPos2.getMinBlockZ()), randomState.sampler()));
                });
                Iterable<Holder<ConfiguredWorldCarver<?>>> iterable = biomeGenerationSettings.getCarvers(carving);
                int m = 0;

                for (Iterator var24 = iterable.iterator(); var24.hasNext(); ++m) {
                    Holder<ConfiguredWorldCarver<?>> holder = (Holder) var24.next();
                    ConfiguredWorldCarver<?> configuredWorldCarver = (ConfiguredWorldCarver) holder.value();
                    worldgenRandom.setLargeFeatureSeed(l + (long) m, chunkPos2.x, chunkPos2.z);
                    if (configuredWorldCarver.isStartChunk(worldgenRandom)) {
                        Objects.requireNonNull(biomeManager2);
                        configuredWorldCarver.carve(carvingContext, chunkAccess, biomeManager2::getBiome, worldgenRandom, aquifer, chunkPos2, carvingMask);
                    }
                }
            }
        }

    }

    ///////////////////////////////
    // SURFACE (HEIGHMAP)
    ///////////////////////////////
    @Override
    public void buildSurface(WorldGenRegion worldGenRegion, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess) {
        if (!SharedConstants.debugVoidTerrain(chunkAccess.getPos())) {
            WorldGenerationContext worldGenerationContext = new WorldGenerationContext(this, worldGenRegion);
            this.buildSurface(chunkAccess, worldGenerationContext, randomState, structureManager, worldGenRegion.getBiomeManager(),
                    worldGenRegion.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), Blender.of(worldGenRegion));
        }
    }

    @VisibleForTesting
    @Override
    public void buildSurface(ChunkAccess chunkAccess, WorldGenerationContext worldGenerationContext, RandomState randomState,
                             StructureManager structureManager, BiomeManager biomeManager, Registry<Biome> registry, Blender blender) {
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk((chunkAccessx) -> {
            return this.createNoiseChunk(chunkAccessx, structureManager, blender, randomState);
        });
        NoiseGeneratorSettings noiseGeneratorSettings = (NoiseGeneratorSettings) this.settings.value();
        randomState.surfaceSystem().buildSurface(randomState, biomeManager, registry, noiseGeneratorSettings.useLegacyRandomSource(),
                worldGenerationContext, chunkAccess, noiseChunk, noiseGeneratorSettings.surfaceRule());
    }


    ////////////////////////////////
    // FILL HEIGHTMAP
    ///////////////////////////////
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunkAccess) {
        NoiseSettings noiseSettings = ((NoiseGeneratorSettings) this.settings.value()).noiseSettings()
                .clampToHeightAccessor(chunkAccess.getHeightAccessorForGeneration());
        int i = noiseSettings.minY();
        int j = Mth.intFloorDiv(i, noiseSettings.getCellHeight());
        int k = Mth.intFloorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        if (k <= 0) {
            return CompletableFuture.completedFuture(chunkAccess);
        } else {
            int l = chunkAccess.getSectionIndex(k * noiseSettings.getCellHeight() - 1 + i);
            int m = chunkAccess.getSectionIndex(i);
            Set<LevelChunkSection> set = Sets.newHashSet();

            for (int n = l; n >= m; --n) {
                LevelChunkSection levelChunkSection = chunkAccess.getSection(n);
                levelChunkSection.acquire();
                set.add(levelChunkSection);
            }

            return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_fill_noise", () -> {
                return this.doFill(blender, structureManager, randomState, chunkAccess, j, k);
            }), Util.backgroundExecutor()).whenCompleteAsync((chunkAccessx, throwable) -> {
                Iterator var3 = set.iterator();

                while (var3.hasNext()) {
                    LevelChunkSection levelChunkSection = (LevelChunkSection) var3.next();
                    levelChunkSection.release();
                }

            }, executor);
        }
    }

    private ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess, int i, int j) {
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk((chunkAccessx) -> {
            return this.createNoiseChunk(chunkAccessx, structureManager, blender, randomState);
        });
        Heightmap heightmap = chunkAccess.getOrCreateHeightmapUnprimed(Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess.getOrCreateHeightmapUnprimed(Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess.getPos();
        int k = chunkPos.getMinBlockX();
        int l = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int m = noiseChunk.cellWidth();
        int n = noiseChunk.cellHeight();
        int o = 16 / m;
        int p = 16 / m;

        for (int q = 0; q < o; ++q) {
            noiseChunk.advanceCellX(q);

            for (int r = 0; r < p; ++r) {
                LevelChunkSection levelChunkSection = chunkAccess.getSection(chunkAccess.getSectionsCount() - 1);

                for (int s = j - 1; s >= 0; --s) {
                    noiseChunk.selectCellYZ(s, r);

                    for (int t = n - 1; t >= 0; --t) {
                        int u = (i + s) * n + t;
                        int v = u & 15;
                        int w = chunkAccess.getSectionIndex(u);
                        if (chunkAccess.getSectionIndex(levelChunkSection.bottomBlockY()) != w) {
                            levelChunkSection = chunkAccess.getSection(w);
                        }

                        double d = (double) t / (double) n;
                        noiseChunk.updateForY(u, d);

                        for (int x = 0; x < m; ++x) {
                            int y = k + q * m + x;
                            int z = y & 15;
                            double e = (double) x / (double) m;
                            noiseChunk.updateForX(y, e);

                            for (int aa = 0; aa < m; ++aa) {
                                int ab = l + r * m + aa;
                                int ac = ab & 15;
                                double f = (double) aa / (double) m;
                                noiseChunk.updateForZ(ab, f);
                                BlockState blockState = noiseChunk.getInterpolatedState();
                                if (blockState == null) {
                                    blockState = this.defaultBlock;
                                }

                                blockState = this.debugPreliminarySurfaceLevel(noiseChunk, y, u, ab, blockState);
                                if (blockState != AIR && !SharedConstants.debugVoidTerrain(chunkAccess.getPos())) {
                                    if (blockState.getLightEmission() != 0 && chunkAccess instanceof ProtoChunk) {
                                        mutableBlockPos.set(y, u, ab);
                                        ((ProtoChunk) chunkAccess).addLight(mutableBlockPos);
                                    }

                                    levelChunkSection.setBlockState(z, v, ac, blockState, false);
                                    heightmap.update(z, u, ac, blockState);
                                    heightmap2.update(z, u, ac, blockState);
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                                        mutableBlockPos.set(y, u, ab);
                                        chunkAccess.markPosForPostprocessing(mutableBlockPos);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            noiseChunk.swapSlices();
        }

        noiseChunk.stopInterpolation();
        return chunkAccess;
    }

    ///////////////////////////////
    // BIOMES see I2WBiomeSource
    ///////////////////////////////
    @Override
    public CompletableFuture<ChunkAccess> createBiomes(Registry<Biome> registry, Executor executor, RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunkAccess) {

        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("init_biomes", () -> {
            this.doCreateBiomes(blender, randomState, structureManager, chunkAccess);
            return chunkAccess;
        }), Util.backgroundExecutor());
    }

    private void doCreateBiomes(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunkAccess) {

        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk((chunkAccessx) -> {
            return this.createNoiseChunk(chunkAccessx, structureManager, blender, randomState);
        });
        BiomeResolver biomeResolver = BelowZeroRetrogen.getBiomeResolver(blender.getBiomeResolver(this.biomeSource), chunkAccess);
        chunkAccess.fillBiomesFromNoise(
                biomeResolver,
                noiseChunk.cachedClimateSampler(randomState.router(), this.settings.value().spawnTarget())
        );
    }

    // TODO move everything over to a class(I2WStructureManager) that is inhernt of StructureManager
    ///////////////////////////////
    // STRUCTURES
    ///////////////////////////////
    @Override
    protected void generatePositions(RandomState randomState) {
        Set<Holder<Biome>> set = this.biomeSource.possibleBiomes();
        this.possibleStructureSets().forEach( holder -> {
            StructurePlacement structurePlacement;
            StructureSet structureSet = holder.value();
            boolean vaildStructure = false;
            for (StructureSet.StructureSelectionEntry structureSelectionEntry : structureSet.structures()) {
                Structure structure2 = structureSelectionEntry.structure().value();
                // TODO
                // might want to override this as if we want odd structures in say the end.
                if (!structure2.biomes().stream().anyMatch(set::contains)) continue;

                this.placementsForStructure.computeIfAbsent( structure2, structure -> new ArrayList()).add(structureSet.placement());
                vaildStructure = true;
            }

            // generate strongholds or other strucutres that are in the RingsStructurePlacement
            if (vaildStructure && (structurePlacement = structureSet.placement()) instanceof ConcentricRingsStructurePlacement) {
                ConcentricRingsStructurePlacement concentricRingsStructurePlacement = (ConcentricRingsStructurePlacement)structurePlacement;
                this.ringPositions.put(
                        concentricRingsStructurePlacement,
                        this.generateRingPositions(holder, randomState, concentricRingsStructurePlacement)
                );
            }
        });
    }

    //    private CompletableFuture<List<ChunkPos>> generateRingPositions( Holder<StructureSet> holder, RandomState randomState, ConcentricRingsStructurePlacement concentricRingsStructurePlacement ) {
    //    }

    // (fabric mappings)setStructureStarts
    @Override
    public void createStructures(RegistryAccess registryAccess,
                                 RandomState randomState,
                                 StructureManager structureManager,
                                 ChunkAccess chunkAccess,
                                 StructureTemplateManager structureTemplateManager,
                                 long seed)
    {
        ChunkPos chunkPos = chunkAccess.getPos();
        SectionPos sectionPos = SectionPos.bottomOf(chunkAccess);
        this.possibleStructureSets().forEach(holder -> {
            StructurePlacement structurePlacement = holder.value().placement();
            List<StructureSet.StructureSelectionEntry> list = holder.value().structures();
            for (StructureSet.StructureSelectionEntry structureSelectionEntry : list) {
                StructureStart structureStart = structureManager.getStartForStructure(
                        sectionPos,
                        structureSelectionEntry.structure().value(),
                        chunkAccess
                );
                if (structureStart == null || !structureStart.isValid()) continue;
                return;
            }
            if (!structurePlacement.isStructureChunk(this, randomState, seed, chunkPos.x, chunkPos.z)) {
                return;
            }
            if (list.size() == 1) {
                this.tryGenerateStructure(
                        list.get(0),
                        structureManager,
                        registryAccess,
                        randomState,
                        structureTemplateManager,
                        seed,
                        chunkAccess,
                        chunkPos,
                        sectionPos);
                return;
            }
            ArrayList<StructureSet.StructureSelectionEntry> arrayList = new ArrayList<StructureSet.StructureSelectionEntry>(list.size());
            arrayList.addAll(list);
            WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(0L));
            worldgenRandom.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);
            int structureWeight = 0;
            for (StructureSet.StructureSelectionEntry structureSelectionEntry2 : arrayList) {
                structureWeight += structureSelectionEntry2.weight();
            }
            while (!arrayList.isEmpty()) {
                //  StructureSet.StructureSelectionEntry structureSelectionEntry3;
                int randInt = worldgenRandom.nextInt(structureWeight);
                int structureEntryCounter = 0;
                Iterator iterator = arrayList.iterator();
                while (iterator.hasNext() && (randInt -= ((StructureSet.StructureSelectionEntry)iterator.next()).weight()) >= 0) {
                    ++structureEntryCounter;
                }
                StructureSet.StructureSelectionEntry structureSelectionEntry4 = arrayList.get(structureEntryCounter);
                if ( this.tryGenerateStructure(
                        structureSelectionEntry4,
                        structureManager,
                        registryAccess,
                        randomState,
                        structureTemplateManager,
                        seed,
                        chunkAccess,
                        chunkPos,
                        sectionPos )
                ) {
                    return;
                }
                arrayList.remove(structureEntryCounter);
                structureWeight -= structureSelectionEntry4.weight();
            }
        });
    }

    // addStructureReferences (createReferenceOrCrash)
    @Override
    public void createReferences(WorldGenLevel worldGenLevel, StructureManager structureManager, ChunkAccess chunkAccess){
        int halfSize = 8;
        ChunkPos chunkPos = chunkAccess.getPos();
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        int chunkPosMinBlockX = chunkPos.getMinBlockX();
        int chunkPosMinBlockZ = chunkPos.getMinBlockZ();
        SectionPos sectionPos = SectionPos.bottomOf(chunkAccess);
        for (int n = chunkX - 8; n <= chunkX + 8; ++n) {
            for (int o = chunkZ - 8; o <= chunkZ + 8; ++o) {
                long p = ChunkPos.asLong(n, o);
                for (StructureStart structureStart : worldGenLevel.getChunk(n, o).getAllStarts().values()) {
                    try {
                        if (!structureStart.isValid() || !structureStart.getBoundingBox().intersects(
                                chunkPosMinBlockX,
                                chunkPosMinBlockZ,
                                chunkPosMinBlockX + 15,
                                chunkPosMinBlockZ + 15)) continue;
                        structureManager.addReferenceForStructure(sectionPos, structureStart.getStructure(), p, chunkAccess);
                        DebugPackets.sendStructurePacket(worldGenLevel, structureStart);
                    }
                    catch (Exception exception) {
                        CrashReport crashReport = CrashReport.forThrowable(exception, "Generating structure reference");
                        CrashReportCategory crashReportCategory = crashReport.addCategory("Structure");
                        Optional<Registry<Structure>> optional = (Optional<Registry<Structure>>)worldGenLevel.registryAccess().registry(Registry.STRUCTURE_REGISTRY);
                        crashReportCategory.setDetail("Id", () ->
                                optional.map( registry -> registry.getKey(structureStart.getStructure()).toString())
                                .orElse("UNKNOWN")
                        );
                        crashReportCategory.setDetail("Name", () ->
                                Registry.STRUCTURE_TYPES.getKey(structureStart.getStructure().type()).toString()
                        );
                        crashReportCategory.setDetail("Class", () ->
                                structureStart.getStructure().getClass().getCanonicalName()
                        );
                        throw new ReportedException(crashReport);
                    }
                }
            }
        }
    }



    ///////////////////////////////
    // MOBS
    ///////////////////////////////
    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        if (!((NoiseGeneratorSettings) this.settings.value()).disableMobGeneration()) {
            ChunkPos chunkPos = worldGenRegion.getCenter();
            Holder<Biome> holder = worldGenRegion.getBiome(chunkPos.getWorldPosition().atY(worldGenRegion.getMaxBuildHeight() - 1));
            WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
            worldgenRandom.setDecorationSeed(worldGenRegion.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
            NaturalSpawner.spawnMobsForChunkGeneration(worldGenRegion, holder, chunkPos, worldgenRandom);
        }
    }


    static {
        AIR = Blocks.AIR.defaultBlockState();
    }
}

package com.mjimmer.imagestooworld.mixin;

import com.mjimmer.imagestooworld.world.level.levelgen.I2WChunkGenerator;
import com.mojang.datafixers.DataFixer;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(ChunkMap.class)
public class MixinChunkMap {
    @Shadow
    private RandomState randomState;

    @Inject(at = @At("RETURN"), method = "<init>*")

    private void  ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess levelStorageAccess,
                           DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor executor,
                           BlockableEventLoop<Runnable> blockableEventLoop, LightChunkGetter lightChunkGetter,
                           ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener,
                           ChunkStatusUpdateListener chunkStatusUpdateListener, Supplier<DimensionDataStorage> supplier,
                           int i, boolean bl, CallbackInfo ci ) {
        if (chunkGenerator instanceof I2WChunkGenerator)
        {
//            ImagesTooWorld.LOGGER.info("Creating Anvil noise settings for map");
            this.randomState = RandomState.create(
                    ((I2WChunkGenerator)chunkGenerator).generatorSettings().value(),
                    serverLevel.registryAccess().registryOrThrow( Registry.NOISE_REGISTRY ), serverLevel.getSeed() );
        }
    }
}

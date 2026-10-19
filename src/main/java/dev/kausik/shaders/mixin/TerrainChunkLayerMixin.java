package dev.kausik.shaders.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps meshing, arena alignment, shader layouts, and indirect offsets on the same vertex stride.
 */
@Mixin(ChunkSectionLayer.class)
public abstract class TerrainChunkLayerMixin {
  @Inject(method = "pipeline", at = @At("RETURN"), cancellable = true)
  private void shaders$terrainPipeline(
      boolean multiDraw, CallbackInfoReturnable<RenderPipeline> callback) {
    if (TerrainShaderGeometry.isEnabled()) {
      callback.setReturnValue(TerrainShaderGeometry.pipeline(callback.getReturnValue()));
    }
  }

  @Inject(method = "vertexFormat", at = @At("HEAD"), cancellable = true)
  private void shaders$terrainFormat(CallbackInfoReturnable<VertexFormat> callback) {
    if (TerrainShaderGeometry.isEnabled())
      callback.setReturnValue(TerrainShaderGeometry.layout().format());
  }
}

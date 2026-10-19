package dev.kausik.metal;

import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.Optional;

/** Metallum port shim: forwards to Metallum's LEQUAL comparison sampler. */
public final class MetalGpuSampler {
  private MetalGpuSampler() {}

  public static Optional<GpuSampler> comparisonVariant(GpuSampler base) {
    return com.metallum.render.MetalGpuSampler.comparisonVariant(base);
  }
}

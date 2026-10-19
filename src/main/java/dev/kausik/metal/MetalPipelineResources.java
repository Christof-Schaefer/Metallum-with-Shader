package dev.kausik.metal;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import java.util.Optional;
import java.util.Set;

/** Metallum port shim: unknown backend, so callers keep all declared resources bound. */
public final class MetalPipelineResources {
  private MetalPipelineResources() {}

  public static Optional<Set<String>> activeResourceNames(CompiledRenderPipeline pipeline) {
    return Optional.empty();
  }
}

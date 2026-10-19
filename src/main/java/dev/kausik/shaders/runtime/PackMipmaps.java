package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Mip generation on the GPU, retaining raster filtering for irregular-size reductions. */
public final class PackMipmaps implements AutoCloseable {
  private static final String VERTEX =
      """
      #version 450
      layout(location=0) out vec2 uv;
      void main() {
        uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
      }
      """;
  private static final String FRAGMENT =
      """
      #version 450
      layout(location=0) in vec2 uv;
      layout(location=0) out vec4 color;
      uniform sampler2D Source;
      void main() { color = textureLod(Source, uv, 0.0); }
      """;
  private final GpuDevice device;
  private final boolean nativeGeneration;
  private final Map<GpuFormat, CompiledRenderPipeline> pipelines = new EnumMap<>(GpuFormat.class);
  private final GpuSampler sampler;
  private boolean closed;

  public PackMipmaps(GpuDevice device) {
    this(device, Boolean.getBoolean("minecraftShaders.nativeMipmaps"));
  }

  public PackMipmaps(GpuDevice device, boolean nativeGeneration) {
    this.device = device;
    this.nativeGeneration = nativeGeneration;
    sampler =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.of(0));
    try {
      // Compile at load time; texture generation never creates a pipeline during a frame.
      for (GpuFormat format :
          List.of(
              GpuFormat.R8_UNORM,
              GpuFormat.RG8_UNORM,
              GpuFormat.RGBA8_UNORM,
              GpuFormat.RGBA16_UNORM,
              GpuFormat.R16_FLOAT,
              GpuFormat.RG16_FLOAT,
              GpuFormat.RGBA16_FLOAT,
              GpuFormat.R32_FLOAT,
              GpuFormat.RG32_FLOAT,
              GpuFormat.RGBA32_FLOAT,
              GpuFormat.RG11B10_FLOAT,
              GpuFormat.RGB10A2_UNORM)) {
        var pipeline =
            RenderPipeline.builder()
                .withLocation(
                    Identifier.fromNamespaceAndPath(
                        "minecraft_shader_loader",
                        "mipmap/" + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader(
                    Identifier.fromNamespaceAndPath("minecraft_shader_loader", "mipmap"))
                .withFragmentShader(
                    Identifier.fromNamespaceAndPath("minecraft_shader_loader", "mipmap"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(
                    new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(
                    BindGroupLayout.builder()
                        .withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withCull(false)
                .build();
        ShaderSource source =
            new ShaderSource() {
              public String getShader(Identifier id, ShaderType type) {
                return type == ShaderType.VERTEX ? VERTEX : FRAGMENT;
              }

              public CachedIncludeSource getInclude(Identifier id) {
                return null;
              }

              public void close() {}
            };
        CompiledRenderPipeline compiled =
            device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        if (compiled == null)
          throw new IllegalStateException("Mipmap pipeline compilation failed for " + format);
        pipelines.put(format, compiled);
      }
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  public void generate(PackTexture texture) {
    if (closed) throw new IllegalStateException("Pack mipmap generator is closed");
    if (texture.texture.getMipLevels() <= 1) return;
    int firstRasterLevel = 1;
    // Metallum port: no native mipmap kernel; always use the raster path from level 1.
    CompiledRenderPipeline pipeline = pipelines.get(texture.texture.getFormat());
    if (pipeline == null)
      throw new UnsupportedOperationException("Mipmap format " + texture.texture.getFormat());
    var encoder = device.createCommandEncoder();
    for (int level = firstRasterLevel; level < texture.texture.getMipLevels(); level++) {
      int destination = level;
      // Views restrict reads and writes to distinct levels of the same allocation.
      try (var pass =
          encoder.createRenderPass(
              () -> "Shader-pack mip " + destination,
              texture.level(destination),
              Optional.of(new org.joml.Vector4f()))) {
        pass.setPipeline(pipeline);
        pass.setUniform("Source", texture.level(destination - 1), sampler);
        pass.draw(3, 1, 0, 0);
      }
    }
  }

  private static boolean nativeFormat(GpuFormat format) {
    return format == GpuFormat.RGBA8_UNORM
        || format == GpuFormat.RGBA16_FLOAT
        || format == GpuFormat.RG11B10_FLOAT;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    pipelines.values().forEach(CompiledRenderPipeline::close);
    pipelines.clear();
    sampler.close();
  }
}

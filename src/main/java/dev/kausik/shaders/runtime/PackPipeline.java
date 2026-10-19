package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalPipelineMath;
import dev.kausik.metal.MetalPipelineResources;
import dev.kausik.shaders.compile.ShaderCompatibilityCompiler;
import dev.kausik.shaders.compile.TranslatedProgram;
import dev.kausik.shaders.compile.UniformLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** One translated program specialized for a draw format and immutable pipeline state. */
public record PackPipeline(
    PackPrograms.Source source,
    TranslatedProgram translated,
    CompiledRenderPipeline compiled,
    boolean fullscreen,
    boolean shadow,
    boolean depth,
    int depthTarget,
    int renderStage,
    float alphaTestRef,
    String primarySampler,
    java.util.List<SamplerBinding> samplerBindings)
    implements AutoCloseable {

  public enum SamplerKind {
    COLOR,
    DEPTH,
    SHADOW_DEPTH,
    SHADOW_COLOR,
    NOISE,
    NORMAL,
    SPECULAR,
    MATERIAL,
    ALBEDO,
    LIGHTMAP,
    CUSTOM
  }

  public record SamplerBinding(
      String name, String shaderName, SamplerKind kind, int index, boolean comparison) {
    static SamplerBinding of(TranslatedProgram.Sampler sampler) {
      String name = sampler.name();
      SamplerKind kind;
      int index = 0;
      if (name.matches("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]")) {
        kind = SamplerKind.COLOR;
        try {
          index = dev.kausik.shaders.pack.ShaderDirectives.colorBufferIndex(name);
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      } else if (name.matches("depthtex[012]")) {
        kind = SamplerKind.DEPTH;
        index = name.charAt(8) - '0';
      } else if (name.matches("shadowtex[01]")
          || name.equals("shadow")
          || name.equals("watershadow")) {
        kind = SamplerKind.SHADOW_DEPTH;
        index = name.endsWith("1") ? 1 : 0;
      } else if (name.matches("shadowcolor[0-7]") || name.equals("shadowcolor")) {
        kind = SamplerKind.SHADOW_COLOR;
        index = name.equals("shadowcolor") ? 0 : name.charAt(11) - '0';
      } else
        kind =
            switch (name) {
              case "noisetex" -> SamplerKind.NOISE;
              case "normals" -> SamplerKind.NORMAL;
              case "specular" -> SamplerKind.SPECULAR;
              case "materialtex" -> SamplerKind.MATERIAL;
              case "texture", "gtexture", "tex" -> SamplerKind.ALBEDO;
              case "lightmap" -> SamplerKind.LIGHTMAP;
              default -> SamplerKind.CUSTOM;
            };
      return new SamplerBinding(
          name,
          ShaderCompatibilityCompiler.samplerShaderName(name),
          kind,
          index,
          sampler.comparison());
    }
  }

  public static PackPipeline compile(
      GpuDevice device,
      PackPrograms.Source source,
      TranslatedProgram translated,
      RenderPipeline original,
      PackRenderTargets targets,
      boolean finalPass,
      boolean shadow,
      int depthTarget,
      int renderStage,
      float alphaTestRef) {
    boolean fullscreen = original == null;
    // Depth-only utilities retain Safe math; ordinary pack color programs may opt in.
    boolean relaxedFragmentMath =
        source.relaxedFragmentMath() && !translated.drawBuffers().isEmpty();
    var builder =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "minecraft_shader_loader",
                    translated
                            .label()
                            .toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("[^a-z0-9/._-]", "_")
                        + (fullscreen ? "/screen" : "/" + original.getLocation().getPath())
                        + (relaxedFragmentMath ? "/math_relaxed_fragment" : "/math_safe")))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withPrimitiveTopology(
                fullscreen ? PrimitiveTopology.TRIANGLES : original.getPrimitiveTopology())
            .withCull(!fullscreen && original.isCull());
    Map<String, BindGroupLayout.UniformDescription> bindings = new LinkedHashMap<>();
    if (original != null) {
      for (var binding : BindGroupLayout.flattenUniforms(original.getBindGroupLayouts()))
        bindings.put(binding.name(), binding);
      for (int slot = 0; slot < original.getVertexFormatBindings().size(); slot++) {
        var format = original.getVertexFormatBindings().get(slot);
        if (format != null) builder.withVertexBinding(slot, format);
      }
      builder
          .withPolygonMode(original.getPolygonMode())
          .withPushConstantSize(original.pushConstantSize());
    }
    if (translated.uniforms().byteSize() > 0)
      bindings.put(
          UniformLayout.BLOCK_NAME,
          new BindGroupLayout.UniformDescription(
              UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER));
    for (var sampler : translated.samplers()) {
      String name = ShaderCompatibilityCompiler.samplerShaderName(sampler.name());
      bindings.put(
          name, new BindGroupLayout.UniformDescription(name, UniformType.COMBINED_IMAGE_SAMPLER));
    }
    builder.withBindGroupLayout(new BindGroupLayout(java.util.List.copyOf(bindings.values())));
    var blend =
        original == null
                || original.getColorTargetStates().isEmpty()
                || original.getColorTargetStates().getFirst() == null
            ? Optional.<BlendFunction>empty()
            : original.getColorTargetStates().getFirst().blendFunction();
    for (int slot = 0; slot < translated.drawBuffers().size(); slot++) {
      int target = translated.drawBuffers().get(slot);
      if (target < 0) builder.withUnusedColorTargetState(slot);
      else
        builder.withColorTargetState(
            slot,
            new ColorTargetState(
                fullscreen ? Optional.empty() : blend,
                finalPass
                    ? com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM
                    : shadow
                        ? targets.shadowColor(target).texture.getFormat()
                        : targets.format(target),
                ColorTargetState.WRITE_ALL));
    }
    boolean depth = original != null && original.getDepthStencilState() != null;
    if (depth) {
      DepthStencilState state = original.getDepthStencilState();
      CompareOp compare =
          switch (state.depthTest()) {
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            default -> state.depthTest();
          };
      builder.withDepthStencilState(
          new DepthStencilState(
              compare,
              state.writeDepth(),
              -state.depthBiasScaleFactor(),
              -state.depthBiasConstant()));
    }
    ShaderSource shaderSource =
        new ShaderSource() {
          @Override
          public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX
                ? translated.vertexSource()
                : translated.fragmentSource();
          }

          @Override
          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          @Override
          public void close() {}
        };
    RenderPipeline description = builder.build();
    CompiledRenderPipeline compiled;
    // Direct executor and joined compilation keep the exact-name backend hint on this thread.
    // Vertex compilation remains Safe; unsupported precision contracts fall back in the backend.
    try (var math =
        relaxedFragmentMath
            ? MetalPipelineMath.openRelaxedFragment(description.getLocation().toString())
            : null) {
      compiled =
          device.compilePipeline(description, shaderSource, Runnable::run).join().finishCompile();
    }
    if (compiled == null)
      throw new IllegalStateException("Pack pipeline compilation failed: " + translated.label());
    String primarySampler =
        original != null
                && original
                    .getShaders()
                    .get(ShaderType.VERTEX)
                    .getPath()
                    .equals("core/rendertype_end_portal")
            ? "Sampler1"
            : "Sampler0";
    // Strict frontend validation requires every declared binding, even when the compiled
    // shaders never read it. Unknown backends also retain all dependencies. Otherwise use the
    // backend's immutable activity facts to avoid preparing/binding unused pack textures.
    var activeResources =
        FrontendGpuDevice.STRICT_VALIDATION
            ? Optional.<java.util.Set<String>>empty()
            : MetalPipelineResources.activeResourceNames(compiled);
    var samplerBindings =
        translated.samplers().stream()
            .map(SamplerBinding::of)
            .filter(
                sampler ->
                    activeResources.isEmpty()
                        || activeResources.get().contains(sampler.shaderName()))
            .toList();
    return new PackPipeline(
        source,
        translated,
        compiled,
        fullscreen,
        shadow,
        depth,
        depthTarget,
        renderStage,
        alphaTestRef,
        primarySampler,
        samplerBindings);
  }

  @Override
  public void close() {
    compiled.close();
  }
}

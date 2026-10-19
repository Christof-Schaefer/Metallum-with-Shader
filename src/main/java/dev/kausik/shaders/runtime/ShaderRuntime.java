package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.kausik.metal.MetalPassHints;
import dev.kausik.shaders.compile.MatrixUniformSpecialization;
import dev.kausik.shaders.compile.MinecraftVertexAdapter;
import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4fc;
import org.slf4j.LoggerFactory;

/** Owns the pack graph for one game instance. All GPU methods run on the render thread. */
public final class ShaderRuntime implements AutoCloseable {
  private static final boolean DISCARD_FULLSCREEN_LOADS =
      Boolean.getBoolean("minecraftShaders.discardFullscreenLoads");
  private static final boolean SPARSE_PROJECTION =
      Boolean.getBoolean("minecraftShaders.sparseProjectionMatrices");
  private static final boolean KEEP_OPAQUE_ALPHA_TEST =
      Boolean.getBoolean("minecraftShaders.keepOpaqueAlphaTest");
  private static final float RENDER_SCALE =
      Math.clamp(Float.parseFloat(System.getProperty("minecraftShaders.renderScale", "1")), 0.25f, 1f);
  private static ShaderRuntime instance;
  private final ShaderPack pack;
  private final Map<String, String> options;
  private final ShaderProperties properties;
  private final MaterialPalette materialPalette;
  private final FrameUniforms uniforms = new FrameUniforms();
  private final ShaderFrameProfiler profiler = ShaderFrameProfiler.fromSystemProperties();
  private final Map<Variant, PackPipeline> geometry = new HashMap<>();
  private final Map<String, PackPipeline> screens = new HashMap<>();
  private final Map<PackPipeline, PackPipeline> sparseScreens = new IdentityHashMap<>();
  private long sparseProjectionPasses, sparseProjectionFallbacks;
  private final Map<PackPipeline, FullscreenOverwrite.Result> screenOverwrites =
      new IdentityHashMap<>();
  private final Map<UniformKey, GpuBufferSlice> frameBuffers = new HashMap<>();
  private PackPrograms programs;
  private PackRenderTargets targets;
  private PackTextures textures;
  private MaterialAtlas materialAtlas;
  private GpuTexture blockAtlas;
  private PackMipmaps mipmaps;
  private UnusedMipmaps unusedMipmaps;
  private long mipmapGenerations;
  private PackDepthMerge depthMerge;
  private String dimension;
  private ShaderRenderPass openPass;
  private boolean active, hand, shadow, raw, deferred;

  public ShaderRuntime(ShaderPack pack, Map<String, String> options, ShaderProperties properties)
      throws IOException {
    this.pack = pack;
    this.options = Map.copyOf(options);
    this.properties = properties;
    String materialPath = properties.get("materialPalette", "");
    materialPalette =
        materialPath.isEmpty() ? null : MaterialPalette.parse(pack.text(materialPath));
  }

  public static void install(ShaderRuntime runtime) {
    instance = runtime;
  }

  public static ShaderRuntime get() {
    return instance;
  }

  public static boolean isRendering() {
    return instance != null && instance.active;
  }

  public GpuDevice device() {
    return RenderSystem.getDevice();
  }

  public FrameUniforms uniforms() {
    return uniforms;
  }

  public PackPrograms programs() {
    return programs;
  }

  public ShaderProperties properties() {
    return properties;
  }

  public PackRenderTargets targets() {
    return targets;
  }

  public MaterialAtlas.Stats materialStats() {
    return materialAtlas == null ? new MaterialAtlas.Stats(0, 0, 0, 0, 0) : materialAtlas.stats();
  }

  public void beginFrame() {
    ShadowRenderer.beginFrame();
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level == null) return;
    // Refresh after resource reloads; a held block and terrain can share this same atlas.
    blockAtlas =
        minecraft
            .getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS)
            .getTextureView()
            .texture();
    var main = minecraft.gameRenderer.mainRenderTarget();
    // Pack targets may render below window resolution; final upsamples into the main target.
    int width = Math.max(1, Math.round(main.width * RENDER_SCALE));
    int height = Math.max(1, Math.round(main.height * RENDER_SCALE));
    String nextDimension = minecraft.level.dimension().identifier().toString();
    try {
      if (programs == null || !nextDimension.equals(dimension)) {
        disposeGraph();
        var shadowComparison = PackTextures.shadowComparison(device());
        programs = new PackPrograms(pack, options, nextDimension, shadowComparison);
        unusedMipmaps =
            Boolean.getBoolean("minecraftShaders.skipUnusedMipmaps")
                ? UnusedMipmaps.analyze(programs)
                : null;
        if (unusedMipmaps != null)
          LoggerFactory.getLogger("minecraft_shader_loader")
              .info("Proven unused mip generations: {}", unusedMipmaps.skipped());
        uniforms.setCustomUniforms(CustomUniforms.compile(programs.properties));
        textures = new PackTextures(device(), pack, programs.properties, shadowComparison);
        mipmaps = new PackMipmaps(device());
        depthMerge = new PackDepthMerge(device());
        dimension = nextDimension;
      }
      if (programs.readsSampler("materialtex")) {
        if (materialAtlas == null) {
          if (materialPalette == null)
            throw new IOException("materialtex requires a materialPalette declaration");
          materialAtlas = new MaterialAtlas(device(), materialPalette);
        }
        materialAtlas.refresh(
            (TextureAtlas) minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS),
            minecraft.getResourceManager());
      }
      if (targets == null || targets.width != width || targets.height != height) {
        // Pipeline state depends on attachment formats, not the size of their textures.
        frameBuffers.clear();
        if (targets != null) targets.close();
        Map<Integer, PackRenderTargets.BufferSpec> specs = programs.bufferSpecifications();
        targets =
            new PackRenderTargets(
                device(),
                specs,
                width,
                height,
                programs.globals.intConstant("shadowMapResolution", 1024));
        // Allocate before any native scene pass opens, never in the middle of a draw batch.
        for (int index : specs.keySet()) targets.color(index);
        targets.shadowColor(0);
        targets.shadowColor(1);
        for (String phase : new String[] {"setup", "begin", "prepare", "deferred", "composite"}) {
          for (var source : programs.sequence(phase)) screenPipeline(source);
        }
        if (programs.find("final") != null) screenPipeline(programs.find("final"));
        LoggerFactory.getLogger("minecraft_shader_loader")
            .info(
                "Pack {} initialized in {} at {}x{}; shadow comparisons: {}",
                pack.name(),
                nextDimension,
                width,
                height,
                textures.hardwareShadowComparison() ? "hardware" : "emulated");
      }
      if (profiler != null) profiler.beginFrame(device(), dimension, width, height);
      frameBuffers.clear();
      uniforms.beginFrame(minecraft, width, height, programs.globals);
      if (uniforms.historyReset())
        targets.resetHistory(
            minecraft.gameRenderer.gameRenderState()
                .levelRenderState
                .cameraRenderState
                .fogData
                .color);
      targets.beginFrame(
          minecraft.gameRenderer.gameRenderState()
              .levelRenderState
              .cameraRenderState
              .fogData
              .color);
      profile("targets.cleared");
      updateShadowMatrices();
      active = true;
      hand = false;
      shadow = false;
      deferred = false;
      execute("begin");
      PackPrograms.Source sky = programs.find("gbuffers_skybasic");
      if (sky != null)
        execute(
            screens.computeIfAbsent(
                "sky_background",
                ignored ->
                    PackPipeline.compile(
                        device(),
                        sky,
                        programs.geometry(sky, MinecraftVertexAdapter.skyBackground()),
                        null,
                        targets,
                        false,
                        false,
                        0,
                        PackEnvironment.stage("SKY"),
                        0)));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot initialize shader pack " + pack.name(), failure);
    }
  }

  private void updateShadowMatrices() throws IOException {
    ShadowRenderer.updateMatrices(this);
  }

  public boolean isShadow() {
    return shadow;
  }

  public boolean beginShadow() {
    if (!active || programs.find("shadow") == null) return false;
    suspend();
    profile("shadow.begin");
    shadow = true;
    frameBuffers.clear();
    return true;
  }

  public void endShadow() {
    suspend();
    profile("shadow.end");
    shadow = false;
    frameBuffers.clear();
    execute("prepare");
  }

  /** Per-frame uniform buffers snapshot the matrices; drop them after changing shadow matrices. */
  public void invalidateFrameUniforms() {
    frameBuffers.clear();
  }

  public void setProjection(Matrix4fc projection) {
    if (!active) return;
    uniforms.setProjection(projection);
    frameBuffers.clear();
  }

  public void beforeTranslucents() {
    if (!active || shadow || deferred) return;
    suspend();
    profile("world.opaque.end");
    // depthtex1 also receives opaque hand rendering; it must start from scene depth.
    targets.copyDepth(1);
    if (programs.readsSampler("depthtex2")) targets.copyDepth(2);
    profile("depth.snapshot.end");
    execute("deferred");
    profile("deferred.end");
    deferred = true;
  }

  public void beginHand() {
    if (!active) return;
    beforeTranslucents();
    suspend();
    profile("hand.begin");
    hand = true;
  }

  public void endFrame() {
    ShadowRenderer.discardSelection();
    if (!active) return;
    try {
      suspend();
      beforeTranslucents();
      if (hand) {
        profile("hand.end");
        if (programs.postReadsSampler("depthtex0")) depthMerge.merge(targets);
        profile("depth.hand_merge.end");
      }
      execute("composite");
      PackPrograms.Source finalProgram = programs.find("final");
      if (finalProgram == null)
        throw new UnsupportedOperationException("Pack requires a final output program");
      execute(screenPipeline(finalProgram));
      uniforms.endFrame();
      if (profiler != null) profiler.endFrame();
    } finally {
      if (profiler != null) profiler.abortFrame();
      active = false;
      hand = false;
      shadow = false;
      OriginalPipelines.prune();
    }
  }

  public void abortFrame() {
    ShadowRenderer.discardSelection();
    suspend();
    if (profiler != null) profiler.abortFrame();
    active = false;
    hand = false;
    shadow = false;
  }

  public boolean intercept(RenderPassDescriptor descriptor) {
    if (!active || raw || descriptor.colorAttachments().isEmpty()) return false;
    var first = descriptor.colorAttachments().getFirst();
    return first != null
        && first.textureView().texture()
            == Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
  }

  public void opened(ShaderRenderPass pass) {
    if (openPass != null) throw new IllegalStateException("Overlapping logical scene passes");
    openPass = pass;
  }

  public void closed(ShaderRenderPass pass) {
    if (openPass == pass) openPass = null;
  }

  public void suspend() {
    if (openPass != null) openPass.suspend();
  }

  public PackPipeline pipeline(RenderPipeline original) {
    boolean blockEntity =
        FeatureDrawContext.currentDrawTag().kind() == FeatureDrawContext.Kind.BLOCK_ENTITY;
    Variant key = new Variant(original, hand, shadow, blockEntity);
    return geometry.computeIfAbsent(
        key,
        variant -> {
          SceneProgram selection = SceneProgram.select(original, hand, shadow, blockEntity);
          var source = programs.resolve(selection.name());
          boolean cutout = original.getShaderDefines().values().containsKey("ALPHA_CUTOUT");
          float alpha =
              cutout
                  ? Float.parseFloat(original.getShaderDefines().values().get("ALPHA_CUTOUT"))
                  : 0.1f;
          var colorTargets = original.getColorTargetStates();
          boolean blended =
              !colorTargets.isEmpty()
                  && colorTargets.getFirst() != null
                  && colorTargets.getFirst().blendFunction().isPresent();
          // Vanilla never alpha-tests opaque, unblended pipelines. A host discard there disables
          // the GPU's hidden-surface removal, so every occluded fragment runs the pack shader.
          // OptiFine-era alphaTest.* properties target the shared cutout program, not SOLID.
          boolean opaque = !cutout && !blended && !KEEP_OPAQUE_ALPHA_TEST;
          dev.kausik.shaders.compile.TranslatedProgram translated;
          AlphaTestPolicy alphaTest;
          try {
            boolean waterMask = MinecraftVertexAdapter.isWaterMask(original);
            alphaTest =
                waterMask || opaque
                    ? AlphaTestPolicy.OFF
                    : AlphaTestPolicy.parse(
                        programs.properties.get("alphaTest." + source.name(), ""),
                        AlphaTestPolicy.greater(alpha));
            translated =
                programs.geometry(
                    source, MinecraftVertexAdapter.adapter(original, shadow, hand), alphaTest);
            if (waterMask) translated = MinecraftVertexAdapter.waterMaskProgram(translated);
            translated =
                dev.kausik.shaders.compile.MinecraftTextureAdapter.adapt(original, translated);
          } catch (IOException failure) {
            throw new UncheckedIOException("Invalid alpha test for " + source.name(), failure);
          } catch (RuntimeException failure) {
            throw new IllegalStateException(
                "Cannot adapt Minecraft pipeline "
                    + original.getLocation()
                    + " to pack program "
                    + source.name(),
                failure);
          }
          int depthTarget = hand && !selection.name().equals("gbuffers_hand_water") ? 1 : 0;
          return PackPipeline.compile(
              device(),
              source,
              translated,
              original,
              targets,
              false,
              shadow,
              depthTarget,
              selection.stage(),
              alphaTest.reference());
        });
  }

  private PackPipeline screenPipeline(PackPrograms.Source source) {
    return screens.computeIfAbsent(
        source.name(),
        name -> {
          PackPipeline dense =
              PackPipeline.compile(
                  device(),
                  source,
                  source.translated(),
                  null,
                  targets,
                  name.equals("final"),
                  false,
                  0,
                  0,
                  0);
          if (SPARSE_PROJECTION) {
            var candidate =
                MatrixUniformSpecialization.apply(
                    source.translated(), MatrixUniformSpecialization.PROJECTION_INVERSE);
            if (candidate.applied()) {
              try {
                sparseScreens.put(
                    dense,
                    PackPipeline.compile(
                        device(),
                        source,
                        candidate.program(),
                        null,
                        targets,
                        name.equals("final"),
                        false,
                        0,
                        0,
                        0));
              } catch (RuntimeException failure) {
                LoggerFactory.getLogger("minecraft_shader_loader")
                    .warn(
                        "Sparse projection variant {} unavailable; retaining dense shader",
                        name,
                        failure);
              }
            }
          }
          return dense;
        });
  }

  public long sparseProjectionPasses() {
    return sparseProjectionPasses;
  }

  public long sparseProjectionFallbacks() {
    return sparseProjectionFallbacks;
  }

  public long mipmapGenerations() {
    return mipmapGenerations;
  }

  public void prepare(PackPipeline pipeline) {
    try {
      var requested = pipeline.source().directives().mipmapBuffers();
      boolean generated = false;
      for (int index : requested) {
        if (pipeline.fullscreen()
            && unusedMipmaps != null
            && unusedMipmaps.skips(pipeline.source().name(), index)) continue;
        PackTexture texture = targets.color(index);
        targets.ensureDefined(texture);
        if (texture.texture.getMipLevels() <= 1) continue;
        mipmaps.generate(texture);
        mipmapGenerations++;
        generated = true;
      }
      if (generated) profile("mipmaps." + pipeline.source().name() + ".end");
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  public boolean requiresReadPreparation(PackPipeline pipeline) {
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      boolean needed =
          switch (sampler.kind()) {
            case COLOR -> targets.needsColorDefinition(sampler.index());
            case DEPTH -> targets.needsDepthDefinition(sampler.index());
            case SHADOW_COLOR -> targets.needsShadowColorDefinition(sampler.index());
            case SHADOW_DEPTH -> targets.needsShadowDepthDefinition(sampler.index());
            default -> false;
          };
      if (needed) return true;
    }
    return false;
  }

  public boolean requiresMipGeneration(PackPipeline pipeline) {
    try {
      return !pipeline.source().directives().mipmapBuffers().isEmpty();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  public RenderPass open(PackPipeline pipeline) {
    return open(pipeline, 0);
  }

  private RenderPass open(PackPipeline pipeline, int discardMask) {
    // Reads must be defined before opening an encoder; a later binding cannot issue a clear.
    for (var sampler : pipeline.samplerBindings()) {
      if (textures.custom(pipeline.source().name(), sampler.name()) != null) continue;
      PackTexture target =
          switch (sampler.kind()) {
            case COLOR -> targets.color(sampler.index());
            case DEPTH -> targets.depth(sampler.index());
            case SHADOW_COLOR -> targets.shadowColor(sampler.index());
            case SHADOW_DEPTH -> targets.shadowDepth(sampler.index());
            default -> null;
          };
      if (target != null) targets.ensureDefined(target);
    }
    RenderPassDescriptor descriptor =
        pipeline.source().name().equals("final")
            ? RenderPassDescriptor.builder(() -> "Shader pack final")
                .withColorAttachment(
                    Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView())
                .build()
            : targets.descriptor(
                "Shader pack " + pipeline.source().name(),
                pipeline.translated().drawBuffers(),
                pipeline.fullscreen(),
                pipeline.shadow(),
                pipeline.depth(),
                pipeline.depthTarget());
    raw = true;
    try {
      if (discardMask != 0) {
        try (var ignored = MetalPassHints.openDiscardColors(descriptor, discardMask)) {
          return device().createCommandEncoder().createRenderPass(descriptor);
        }
      }
      return device().createCommandEncoder().createRenderPass(descriptor);
    } finally {
      raw = false;
    }
  }

  public void bind(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    bindUniforms(pipeline, pass);
    bindTextures(pipeline, pass, supplied);
  }

  public void bindUniforms(PackPipeline pipeline, RenderPass pass) {
    if (pipeline.translated().uniforms().byteSize() > 0) {
      var tag =
          pipeline.fullscreen() ? FeatureDrawContext.NONE : FeatureDrawContext.currentDrawTag();
      GpuBufferSlice buffer =
          frameBuffers.computeIfAbsent(
              new UniformKey(pipeline, tag),
              key -> {
                uniforms.setDrawState(
                    pipeline.renderStage(),
                    pipeline.alphaTestRef(),
                    tag.entityId(),
                    tag.blockEntityId());
                uniforms.setEntityColor(tag.red(), tag.green(), tag.blue(), tag.alpha());
                try (var allocation =
                    device()
                        .createCommandEncoder()
                        .transientMemory()
                        .allocateGpuMapped(
                            pipeline.translated().uniforms().byteSize(),
                            256,
                            GpuBuffer.USAGE_UNIFORM)) {
                  uniforms.write(
                      pipeline.translated().uniforms(),
                      allocation.data(),
                      pipeline.fullscreen(),
                      pipeline.shadow());
                  return allocation.slice();
                }
              });
      pass.setUniform(UniformLayout.BLOCK_NAME, buffer);
    }
  }

  public void bindTextures(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    for (var sampler : pipeline.samplerBindings()) bindTexture(pipeline, pass, supplied, sampler);
  }

  /** Only aliases of a changed vanilla material texture need rebinding within a scene pass. */
  public void bindSuppliedTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      String changed) {
    for (var sampler : pipeline.samplerBindings()) {
      boolean affected =
          (sampler.kind() == PackPipeline.SamplerKind.ALBEDO
                      || sampler.kind() == PackPipeline.SamplerKind.MATERIAL)
                  && changed.equals(pipeline.primarySampler())
              || sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP && changed.equals("Sampler2");
      if (affected && textures.custom(pipeline.source().name(), sampler.name()) == null)
        bindTexture(pipeline, pass, supplied, sampler);
    }
  }

  private void bindTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      PackPipeline.SamplerBinding sampler) {
    PackTexture texture = textures.custom(pipeline.source().name(), sampler.name());
    GpuSampler filter = textures.linear;
    GpuTextureView view = null;
    if (texture == null) {
      switch (sampler.kind()) {
        case COLOR -> texture = targets.color(sampler.index());
        case DEPTH -> {
          texture = targets.depth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_DEPTH -> {
          texture = targets.shadowDepth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_COLOR -> texture = targets.shadowColor(sampler.index());
        case NOISE -> {
          texture = textures.noise;
          filter = textures.repeat;
        }
        case NORMAL -> texture = textures.normal;
        case SPECULAR -> texture = textures.black;
        case MATERIAL -> {
          if (materialAtlas == null) throw new IllegalStateException("Material atlas not prepared");
          var original = supplied.get(pipeline.primarySampler());
          texture =
              materialAtlas.forAlbedo(
                  original == null || original.texture() == null
                      ? null
                      : original.texture().texture());
          filter = materialAtlas.sampler;
        }
        case ALBEDO, LIGHTMAP -> {
          var original =
              supplied.get(
                  sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP
                      ? "Sampler2"
                      : pipeline.primarySampler());
          if (original != null && original.texture() != null) {
            view = original.texture();
            filter = original.sampler();
            if (sampler.kind() == PackPipeline.SamplerKind.ALBEDO)
              filter = textures.albedoSampler(view, blockAtlas, filter);
          } else texture = textures.white;
        }
        case CUSTOM ->
            throw new UnsupportedOperationException(
                "Unbound pack sampler " + sampler.name() + " in " + pipeline.source().name());
      }
    }
    if (view == null) view = texture.sampled;
    if (sampler.comparison()) {
      if (textures.hardwareShadowComparison()
          && view.texture().getFormat() != com.mojang.renderpearl.api.GpuFormat.D32_FLOAT)
        throw new UnsupportedOperationException(
            "Hardware shadow comparison requires a depth texture: " + sampler.name());
      filter = textures.comparisonSampler();
    }
    pass.setUniform(sampler.shaderName(), view, filter);
  }

  private void execute(String phase) {
    for (var source : programs.sequence(phase)) execute(screenPipeline(source));
  }

  private void execute(PackPipeline pipeline) {
    PackPipeline sparse = sparseScreens.get(pipeline);
    if (sparse != null) {
      if (uniforms.matchesMatrix(MatrixUniformSpecialization.PROJECTION_INVERSE)) {
        pipeline = sparse;
        sparseProjectionPasses++;
      } else sparseProjectionFallbacks++;
    }
    profile("pass." + pipeline.source().name() + ".begin");
    prepare(pipeline);
    try (RenderPass pass = open(pipeline, fullscreenDiscardMask(pipeline))) {
      pass.setPipeline(pipeline.compiled());
      bind(pipeline, pass, Map.of());
      pass.draw(3, 1, 0, 0);
    }
    if (!pipeline.source().name().equals("final"))
      targets.flip(pipeline.translated().drawBuffers());
    profile("pass." + pipeline.source().name() + ".end");
  }

  private int fullscreenDiscardMask(PackPipeline pipeline) {
    if (!DISCARD_FULLSCREEN_LOADS
        || !pipeline.fullscreen()
        || pipeline.shadow()
        || pipeline.depth()
        || !device()
            .getDeviceInfo()
            .underlyingExtensions()
            .contains(MetalPassHints.DISCARD_COLOR_CAPABILITY)) return 0;
    // Only execute() calls this: PackPipeline.compile(original=null) owns triangles, no depth,
    // blending or culling and WRITE_ALL; execute owns draw(3), full viewport and no scissor.
    // Backend validation additionally checks mip/layer/area bounds and explicit clears.
    return screenOverwrites
        .computeIfAbsent(
            pipeline,
            candidate -> {
              var result = FullscreenOverwrite.analyze(candidate.translated());
              LoggerFactory.getLogger("minecraft_shader_loader")
                  .info(
                      "Fullscreen load discard {}: mask={}, {}",
                      candidate.source().name(),
                      result.colorMask(),
                      result.reason());
              return result;
            })
        .colorMask();
  }

  private void profile(String boundary) {
    if (profiler != null) profiler.mark(boundary);
  }

  private void disposePipelines() {
    geometry.values().forEach(PackPipeline::close);
    geometry.clear();
    screens.values().forEach(PackPipeline::close);
    screens.clear();
    sparseScreens.values().forEach(PackPipeline::close);
    sparseScreens.clear();
    screenOverwrites.clear();
    frameBuffers.clear();
  }

  private void disposeGraph() {
    ShadowRenderer.discardSelection();
    unusedMipmaps = null;
    disposePipelines();
    if (targets != null) {
      targets.close();
      targets = null;
    }
    if (textures != null) {
      textures.close();
      textures = null;
    }
    if (mipmaps != null) {
      mipmaps.close();
      mipmaps = null;
    }
    if (depthMerge != null) {
      depthMerge.close();
      depthMerge = null;
    }
    if (programs != null) {
      programs.close();
      programs = null;
    }
  }

  @Override
  public void close() {
    ShadowRenderer.discardSelection();
    active = false;
    suspend();
    disposeGraph();
    if (materialAtlas != null) {
      materialAtlas.close();
      materialAtlas = null;
    }
    OriginalPipelines.clear();
    if (profiler != null) profiler.close();
  }

  private record Variant(
      RenderPipeline original, boolean hand, boolean shadow, boolean blockEntity) {}

  private record UniformKey(PackPipeline pipeline, FeatureDrawContext.DrawTag tag) {
    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(pipeline) + tag.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof UniformKey key && pipeline == key.pipeline && tag.equals(key.tag);
    }
  }
}

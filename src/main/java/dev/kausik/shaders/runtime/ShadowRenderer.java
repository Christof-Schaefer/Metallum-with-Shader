package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.kausik.scene.SceneExtractionRequest;
import dev.kausik.scene.SceneFrustumVolume;
import dev.kausik.scene.SceneGeneration;
import dev.kausik.scene.SceneSelection;
import dev.kausik.scene.SceneSelectionTicket;
import dev.kausik.scene.SceneViewRequest;
import dev.kausik.scene.SceneViews;
import dev.kausik.scene.SceneVolume;
import dev.kausik.shaders.pack.ShaderPackException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.EnumMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.LoggerFactory;

/** Reuses uploaded vanilla chunk meshes and prepared features from the light's point of view. */
public final class ShadowRenderer {
  private static final EnumMap<
          SceneExtractionRequest.Purpose, ObjectArrayList<SectionRenderDispatcher.RenderSection>>
      extractionCache = new EnumMap<>(SceneExtractionRequest.Purpose.class);
  private static LevelRenderer extractionRenderer;
  private static final boolean RECEIVER_CULLING =
      Boolean.getBoolean("minecraftShaders.shadowReceiverCulling");
  private static final boolean TIGHT_RECEIVER_PADDING =
      Boolean.getBoolean("minecraftShaders.tightShadowReceiverPadding");
  private static final boolean ASYNC_SELECTION =
      Boolean.getBoolean("minecraftScene.asyncSelection");
  // Audited BSL defaults: <13.895 blocks of filter reach, <=6 blocks of raster interpolation
  // displacement for section-bounded triangles, <1 block waving, and <.128 reverse-depth bias.
  // The 32-block diagnostic retains numeric slack; 64 remains the default. See the source audit.
  private static final double RECEIVER_PADDING = 64;
  private static CullingStats lastCullingStats =
      new CullingStats(false, false, 0, 0, 0, 0, RECEIVER_PADDING, "not-rendered");
  private static long cullingFrames;
  private static PendingSelection pendingSelection;
  private static long selectionFrame, frameCaptureNanos;
  private static long overlapRequested, overlapSubmitted, overlapUsed, overlapStale;
  private static long overlapCaptureNanos, overlapConsumeNanos;
  private static final boolean SHADOW_CACHE = Boolean.getBoolean("minecraftShaders.shadowCache");
  private static final float CACHE_ANGLE =
      (float)
          Math.toRadians(
              Double.parseDouble(System.getProperty("minecraftShaders.shadowCacheAngle", "0.25")));
  private static final int CACHE_MAX_AGE =
      Integer.getInteger("minecraftShaders.shadowCacheMaxAge", 120);
  private static final int CACHE_SLICES =
      Math.max(1, Integer.getInteger("minecraftShaders.shadowCacheSlices", 8));
  // Front: the complete map frames sample. Back: rebuilt a slice per frame, then swapped in.
  private static CacheKey frameKey, frontKey, backKey;
  private static long frontFingerprint, backFingerprint;
  private static PackRenderTargets frontTargets;
  private static int frontSlot, frontAge, slice;
  private static boolean building;

  /** World-space placement of a shadow map: light direction plus the anchored grid origin. */
  private record CacheKey(Vector3f direction, double x, double y, double z, float distance) {}

  private static void applyKey(ShaderRuntime runtime, CacheKey key, Vec3 camera) {
    Matrix4f view = lightView(key.direction(), key.distance());
    view.translate(
        (float) (camera.x - key.x()), (float) (camera.y - key.y()), (float) (camera.z - key.z()));
    float d = key.distance();
    runtime.uniforms().setShadowMatrices(new Matrix4f().ortho(-d, d, -d, d, 0.05f, d * 4), view);
    runtime.invalidateFrameUniforms();
  }

  private static Vec3 cameraPos() {
    return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        .pos;
  }

  // Diagnostic: which trigger starts rebuilds (xz, y, distance, angle, chunks, age); logged when profiling.
  private static final int[] refreshReasons = new int[6];
  private static int refreshChecks;

  private static boolean needsRefresh(long fingerprint) {
    boolean[] r = {
      frameKey.x() != frontKey.x() || frameKey.z() != frontKey.z(),
      frameKey.y() != frontKey.y(),
      frameKey.distance() != frontKey.distance(),
      frameKey.direction().angle(frontKey.direction()) > CACHE_ANGLE,
      fingerprint != frontFingerprint,
      frontAge >= CACHE_MAX_AGE
    };
    boolean any = false;
    for (int i = 0; i < r.length; i++) if (r[i]) { refreshReasons[i]++; any = true; }
    if (++refreshChecks % 600 == 0 && Boolean.getBoolean("minecraftShaders.profiler.enabled")) {
      LoggerFactory.getLogger("minecraft_shader_loader")
          .info("Shadow cache refresh triggers per 600 checks [xz, y, distance, angle, chunks, age]: {}",
              java.util.Arrays.toString(refreshReasons));
      java.util.Arrays.fill(refreshReasons, 0);
    }
    return any;
  }

  /** Spatially interleaved, so every slice costs about the same. */
  static boolean inSlice(SectionRenderDispatcher.RenderSection section, int index, int slices) {
    long node = section.getSectionNode();
    int mix =
        SectionPos.x(node) * 3 + SectionPos.y(node) * 5 + SectionPos.z(node) * 7;
    return Math.floorMod(mix, slices) == index;
  }

  /** Order-independent: changes when the set of shadow sections or any of their meshes changes. */
  static long fingerprint(java.util.List<SectionRenderDispatcher.RenderSection> sections) {
    long sum = 0;
    for (var section : sections) {
      long h = System.identityHashCode(section) * 0x9E3779B97F4A7C15L;
      h ^= System.identityHashCode(section.getSectionMesh()) * 0xC2B2AE3D27D4EB4FL;
      sum += h ^ (h >>> 29);
    }
    return sum ^ sections.size();
  }

  private ShadowRenderer() {}

  /**
   * Counts refer to compiled terrain inside the original light frustum. selectionCpuMs is elapsed
   * main-thread work, not thread CPU time. Control retains its historical timer after light-frustum
   * construction; async mode includes capture (including frustum construction) plus consumption,
   * never worker latency or the time between prefetch and use.
   */
  public record CullingStats(
      boolean requested,
      boolean applied,
      int candidates,
      int submitted,
      int rejected,
      double selectionCpuMs,
      double receiverPadding,
      String eligibility) {}

  /** Used means a valid ticket reached the provider; its ready counter proves worker-result use. */
  public record OverlapStats(
      boolean enabled,
      long requested,
      long submitted,
      long used,
      long stale,
      long captureNanos,
      long consumeNanos) {}

  public static OverlapStats overlapStats() {
    return new OverlapStats(
        ASYNC_SELECTION,
        overlapRequested,
        overlapSubmitted,
        overlapUsed,
        overlapStale,
        overlapCaptureNanos,
        overlapConsumeNanos);
  }

  /** Called before any beginFrame early exit, including a world which has just been unloaded. */
  public static void beginFrame() {
    discardSelection();
    selectionFrame++;
    frameCaptureNanos = 0;
  }

  public static void discardSelection() {
    PendingSelection previous = pendingSelection;
    pendingSelection = null;
    if (previous != null && previous.ticket() != null) previous.ticket().close();
  }

  /** After camera recentering, immediately before prepareFrame can wait for its transform ring. */
  public static void prefetchSelection(LevelRenderer renderer, ViewArea area) {
    if (!ASYNC_SELECTION) return;
    discardSelection();
    ShaderRuntime runtime = ShaderRuntime.get();
    if (!ShaderRuntime.isRendering()
        || runtime == null
        || area == null
        || runtime.programs() == null
        || runtime.programs().find("shadow") == null) return;
    overlapRequested++;
    long started = System.nanoTime();
    try {
      SelectionInputs inputs = captureInputs(runtime);
      var eligibility = runtime.programs().shadowCullingEligibility;
      boolean receiverCulling = RECEIVER_CULLING && eligibility.eligible();
      double padding = receiverPadding(TIGHT_RECEIVER_PADDING, receiverCulling);
      ShadowCasterVolume receiver =
          receiverCulling
              ? ShadowCasterVolume.create(
                  inputs.projection, inputs.modelView, inputs.light, padding)
              : null;
      SceneGeneration generation = SceneViews.generation();
      var request =
          new SceneViewRequest(
              area,
              generation,
              "shadow-render",
              new SceneFrustumVolume(inputs.frustum(), 0),
              receiver == null ? null : new ReceiverRefinement(receiver, inputs.receiverOrigin),
              true);
      SceneSelectionTicket ticket = SceneViews.prefetch(request);
      if (ticket != null) overlapSubmitted++;
      pendingSelection =
          new PendingSelection(
              renderer,
              area,
              runtime,
              runtime.programs(),
              generation,
              selectionFrame,
              inputs,
              request,
              ticket,
              receiver != null && receiver.planeCount() > 0,
              padding,
              eligibility.reason());
    } finally {
      long elapsed = System.nanoTime() - started;
      frameCaptureNanos += elapsed;
      overlapCaptureNanos += elapsed;
    }
  }

  static double receiverPadding(boolean tightRequested, boolean eligible) {
    return tightRequested && eligible ? 32 : RECEIVER_PADDING;
  }

  public static CullingStats cullingStats() {
    return lastCullingStats;
  }

  public static void updateMatrices(ShaderRuntime runtime) throws ShaderPackException {
    float distance = runtime.programs().globals.floatConstant("shadowDistance", 160);
    if (!(distance > 0)) throw new ShaderPackException("Shadow distance must be positive");
    Vector3fc direction = runtime.uniforms().shadowDirectionWorld();
    Matrix4f view = lightView(direction, distance);
    float interval = runtime.programs().globals.floatConstant("shadowIntervalSize", 2);
    var camera = cameraPos();
    boolean texelSnap =
        runtime.programs().properties.get("shadow.texelSnap", "false").equals("true");
    // shortcut: texel-snapped maps move with sub-cell camera motion; no caching for them yet.
    frameKey = null;
    if (SHADOW_CACHE && !texelSnap) {
      frameKey =
          interval > 0
              ? new CacheKey(
                  new Vector3f(direction),
                  Math.floor(camera.x / interval) * interval,
                  Math.floor(camera.y / interval) * interval,
                  Math.floor(camera.z / interval) * interval,
                  distance)
              : new CacheKey(new Vector3f(direction), camera.x, camera.y, camera.z, distance);
      if (frontTargets != runtime.targets()) {
        frontKey = null;
        building = false;
        frontTargets = runtime.targets();
      }
      // Frames sample the front map, so the uniforms must describe its placement.
      applyKey(runtime, frontKey != null ? frontKey : frameKey, camera);
      return;
    }
    if (texelSnap) {
      snapToTexels(
          view,
          camera.x,
          camera.y,
          camera.z,
          distance,
          runtime.programs().globals.intConstant("shadowMapResolution", 1024));
    } else if (interval > 0) {
      // World-anchored sampling prevents every tiny camera movement from sliding the shadow grid.
      view.translate(
          (float) (camera.x - Math.floor(camera.x / interval) * interval),
          (float) (camera.y - Math.floor(camera.y / interval) * interval),
          (float) (camera.z - Math.floor(camera.z / interval) * interval));
    }
    Matrix4f projection =
        new Matrix4f().ortho(-distance, distance, -distance, distance, 0.05f, distance * 4);
    runtime.uniforms().setShadowMatrices(projection, view);
  }

  /** Never choose another light here: the uniform and the depth map must describe the same one. */
  static Matrix4f lightView(Vector3fc direction, float distance) {
    Vector3f up = Math.abs(direction.y()) > 0.99f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
    return new Matrix4f()
        .lookAt(
            direction.x() * distance * 2,
            direction.y() * distance * 2,
            direction.z() * distance * 2,
            0,
            0,
            0,
            up.x,
            up.y,
            up.z);
  }

  /** Snap in the light's XY basis; double intermediates retain precision far from world origin. */
  static void snapToTexels(Matrix4f view, double x, double y, double z, float distance, int size) {
    double texel = 2.0 * distance / size;
    double lx = view.m00() * x + view.m10() * y + view.m20() * z;
    double ly = view.m01() * x + view.m11() * y + view.m21() * z;
    view.m30(view.m30() + (float) (lx - Math.rint(lx / texel) * texel));
    view.m31(view.m31() + (float) (ly - Math.rint(ly / texel) * texel));
  }

  public static Frustum frustum() {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime == null || runtime.programs() == null || runtime.programs().find("shadow") == null)
      return null;
    Frustum result =
        new Frustum(
            runtime.uniforms().shadowModelView(),
            new Matrix4f(runtime.uniforms().shadowProjection()));
    var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
    result.prepare(camera.x, camera.y, camera.z);
    return result;
  }

  public static void beginExtraction() {
    discardSelection();
    extractionRenderer = null;
    extractionCache.clear();
  }

  public static Frustum withShadowFrustum(Frustum cameraFrustum) {
    Frustum light = frustum();
    if (light == null) return cameraFrustum;
    return new Frustum(cameraFrustum) {
      @Override
      public boolean isVisible(AABB bounds) {
        return cameraFrustum.isVisible(bounds) || light.isVisible(bounds.inflate(16));
      }
    };
  }

  /** Include off-camera casters in normal asynchronous chunk compilation and feature extraction. */
  public static ObjectArrayList<SectionRenderDispatcher.RenderSection> extractionSections(
      LevelRenderer renderer,
      SceneExtractionRequest.Purpose purpose,
      SectionUpdateTracker updateTracker) {
    var normal = renderer.visibleSections();
    Frustum shadowFrustum = frustum();
    ViewArea area = renderer.viewArea();
    if (shadowFrustum == null || area == null) return normal;
    if (extractionRenderer != renderer) extractionCache.clear();
    var cached = extractionCache.get(purpose);
    if (cached != null) return cached;
    // Keep one section of padding for extraction/render camera motion. Each vanilla loop needs
    // a different sparse subset; clean sections and featureless meshes have no extraction work.
    var selected =
        SceneViews.selectExtraction(
            new SceneExtractionRequest(
                new SceneViewRequest(
                    area,
                    SceneViews.generation(),
                    "shadow-extraction-" + purpose.name(),
                    new SceneFrustumVolume(shadowFrustum, 16),
                    null,
                    false),
                normal,
                purpose,
                updateTracker));
    var result = new ObjectArrayList<>(selected);
    extractionCache.put(purpose, result);
    extractionRenderer = renderer;
    return result;
  }

  public static void render(
      LevelRenderer renderer,
      ViewArea area,
      FeatureRenderDispatcher.PreparedFrame features,
      GpuBufferSlice terrainFog) {
    ShaderRuntime runtime = ShaderRuntime.get();
    boolean shadowStarted = false;
    try {
      if (runtime == null || area == null || !runtime.beginShadow()) return;
      shadowStarted = true;
      SelectionResult selected = selectForRender(renderer, area, runtime);
      SceneSelection selection = selected.selection();
      lastCullingStats =
          new CullingStats(
              RECEIVER_CULLING,
              selected.receiverApplied(),
              selection.candidates(),
              selection.sections().size(),
              selection.candidates() - selection.sections().size(),
              RECEIVER_CULLING || ASYNC_SELECTION ? selected.elapsedNanos() * 1.0e-6 : 0,
              selected.padding(),
              selected.eligibility());
      if (RECEIVER_CULLING && cullingFrames++ % 120 == 0) {
        LoggerFactory.getLogger("minecraft_shader_loader")
            .info("Shadow receiver culling: {}", lastCullingStats);
      }
      boolean translucent =
          !runtime.programs().properties.get("shadow.translucent", "true").equals("false");
      // Opaque terrain is the bulk of the shadow pass and is static between invalidations.
      // A complete front map is always sampled; changes rebuild a back map a slice per frame,
      // so no single frame pays for the whole terrain.
      boolean cache = frameKey != null;
      PackRenderTargets targets = runtime.targets();
      boolean haveFront = cache && frontKey != null;
      if (haveFront) {
        frontAge++;
        if (!building && needsRefresh(fingerprint(selection.sections()))) {
          building = true;
          backKey = frameKey;
          slice = 0;
        }
      }
      // Preparation consumes this view's ordered selection without replacing camera visibility.
      ChunkSectionsToRender chunks =
          haveFront && !translucent
              ? null
              : SceneViews.prepare(
                  renderer, selection, runtime.uniforms().cameraViewRotation(), false);
      var minecraft = Minecraft.getInstance();
      var atlas =
          minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
      var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
      RenderSystem.setShaderFog(terrainFog);
      try (ShaderRenderPass pass = new ShaderRenderPass(runtime)) {
        RenderSystem.bindDefaultUniforms(pass);
        if (!haveFront) {
          chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
          if (cache) {
            pass.suspend();
            targets.saveShadowCache(frontSlot);
            frontKey = frameKey;
            frontFingerprint = fingerprint(selection.sections());
            frontAge = 0;
          }
        } else {
          if (building) {
            var camera = cameraPos();
            applyKey(runtime, backKey, camera);
            SceneSelection back = selectSynchronously(area, runtime).selection();
            if (slice == 0) backFingerprint = fingerprint(back.sections());
            else targets.restoreShadowCache(1 - frontSlot);
            var part = new ArrayList<SectionRenderDispatcher.RenderSection>();
            for (var section : back.sections())
              if (inSlice(section, slice, CACHE_SLICES)) part.add(section);
            SceneSelection subset =
                new SceneSelection(
                    back.area(),
                    back.generation(),
                    back.viewId(),
                    part,
                    back.candidates(),
                    back.boundsTests(),
                    back.coarseTests(),
                    back.providerId());
            SceneViews.prepare(renderer, subset, runtime.uniforms().cameraViewRotation(), false)
                .renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
            pass.suspend();
            targets.saveShadowCache(1 - frontSlot);
            if (++slice == CACHE_SLICES) {
              frontSlot = 1 - frontSlot;
              frontKey = backKey;
              frontFingerprint = backFingerprint;
              frontAge = 0;
              building = false;
            }
            applyKey(runtime, frontKey, camera);
          }
          targets.restoreShadowCache(frontSlot);
        }
        features.executeSolid(pass);
        pass.suspend();
        if (runtime.programs().readsSampler("shadowtex1")) runtime.targets().copyShadowDepth();
        if (translucent) {
          chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
          features.executeTranslucent(pass);
          features.executeTranslucentAfterTerrain(pass);
        }
      }
    } finally {
      discardSelection();
      if (shadowStarted) runtime.endShadow();
    }
  }

  private static SelectionResult selectForRender(
      LevelRenderer renderer, ViewArea area, ShaderRuntime runtime) {
    if (!ASYNC_SELECTION) return selectSynchronously(area, runtime);
    long started = System.nanoTime();
    PendingSelection prepared = pendingSelection;
    pendingSelection = null;
    SelectionResult result;
    long elapsed;
    try {
      if (prepared != null && prepared.matches(renderer, area, runtime)) {
        if (prepared.ticket() != null) overlapUsed++;
        SceneSelection selection = SceneViews.select(prepared.request(), prepared.ticket());
        result =
            new SelectionResult(
                selection,
                prepared.receiverApplied(),
                prepared.padding(),
                prepared.eligibility(),
                0);
      } else {
        if (prepared != null) overlapStale++;
        result = selectSynchronously(area, runtime);
      }
    } finally {
      if (prepared != null && prepared.ticket() != null) prepared.ticket().close();
      elapsed = System.nanoTime() - started;
      overlapConsumeNanos += elapsed;
    }
    return new SelectionResult(
        result.selection(),
        result.receiverApplied(),
        result.padding(),
        result.eligibility(),
        frameCaptureNanos + elapsed);
  }

  private static SelectionResult selectSynchronously(ViewArea area, ShaderRuntime runtime) {
    // Keep the control's original math/order and timing boundary: the primary frustum was always
    // constructed before selectionCpuMs started. The async timer deliberately counts capture too.
    Frustum lightFrustum = frustum();
    var eligibility = runtime.programs().shadowCullingEligibility;
    boolean receiverCulling = RECEIVER_CULLING && eligibility.eligible();
    double padding = receiverPadding(TIGHT_RECEIVER_PADDING, receiverCulling);
    long started = RECEIVER_CULLING ? System.nanoTime() : 0;
    ShadowCasterVolume receiver =
        receiverCulling
            ? ShadowCasterVolume.create(
                runtime.uniforms().projection(),
                runtime.uniforms().modelView(),
                runtime.uniforms().shadowDirectionWorld(),
                padding)
            : null;
    Vec3 camera =
        Minecraft.getInstance()
            .gameRenderer
            .gameRenderState()
            .levelRenderState
            .cameraRenderState
            .pos;
    SceneVolume refinement = receiver == null ? null : new ReceiverRefinement(receiver, camera);
    SceneSelection selection =
        SceneViews.select(
            new SceneViewRequest(
                area,
                SceneViews.generation(),
                "shadow-render",
                new SceneFrustumVolume(lightFrustum, 0),
                refinement,
                true));
    return new SelectionResult(
        selection,
        receiver != null && receiver.planeCount() > 0,
        padding,
        eligibility.reason(),
        RECEIVER_CULLING ? System.nanoTime() - started : 0);
  }

  private record SelectionResult(
      SceneSelection selection,
      boolean receiverApplied,
      double padding,
      String eligibility,
      long elapsedNanos) {}

  private static SelectionInputs captureInputs(ShaderRuntime runtime) {
    var minecraft = Minecraft.getInstance();
    var uniforms = runtime.uniforms();
    return new SelectionInputs(
        uniforms.projection(),
        uniforms.modelView(),
        uniforms.shadowProjection(),
        uniforms.shadowModelView(),
        uniforms.shadowDirectionWorld(),
        minecraft.gameRenderer.mainCamera().position(),
        minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
  }

  private record PendingSelection(
      LevelRenderer renderer,
      ViewArea area,
      ShaderRuntime runtime,
      PackPrograms programs,
      SceneGeneration generation,
      long frame,
      SelectionInputs inputs,
      SceneViewRequest request,
      SceneSelectionTicket ticket,
      boolean receiverApplied,
      double padding,
      String eligibility) {
    boolean matches(LevelRenderer currentRenderer, ViewArea currentArea, ShaderRuntime current) {
      if (renderer != currentRenderer
          || area != currentArea
          || runtime != current
          || programs != current.programs()
          || frame != selectionFrame
          || !generation.equals(SceneViews.generation())) return false;
      var minecraft = Minecraft.getInstance();
      var uniforms = current.uniforms();
      return inputs.matches(
          uniforms.projection(),
          uniforms.modelView(),
          uniforms.shadowProjection(),
          uniforms.shadowModelView(),
          uniforms.shadowDirectionWorld(),
          minecraft.gameRenderer.mainCamera().position(),
          minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
    }
  }

  /**
   * Owns every mutable input; package-local seam verifies copying and exact invalidation on CPU.
   */
  static final class SelectionInputs {
    private final Matrix4f projection, modelView, shadowProjection, shadowModelView;
    private final Vector3f light;
    private final Vec3 primaryOrigin, receiverOrigin;

    SelectionInputs(
        Matrix4fc projection,
        Matrix4fc modelView,
        Matrix4fc shadowProjection,
        Matrix4fc shadowModelView,
        Vector3fc light,
        Vec3 primaryOrigin,
        Vec3 receiverOrigin) {
      this.projection = new Matrix4f(projection);
      this.modelView = new Matrix4f(modelView);
      this.shadowProjection = new Matrix4f(shadowProjection);
      this.shadowModelView = new Matrix4f(shadowModelView);
      this.light = new Vector3f(light);
      this.primaryOrigin = new Vec3(primaryOrigin.x, primaryOrigin.y, primaryOrigin.z);
      this.receiverOrigin = new Vec3(receiverOrigin.x, receiverOrigin.y, receiverOrigin.z);
    }

    Frustum frustum() {
      Frustum result = new Frustum(shadowModelView, shadowProjection);
      result.prepare(primaryOrigin.x, primaryOrigin.y, primaryOrigin.z);
      return result;
    }

    boolean matches(
        Matrix4fc projection,
        Matrix4fc modelView,
        Matrix4fc shadowProjection,
        Matrix4fc shadowModelView,
        Vector3fc light,
        Vec3 primaryOrigin,
        Vec3 receiverOrigin) {
      return same(this.projection, projection)
          && same(this.modelView, modelView)
          && same(this.shadowProjection, shadowProjection)
          && same(this.shadowModelView, shadowModelView)
          && same(this.light.x, light.x())
          && same(this.light.y, light.y())
          && same(this.light.z, light.z())
          && same(this.primaryOrigin, primaryOrigin)
          && same(this.receiverOrigin, receiverOrigin);
    }

    private static boolean same(Matrix4fc a, Matrix4fc b) {
      return same(a.m00(), b.m00())
          && same(a.m01(), b.m01())
          && same(a.m02(), b.m02())
          && same(a.m03(), b.m03())
          && same(a.m10(), b.m10())
          && same(a.m11(), b.m11())
          && same(a.m12(), b.m12())
          && same(a.m13(), b.m13())
          && same(a.m20(), b.m20())
          && same(a.m21(), b.m21())
          && same(a.m22(), b.m22())
          && same(a.m23(), b.m23())
          && same(a.m30(), b.m30())
          && same(a.m31(), b.m31())
          && same(a.m32(), b.m32())
          && same(a.m33(), b.m33());
    }

    private static boolean same(float a, float b) {
      return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b);
    }

    private static boolean same(Vec3 a, Vec3 b) {
      return Double.doubleToRawLongBits(a.x) == Double.doubleToRawLongBits(b.x)
          && Double.doubleToRawLongBits(a.y) == Double.doubleToRawLongBits(b.y)
          && Double.doubleToRawLongBits(a.z) == Double.doubleToRawLongBits(b.z);
    }
  }

  /**
   * Receiver planes and Vec3 coordinates are immutable; workers never read camera/runtime state.
   */
  static final class ReceiverRefinement implements SceneVolume {
    private final ShadowCasterVolume receiver;
    private final Vec3 origin;

    ReceiverRefinement(ShadowCasterVolume receiver, Vec3 origin) {
      this.receiver = receiver;
      this.origin = origin;
    }

    @Override
    public boolean intersects(AABB bounds) {
      return receiver.intersects(
          bounds.minX - origin.x,
          bounds.minY - origin.y,
          bounds.minZ - origin.z,
          bounds.maxX - origin.x,
          bounds.maxY - origin.y,
          bounds.maxZ - origin.z);
    }

    @Override
    public SceneVolume workerSnapshot() {
      return this;
    }
  }

  /** UI-like scene features and vanilla blob shadows are not sunlight occluders. */
  public static boolean skipPipeline(RenderPipeline pipeline) {
    String path = pipeline.getLocation().getPath();
    path = path.substring(path.lastIndexOf('/') + 1);
    return path.contains("entity_shadow")
        // Breaking cracks are a decal on the existing caster, not additional solid geometry.
        || path.contains("crumbling")
        || path.contains("text")
        || path.contains("outline")
        || path.contains("lines")
        || path.contains("line_strip")
        || path.contains("debug")
        || path.contains("particle")
        || path.contains("beacon_beam")
        // Both vanilla pipelines use additive LIGHTNING blending (OIT_ADDITIVE in OIT).
        // Their luminous effect volumes do not obstruct the light reaching other geometry.
        || path.equals("lightning")
        || path.equals("dragon_rays")
        // Fused item/entity glint pipelines still draw the caster's base material.
        || pipeline.getShaders().get(ShaderType.VERTEX).getPath().equals("core/glint")
        || path.contains("water_mask")
        || path.contains("weather")
        || path.contains("world_border")
        || path.contains("sky")
        || path.contains("cloud");
  }
}

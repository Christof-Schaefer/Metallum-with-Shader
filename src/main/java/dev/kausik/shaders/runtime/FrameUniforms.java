package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.ShaderDirectives;
import dev.kausik.shaders.pack.ShaderPackException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Real game values and temporal history shared by pack programs. Pack matrices use conventional
 * OpenGL -1..1 depth; the compiler converts their vertex output to Metal 0..1 depth.
 */
public final class FrameUniforms {
  private static final float[] IDENTITY = new Matrix4f().get(new float[16]);
  private static final float[] IDENTITY3 = new Matrix3f().get(new float[9]);
  private static final float[] TEXTURE_MATRICES = textureMatrices();
  private final Map<String, Double> scalars = new HashMap<>();
  private final Map<String, float[]> vectors = new HashMap<>();
  private final Map<String, float[]> matrices = new HashMap<>();
  private final Matrix4f projection = new Matrix4f();
  private final Matrix4f modelView = new Matrix4f();
  private final Matrix4f cameraViewRotation = new Matrix4f();
  private final Matrix4f cameraEffect = new Matrix4f();
  private final Matrix4f previousProjection = new Matrix4f();
  private final Matrix4f previousModelView = new Matrix4f();
  private final Matrix4f shadowProjection = new Matrix4f();
  private final Matrix4f shadowModelView = new Matrix4f();
  private final Matrix4f capturedProjection = new Matrix4f();
  private final Vector3f sunWorld = new Vector3f();
  private final Vector3f shadowWorld = new Vector3f();
  private ClientLevel previousLevel;
  private Vec3 cameraPosition = Vec3.ZERO;
  private Vec3 previousCameraPosition = Vec3.ZERO;
  private int previousWidth;
  private int previousHeight;
  private long previousNanos;
  private long startedNanos;
  private int frameCounter;
  private boolean historyReset = true;
  private boolean hasHistory;
  private boolean hasCapturedProjection;
  private Registry<Biome> previousBiomes;
  private final Map<String, Double> biomeConstants = new HashMap<>();
  private float wetness;
  private float eyeBlockLight;
  private float eyeSkyLight;
  private float centerDepth = 1;
  private float centerDepthSmooth = 1;
  private float previousEndFlash;
  private CustomUniforms customUniforms;
  private ToIntFunction<ItemStack> itemIds = stack -> -1;

  public void setCustomUniforms(CustomUniforms expressions) {
    customUniforms = expressions;
    if (expressions != null) expressions.reset();
    hasHistory = false;
  }

  public void setItemIdResolver(ToIntFunction<ItemStack> resolver) {
    itemIds = Objects.requireNonNull(resolver);
  }

  /** Supply asynchronous depth readback when a pack requests autofocus; no synchronous GPU wait. */
  public void setCenterDepth(float depth) {
    if (!Float.isFinite(depth) || depth < 0 || depth > 1) {
      throw new IllegalArgumentException("Pack depth must be finite and in [0,1]");
    }
    centerDepth = depth;
  }

  public boolean historyReset() {
    return historyReset;
  }

  /** Read the exact values that write() will upload; camera assumptions alone are insufficient. */
  public boolean matchesMatrix(
      dev.kausik.shaders.compile.MatrixUniformSpecialization.ZeroPattern pattern) {
    return pattern.matches(matrices.get(pattern.uniform()));
  }

  public Vector3fc sunDirectionWorld() {
    return sunWorld;
  }

  /** The same world-space light used by shadowLightPosition and the shadow camera. */
  public Vector3fc shadowDirectionWorld() {
    return shadowWorld;
  }

  public Matrix4fc projection() {
    return projection;
  }

  public Matrix4fc modelView() {
    return modelView;
  }

  /** Vanilla terrain uniforms need the unmodified camera rotation; the adapter adds effects. */
  public Matrix4fc cameraViewRotation() {
    return cameraViewRotation;
  }

  public Matrix4fc shadowProjection() {
    return shadowProjection;
  }

  public Matrix4fc shadowModelView() {
    return shadowModelView;
  }

  public void beginFrame(Minecraft minecraft, int width, int height, ShaderDirectives globals)
      throws ShaderPackException {
    ClientLevel level = Objects.requireNonNull(minecraft.level, "Pack frame requires a level");
    var game = minecraft.gameRenderer.gameRenderState();
    var state = game.levelRenderState;
    CameraRenderState camera = state.cameraRenderState;
    float partialTicks = state.worldPartialTicks;
    long now = System.nanoTime();
    if (startedNanos == 0) startedNanos = now;
    float elapsed = previousNanos == 0 ? 0 : (now - previousNanos) * 1.0e-9f;
    // Long loading stalls reset history instead of extrapolating previous-frame motion through
    // them.
    historyReset =
        !hasHistory
            || previousLevel != level
            || previousWidth != width
            || previousHeight != height
            || elapsed > 1
            || camera.pos.distanceToSqr(previousCameraPosition) > 64 * 64;
    previousNanos = now;
    previousLevel = level;
    previousWidth = width;
    previousHeight = height;
    cameraPosition = camera.pos;
    if (historyReset && customUniforms != null) customUniforms.reset();
    scalars.clear();
    vectors.clear();
    matrices.clear();
    updateCameraMatrices(
        camera.projectionMatrix,
        hasCapturedProjection ? capturedProjection : camera.projectionMatrix,
        camera.viewRotationMatrix);
    hasCapturedProjection = false;
    if (historyReset) {
      previousProjection.set(projection);
      previousModelView.set(modelView);
      previousCameraPosition = cameraPosition;
    }
    matrix("gbufferPreviousModelView", previousModelView);
    matrix("gbufferPreviousProjection", previousProjection);
    setShadowMatrices(shadowProjection, shadowModelView);
    put("frameTime", elapsed);
    put("frameTimeCounter", ((now - startedNanos) * 1.0e-9) % 3600);
    put("frameCounter", frameCounter);
    put("viewWidth", width);
    put("viewHeight", height);
    put("aspectRatio", (double) width / height);
    // The pack contract is terrain render distance, not the much farther cloud clipping plane.
    put("far", game.optionsRenderState.renderDistance * 16);
    put("cloudHeight", state.cloudHeight);
    put("screenBrightness", minecraft.options.gamma().get());
    put("worldTime", Math.floorMod(level.getOverworldClockTime(), 24000));
    put("worldDay", Math.floorDiv(level.getOverworldClockTime(), 24000));
    put("heightLimit", level.getMaxY() + 1);
    put("bedrockLevel", level.getMinY());
    put(
        "isEyeInWater",
        switch (camera.fogType) {
          case WATER -> 1;
          case LAVA -> 2;
          case POWDER_SNOW -> 3;
          default -> 0;
        });
    vector(
        "cameraPosition",
        (float) cameraPosition.x,
        (float) cameraPosition.y,
        (float) cameraPosition.z);
    vector(
        "cameraPositionFract",
        fract(cameraPosition.x),
        fract(cameraPosition.y),
        fract(cameraPosition.z));
    vector(
        "previousCameraPosition",
        (float) previousCameraPosition.x,
        (float) previousCameraPosition.y,
        (float) previousCameraPosition.z);
    vector("fogColor", camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z);
    var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
    vector("skyColor", probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTicks));
    float skyAngle =
        (float) Math.toRadians(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks));
    put("moonPhase", probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTicks).index());
    float pathRotation = (float) Math.toRadians(globals.floatConstant("sunPathRotation", 0));
    float moonAngle =
        (float) Math.toRadians(probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTicks));
    updateCelestial(
        skyAngle, moonAngle, pathRotation, level.dimension().equals(Level.END), modelView);
    vector("upPosition", modelView.transformDirection(new Vector3f(0, 1, 0)).normalize(100));
    put("endFlashIntensity", state.skyRenderState.endFlashIntensity);
    put(
        "previousEndFlashIntensity",
        historyReset ? state.skyRenderState.endFlashIntensity : previousEndFlash);
    Vector3f flash =
        new Matrix4f()
            .rotateY((float) Math.toRadians(180 - state.skyRenderState.endFlashYAngle))
            .rotateX((float) Math.toRadians(-90 - state.skyRenderState.endFlashXAngle))
            .transformDirection(new Vector3f(0, 100, 0));
    vector("endFlashPosition", modelView.transformDirection(flash).normalize(100));
    float rain = level.getRainLevel(partialTicks);
    float wetnessHalfLife =
        globals.floatConstant(
            rain > wetness ? "wetnessHalflife" : "drynessHalflife", rain > wetness ? 600 : 200);
    wetness = historyReset ? rain : smooth(wetness, rain, elapsed, wetnessHalfLife);
    put("rainStrength", rain);
    put("thunderStrength", level.getThunderLevel(partialTicks));
    put("wetness", wetness);
    float blockLight = level.getBrightness(LightLayer.BLOCK, camera.blockPos) * 16;
    float skyLight = level.getBrightness(LightLayer.SKY, camera.blockPos) * 16;
    float eyeHalfLife = globals.floatConstant("eyeBrightnessHalflife", 10);
    eyeBlockLight =
        historyReset ? blockLight : smooth(eyeBlockLight, blockLight, elapsed, eyeHalfLife);
    eyeSkyLight = historyReset ? skyLight : smooth(eyeSkyLight, skyLight, elapsed, eyeHalfLife);
    vector("eyeBrightness", blockLight, skyLight);
    vector("eyeBrightnessSmooth", Math.round(eyeBlockLight), Math.round(eyeSkyLight));
    centerDepthSmooth =
        historyReset
            ? centerDepth
            : smooth(
                centerDepthSmooth,
                centerDepth,
                elapsed,
                globals.floatConstant("centerDepthHalflife", 1));
    put("centerDepthSmooth", centerDepthSmooth);
    populateEntityStatus(minecraft, partialTicks);
    var atlas =
        minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
    vector("atlasSize", atlas.getWidth(0), atlas.getHeight(0));
    Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
    Biome biome = level.getBiome(camera.blockPos).value();
    put("biome", biomes.getId(biome));
    put("temperature", biome.getBaseTemperature());
    if (previousBiomes != biomes) {
      biomeConstants.clear();
      for (var entry : biomes.entrySet()) {
        String key =
            entry.getKey().identifier().getPath().toUpperCase(Locale.ROOT).replace('/', '_');
        biomeConstants.put("BIOME_" + key, (double) biomes.getId(entry.getValue()));
      }
      previousBiomes = biomes;
    }
    scalars.putAll(biomeConstants);
    setDrawState(0, 0, -1, -1);
    vector("entityColor", 0, 0, 0, 0);
    if (customUniforms != null) {
      // shortcut: elements come from the previous frame's matrices (this runs before the new
      // projection is set); fine for slowly changing values like the projection's FOV terms.
      matrixElements("gbufferProjection", projection);
      matrixElements("gbufferProjectionInverse", new Matrix4f(projection).invert());
      matrixElements("gbufferModelView", modelView);
      matrixElements("gbufferModelViewInverse", new Matrix4f(modelView).invert());
      var evaluated = customUniforms.evaluate(scalars, elapsed);
      scalars.putAll(evaluated);
      vectors.putAll(customUniforms.vec2Values(evaluated));
    }
  }

  /**
   * Public pack uniforms require shadowLightPosition to match the shadow pass direction. The End
   * has no celestial timeline in 26.3. Our initial BSL compatibility follows its modern non-Iris
   * fixed half-turn light convention there, including light below the horizon. Keep the End's
   * environment cycle angle unchanged: BSL uses it separately to select the light's sign.
   * https://shaders.properties/current/reference/uniforms/world/#shadowlightposition
   */
  void updateCelestial(
      float skyAngle, float moonAngle, float pathRotation, boolean end, Matrix4fc view) {
    float sunAngle = fract(skyAngle / (Math.PI * 2) + 0.25);
    put("sunAngle", sunAngle);
    put("shadowAngle", sunAngle > 0.5 ? sunAngle - 0.5 : sunAngle);
    celestialDirection(end ? (float) Math.PI : skyAngle, pathRotation, sunWorld);
    Vector3f moonWorld =
        end
            ? new Vector3f(sunWorld).negate()
            : celestialDirection(moonAngle, pathRotation, new Vector3f());
    shadowWorld.set(!end && sunAngle > 0.5 ? moonWorld : sunWorld);
    vector("sunPosition", view.transformDirection(new Vector3f(sunWorld)).normalize(100));
    vector("moonPosition", view.transformDirection(moonWorld).normalize(100));
    vector(
        "shadowLightPosition", view.transformDirection(new Vector3f(shadowWorld)).normalize(100));
  }

  private static Vector3f celestialDirection(float angle, float pathRotation, Vector3f target) {
    return target.set(
        -(float) Math.sin(angle),
        (float) (Math.cos(angle) * Math.cos(pathRotation)),
        (float) (-Math.cos(angle) * Math.sin(pathRotation)));
  }

  private void populateEntityStatus(Minecraft minecraft, float partialTicks) {
    var entity = minecraft.gameRenderer.mainCamera().entity();
    LivingEntity living = entity instanceof LivingEntity value ? value : null;
    var player = minecraft.player;
    Vec3 eye = entity == null ? cameraPosition : entity.getEyePosition(partialTicks);
    vector(
        "relativeEyePosition",
        (float) (cameraPosition.x - eye.x),
        (float) (cameraPosition.y - eye.y),
        (float) (cameraPosition.z - eye.z));
    float blindness = 0;
    if (living != null && living.getEffect(MobEffects.BLINDNESS) != null) {
      int duration = living.getEffect(MobEffects.BLINDNESS).getDuration();
      blindness = duration < 0 ? 1 : Math.clamp(duration / 20.0f, 0, 1);
    }
    put("blindness", blindness);
    put(
        "darknessFactor",
        living == null ? 0 : living.getEffectBlendFactor(MobEffects.DARKNESS, partialTicks));
    var lightmap = minecraft.gameRenderer.gameRenderState().lightmapRenderState;
    put("darknessLightFactor", lightmap.darknessEffectScale);
    put("nightVision", lightmap.nightVisionEffectIntensity);
    put("isRightHanded", player == null || player.getMainArm() == HumanoidArm.RIGHT ? 1 : 0);
    ItemStack first = player == null ? ItemStack.EMPTY : player.getMainHandItem();
    ItemStack second = player == null ? ItemStack.EMPTY : player.getOffhandItem();
    put("heldItemId", first.isEmpty() ? -1 : itemIds.applyAsInt(first));
    put("heldItemId2", second.isEmpty() ? -1 : itemIds.applyAsInt(second));
    put("heldBlockLightValue", heldBlockLight(first));
    put("heldBlockLightValue2", heldBlockLight(second));
  }

  private static int heldBlockLight(ItemStack stack) {
    return stack.getItem() instanceof BlockItem block
        ? block.getBlock().defaultBlockState().getLightEmission()
        : 0;
  }

  /** Capture the final native camera projection after view bob/nausea before drawing the level. */
  public void captureProjection(Matrix4fc nativeProjection) {
    capturedProjection.set(nativeProjection);
    hasCapturedProjection = true;
  }

  /**
   * Minecraft 26.3 places bob, hurt tilt and nausea in P*B. Legacy packs reconstruct depth using
   * the diagonal of P inverse, so retain the symmetric perspective P and move B into model-view.
   * Rasterization remains P*B*V; its inverse is now also valid for those optimized pack helpers.
   */
  void updateCameraMatrices(
      Matrix4fc baseNativeProjection, Matrix4fc combinedNativeProjection, Matrix4fc viewRotation) {
    cameraViewRotation.set(viewRotation);
    cameraEffect.set(baseNativeProjection).invert().mul(combinedNativeProjection);
    // Every vanilla camera effect is affine. Remove numerical residue from perspective inversion.
    cameraEffect.m03(0).m13(0).m23(0).m33(1);
    modelView.set(cameraEffect).mul(cameraViewRotation);
    matrix("sl_CameraEffect", cameraEffect);
    matrix("sl_CameraEffectInverse", new Matrix4f(cameraEffect).invert());
    matrix("gbufferModelView", modelView);
    matrix("gbufferModelViewInverse", new Matrix4f(modelView).invert());
    setProjection(baseNativeProjection);
  }

  /** Updates the base perspective; use captureProjection to supply the combined native P*B. */
  public void setProjection(Matrix4fc nativeProjection) {
    projection.identity().m22(-2).m32(1).mul(nativeProjection);
    matrix("gbufferProjection", projection);
    matrix("gbufferProjectionInverse", new Matrix4f(projection).invert());
    matrix("sl_TerrainModelViewProjection", new Matrix4f(projection).mul(modelView));
    if (historyReset) {
      previousProjection.set(projection);
      matrix("gbufferPreviousProjection", previousProjection);
    }
    float denominator = 1 + nativeProjection.m22();
    put("near", Math.abs(denominator) > 1.0e-8f ? nativeProjection.m32() / denominator : 0.05);
  }

  /** The supplied projection is conventional OpenGL -1..1 depth; both matrices are copied. */
  public void setShadowMatrices(Matrix4fc packProjection, Matrix4fc packModelView) {
    shadowProjection.set(packProjection);
    shadowModelView.set(packModelView);
    matrix("shadowProjection", shadowProjection);
    matrix("shadowProjectionInverse", new Matrix4f(shadowProjection).invert());
    matrix("shadowModelView", shadowModelView);
    matrix("shadowModelViewInverse", new Matrix4f(shadowModelView).invert());
    matrix("sl_ShadowModelViewProjection", new Matrix4f(shadowProjection).mul(shadowModelView));
  }

  public void setDrawState(int stage, float alphaTestRef, int entityId, int blockEntityId) {
    put("renderStage", stage);
    put("alphaTestRef", alphaTestRef);
    put("entityId", entityId);
    put("blockEntityId", blockEntityId);
  }

  public void setEntityColor(float red, float green, float blue, float alpha) {
    vector("entityColor", red, green, blue, alpha);
  }

  public void write(UniformLayout layout, ByteBuffer target, boolean fullscreen, boolean shadow) {
    target.order(ByteOrder.nativeOrder());
    for (int offset = 0; offset < layout.byteSize(); offset += 8) target.putLong(offset, 0);
    for (UniformLayout.Field field : layout.fields()) {
      String name = field.name();
      if (field.type().startsWith("mat")) {
        float[] data = matrixValue(name, fullscreen, shadow);
        if (data == null)
          throw new IllegalStateException("No game matrix supplied for pack uniform " + name);
        writeComponents(target, field, data, false);
      } else if (field.type().contains("vec")) {
        float[] data = vectors.get(name);
        if (data == null)
          throw new IllegalStateException("No game vector supplied for pack uniform " + name);
        writeComponents(target, field, data, !field.type().startsWith("vec"));
      } else {
        Double value = scalars.get(name);
        if (value == null)
          throw new IllegalStateException("No game value supplied for pack uniform " + name);
        if (field.arrayLength() > 0)
          throw new IllegalStateException("No array supplied for " + name);
        if (field.type().equals("float")) target.putFloat(field.offset(), value.floatValue());
        else target.putInt(field.offset(), value.intValue());
      }
    }
    target.position(0).limit(layout.byteSize());
  }

  /** Use the already resolved field; searching the whole layout for each field is quadratic. */
  private static void writeComponents(
      ByteBuffer target, UniformLayout.Field field, float[] values, boolean integral) {
    String type = field.type();
    int rows = type.charAt(type.length() - 1) - '0';
    int components = field.matrixStride() == 0 ? rows : rows * (type.charAt(3) - '0');
    int elements = Math.max(field.arrayLength(), 1);
    if (values.length != components * elements) {
      throw new IllegalStateException(
          "Wrong number of components for pack uniform " + field.name());
    }
    for (int element = 0; element < elements; element++) {
      int start = field.offset() + element * field.arrayStride();
      for (int component = 0; component < components; component++) {
        int offset =
            start
                + (field.matrixStride() == 0
                    ? component * 4
                    : component / rows * field.matrixStride() + component % rows * 4);
        float value = values[element * components + component];
        if (integral) target.putInt(offset, Math.round(value));
        else target.putFloat(offset, value);
      }
    }
  }

  private float[] matrixValue(String name, boolean fullscreen, boolean shadow) {
    Matrix4f view = shadow ? shadowModelView : modelView;
    Matrix4f proj = shadow ? shadowProjection : projection;
    return switch (name) {
      case "sl_ModelViewMatrix" -> fullscreen ? IDENTITY : view.get(new float[16]);
      case "sl_ProjectionMatrix" -> fullscreen ? IDENTITY : proj.get(new float[16]);
      case "sl_ModelViewProjectionMatrix" ->
          fullscreen ? IDENTITY : new Matrix4f(proj).mul(view).get(new float[16]);
      case "sl_NormalMatrix" ->
          fullscreen ? IDENTITY3 : new Matrix3f(view).invert().transpose().get(new float[9]);
      case "sl_TextureMatrix" -> TEXTURE_MATRICES;
      default -> matrices.get(name);
    };
  }

  public void endFrame() {
    previousProjection.set(projection);
    previousModelView.set(modelView);
    previousCameraPosition = cameraPosition;
    previousEndFlash = scalars.getOrDefault("endFlashIntensity", 0.0).floatValue();
    frameCounter = (frameCounter + 1) & 0x7fffffff;
    hasHistory = true;
  }

  private void put(String name, double value) {
    scalars.put(name, value);
  }

  private void vector(String name, Vector3fc value) {
    vector(name, value.x(), value.y(), value.z());
  }

  private void vector(String name, float... values) {
    vectors.put(name, values);
    String components = "xyzw";
    for (int i = 0; i < values.length; i++) put(name + "." + components.charAt(i), values[i]);
  }

  /** Exposes "name.column.row" scalars for custom-uniform expressions that index a matrix. */
  private void matrixElements(String name, Matrix4fc value) {
    String prefix = name + ".";
    if (customUniforms.requiredInputs().stream().noneMatch(input -> input.startsWith(prefix)))
      return;
    float[] data = value.get(new float[16]);
    for (int column = 0; column < 4; column++)
      for (int row = 0; row < 4; row++) {
        float v = data[column * 4 + row];
        // shortcut: the first frame can hold an uninitialised (NaN/inf) matrix; use identity then.
        if (!Float.isFinite(v)) v = column == row ? 1.0f : 0.0f;
        put(prefix + column + "." + row, v);
      }
  }

  private void matrix(String name, Matrix4fc value) {
    matrices.put(name, value.get(new float[16]));
  }

  private static float fract(double value) {
    return (float) (value - Math.floor(value));
  }

  private static float smooth(float before, float target, float seconds, float halfLifeTicks) {
    if (halfLifeTicks <= 0) return target;
    return target + (before - target) * (float) Math.pow(0.5, seconds * 20 / halfLifeTicks);
  }

  private static float[] textureMatrices() {
    float[] result = new float[8 * 16];
    for (int index = 0; index < 8; index++) System.arraycopy(IDENTITY, 0, result, index * 16, 16);
    result[16] = 1.0f / 256;
    result[21] = 1.0f / 256;
    result[28] = 8.0f / 256;
    result[29] = 8.0f / 256;
    return result;
  }
}

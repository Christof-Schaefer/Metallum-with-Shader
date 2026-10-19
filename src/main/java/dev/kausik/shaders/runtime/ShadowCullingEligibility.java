package dev.kausik.shaders.runtime;

import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderPackException;
import java.util.Map;

/** The receiver bounds are an audited pack contract, not a capability inferred from a name. */
public final class ShadowCullingEligibility {
  static final String BSL_10_1_8_FINGERPRINT =
      "fb4652d2b9bf7a142e56f6cad4046bd18115e2b5d4d61f534b2e711c309f925c";

  // Deliberately pinned independently of PackEnvironment: a new advertised capability or stage
  // changes preprocessing and must receive another source/receiver audit before enabling culling.
  private static final Map<String, String> AUDITED_ENVIRONMENT =
      Map.ofEntries(
          Map.entry("MC_VERSION", "260300"),
          Map.entry("MC_GL_VERSION", "330"),
          Map.entry("MC_GLSL_VERSION", "330"),
          Map.entry("MC_OS_MAC", ""),
          Map.entry("MC_HAND_DEPTH", "0.125"),
          Map.entry("MC_RENDER_STAGE_NONE", "0"),
          Map.entry("MC_RENDER_STAGE_SKY", "1"),
          Map.entry("MC_RENDER_STAGE_SUNSET", "2"),
          Map.entry("MC_RENDER_STAGE_CUSTOM_SKY", "3"),
          Map.entry("MC_RENDER_STAGE_SUN", "4"),
          Map.entry("MC_RENDER_STAGE_MOON", "5"),
          Map.entry("MC_RENDER_STAGE_STARS", "6"),
          Map.entry("MC_RENDER_STAGE_VOID", "7"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_SOLID", "8"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10"),
          Map.entry("MC_RENDER_STAGE_ENTITIES", "11"),
          Map.entry("MC_RENDER_STAGE_BLOCK_ENTITIES", "12"),
          Map.entry("MC_RENDER_STAGE_DESTROY", "13"),
          Map.entry("MC_RENDER_STAGE_OUTLINE", "14"),
          Map.entry("MC_RENDER_STAGE_DEBUG", "15"),
          Map.entry("MC_RENDER_STAGE_HAND_SOLID", "16"),
          Map.entry("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17"),
          Map.entry("MC_RENDER_STAGE_TRIPWIRE", "18"),
          Map.entry("MC_RENDER_STAGE_PARTICLES", "19"),
          Map.entry("MC_RENDER_STAGE_CLOUDS", "20"),
          Map.entry("MC_RENDER_STAGE_RAIN_SNOW", "21"),
          Map.entry("MC_RENDER_STAGE_WORLD_BORDER", "22"),
          Map.entry("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23"));

  public record Result(boolean eligible, String reason) {}

  private ShadowCullingEligibility() {}

  /** Evaluate once for a pack graph, using the exact world dimension before folder resolution. */
  public static Result assess(
      ShaderPack pack,
      Map<String, String> options,
      String dimension,
      Map<String, String> environment)
      throws ShaderPackException {
    return evaluate(
        pack.contentFingerprint(),
        pack.optionValues(options),
        pack.optionValues(Map.of()),
        dimension,
        pack.dimensionFolder(dimension),
        environment);
  }

  static Result evaluate(
      String fingerprint,
      Map<String, String> effectiveOptions,
      Map<String, String> defaults,
      String dimension,
      String directory,
      Map<String, String> environment) {
    if (!BSL_10_1_8_FINGERPRINT.equals(fingerprint))
      return new Result(false, "unrecognized-pack-content");
    if (!effectiveOptions.equals(defaults)) return new Result(false, "non-default-pack-options");
    if (!"minecraft:overworld".equals(dimension)) return new Result(false, "unsupported-dimension");
    if (!"world0".equals(directory)) return new Result(false, "unsupported-pack-directory");
    if (!AUDITED_ENVIRONMENT.equals(environment)) return new Result(false, "unsupported-environment");
    return new Result(true, "audited-bsl-10.1.8-defaults-overworld-v1");
  }
}

package dev.kausik.shaders.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Advertised capabilities and render stages are matched to the loader's actual implementation. */
public final class PackEnvironment {
  public static final List<String> STAGES =
      List.of(
          "NONE",
          "SKY",
          "SUNSET",
          "CUSTOM_SKY",
          "SUN",
          "MOON",
          "STARS",
          "VOID",
          "TERRAIN_SOLID",
          "TERRAIN_CUTOUT_MIPPED",
          "TERRAIN_CUTOUT",
          "ENTITIES",
          "BLOCK_ENTITIES",
          "DESTROY",
          "OUTLINE",
          "DEBUG",
          "HAND_SOLID",
          "TERRAIN_TRANSLUCENT",
          "TRIPWIRE",
          "PARTICLES",
          "CLOUDS",
          "RAIN_SNOW",
          "WORLD_BORDER",
          "HAND_TRANSLUCENT");

  public static Map<String, String> definitions() {
    Map<String, String> result = new LinkedHashMap<>();
    result.put("MC_VERSION", "260300");
    result.put("MC_GL_VERSION", "330");
    result.put("MC_GLSL_VERSION", "330");
    result.put("MC_OS_MAC", "");
    result.put("MC_HAND_DEPTH", "0.125");
    for (int i = 0; i < STAGES.size(); i++)
      result.put("MC_RENDER_STAGE_" + STAGES.get(i), Integer.toString(i));
    return Map.copyOf(result);
  }

  public static int stage(String name) {
    int stage = STAGES.indexOf(name);
    if (stage < 0) throw new IllegalArgumentException("Unknown render stage " + name);
    return stage;
  }
}

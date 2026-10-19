package dev.kausik.shaders.compile;

import java.util.List;

/** Backend-neutral GLSL and the complete resource contract needed to execute it. */
public record TranslatedProgram(
    String label,
    String vertexSource,
    String fragmentSource,
    UniformLayout uniforms,
    List<Sampler> samplers,
    List<Attribute> attributes,
    List<Integer> drawBuffers,
    String preprocessedVertex,
    String preprocessedFragment) {
  public TranslatedProgram {
    samplers = List.copyOf(samplers);
    attributes = List.copyOf(attributes);
    drawBuffers = List.copyOf(drawBuffers);
  }

  /** Names remain the pack's resource names, including aliases such as texture and gaux1. */
  public record Sampler(String name, String type) {
    public boolean comparison() {
      return type.endsWith("Shadow");
    }
  }

  /** legacyName identifies the incoming pack attribute; shaderName is its lowered GLSL name. */
  public record Attribute(String legacyName, String shaderName, String type, int location) {}
}

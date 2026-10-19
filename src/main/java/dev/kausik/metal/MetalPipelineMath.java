package dev.kausik.metal;

/** Metallum port shim: relaxed fragment math is not implemented; compilation stays Safe. */
public final class MetalPipelineMath {
  private MetalPipelineMath() {}

  public static Scope openRelaxedFragment(String exactPipelineName) {
    return new Scope();
  }

  public static final class Scope implements AutoCloseable {
    @Override
    public void close() {}
  }
}

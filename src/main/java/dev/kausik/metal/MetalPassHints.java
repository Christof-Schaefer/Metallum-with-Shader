package dev.kausik.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;

/** Metallum port shim: Metallum does not offer load-discard hints, so the capability is never advertised. */
public final class MetalPassHints {
  public static final String DISCARD_COLOR_CAPABILITY = "color-attachment-load-discard";

  private MetalPassHints() {}

  public static Scope openDiscardColors(RenderPassDescriptor descriptor, int mask) {
    return new Scope();
  }

  public static final class Scope implements AutoCloseable {
    @Override
    public void close() {}
  }
}

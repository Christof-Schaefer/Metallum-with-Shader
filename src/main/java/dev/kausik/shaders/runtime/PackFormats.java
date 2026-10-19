package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;

/** Expanding RGB storage to RGBA retains the requested color precision on Metal. */
public final class PackFormats {
  public static GpuFormat color(String format) {
    return switch (format) {
      case "R8" -> GpuFormat.R8_UNORM;
      case "RG8" -> GpuFormat.RG8_UNORM;
      case "RGB8", "RGBA8" -> GpuFormat.RGBA8_UNORM;
      case "RGBA16", "RGB16" -> GpuFormat.RGBA16_UNORM;
      case "R16F" -> GpuFormat.R16_FLOAT;
      case "RG16F" -> GpuFormat.RG16_FLOAT;
      case "RGB16F", "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
      case "R32F" -> GpuFormat.R32_FLOAT;
      case "RG32F" -> GpuFormat.RG32_FLOAT;
      case "RGB32F", "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
      case "R11F_G11F_B10F", "RG11B10F" -> GpuFormat.RG11B10_FLOAT;
      case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
      default -> throw new UnsupportedOperationException("Shader-pack color format " + format);
    };
  }
}

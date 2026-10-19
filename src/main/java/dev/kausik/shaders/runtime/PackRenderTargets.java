package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Color pairs survive across frames. Geometry writes current; screen passes write alternate. */
public final class PackRenderTargets implements AutoCloseable {
  public record BufferSpec(GpuFormat format, boolean clear, Vector4fc clearColor, boolean mipmaps) {
    public BufferSpec {
      clearColor = clearColor == null ? null : new Vector4f(clearColor);
    }
  }

  private static final BufferSpec DEFAULT =
      new BufferSpec(GpuFormat.RGBA8_UNORM, true, null, false);
  private final GpuDevice device;
  private final boolean lazyClears;
  private final Map<Integer, BufferSpec> specifications;
  private final Map<PackTexture, Vector4fc> pendingColors = new HashMap<>();
  private final Set<PackTexture> pendingDepths = new HashSet<>();
  private final Map<Integer, Pair> colors = new HashMap<>();
  private final List<PackTexture> owned = new ArrayList<>();
  private final PackTexture[] depths = new PackTexture[3];
  private PackTexture mergedDepth;
  private final PackTexture[] shadowDepths = new PackTexture[2];
  private final Map<Integer, PackTexture> shadowColors = new HashMap<>();
  @SuppressWarnings("unchecked")
  private final Map<Integer, PackTexture>[] shadowColorCaches =
      new Map[] {new HashMap<Integer, PackTexture>(), new HashMap<Integer, PackTexture>()};
  private final PackTexture[] shadowDepthCaches = new PackTexture[2];
  public final int width, height, shadowSize;
  private Vector4fc fogColor = new Vector4f();

  public PackRenderTargets(
      GpuDevice device,
      Map<Integer, BufferSpec> specifications,
      int width,
      int height,
      int shadowSize) {
    this(
        device,
        specifications,
        width,
        height,
        shadowSize,
        Boolean.getBoolean("minecraftShaders.lazyTargetClears"));
  }

  PackRenderTargets(
      GpuDevice device,
      Map<Integer, BufferSpec> specifications,
      int width,
      int height,
      int shadowSize,
      boolean lazyClears) {
    if (width < 1 || height < 1 || shadowSize < 1)
      throw new IllegalArgumentException("Invalid target size");
    this.device = device;
    this.lazyClears = lazyClears;
    this.specifications = Map.copyOf(specifications);
    this.width = width;
    this.height = height;
    this.shadowSize = shadowSize;
    try {
      for (int i = 0; i < depths.length; i++)
        depths[i] = allocate("depthtex" + i, GpuFormat.D32_FLOAT, width, height, 1);
      mergedDepth = allocate("merged hand depth", GpuFormat.D32_FLOAT, width, height, 1);
      for (int i = 0; i < shadowDepths.length; i++)
        shadowDepths[i] = allocate("shadowtex" + i, GpuFormat.D32_FLOAT, shadowSize, shadowSize, 1);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  private PackTexture allocate(String name, GpuFormat format, int w, int h, int mips) {
    PackTexture result = new PackTexture(device, name, format, w, h, mips);
    owned.add(result);
    return result;
  }

  public BufferSpec specification(int index) {
    return specifications.getOrDefault(index, DEFAULT);
  }

  public GpuFormat format(int index) {
    return specification(index).format();
  }

  private Pair pair(int index) {
    if (index < 0 || index > 31)
      throw new IllegalArgumentException("Color attachment outside 0..31");
    return colors.computeIfAbsent(
        index,
        key -> {
          BufferSpec spec = specification(key);
          int mips =
              spec.mipmaps() ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
          PackTexture a = allocate("colortex" + key + " A", spec.format(), width, height, mips);
          PackTexture b = allocate("colortex" + key + " B", spec.format(), width, height, mips);
          // Allocation can occur on first program use after beginFrame. Both sides must be defined.
          CommandEncoder encoder = device.createCommandEncoder();
          encoder.clearColorTexture(a.texture, clearColor(key));
          encoder.clearColorTexture(b.texture, clearColor(key));
          return new Pair(a, b);
        });
  }

  private Vector4fc clearColor(int index) {
    Vector4fc explicit = specification(index).clearColor();
    if (explicit != null) return explicit;
    return index == 0 ? fogColor : index == 1 ? new Vector4f(1) : new Vector4f();
  }

  public PackTexture color(int index) {
    return pair(index).read();
  }

  public PackTexture alternate(int index) {
    return pair(index).write();
  }

  public PackTexture depth(int index) {
    return depths[index];
  }

  PackTexture depthMergeTarget() {
    return mergedDepth;
  }

  void commitMergedDepth() {
    // Every consumer asks for the current depth view when binding. Rotate ownership instead of
    // copying the entire merged image back to the old scene image (both are otherwise identical).
    PackTexture previousScene = depths[0];
    depths[0] = mergedDepth;
    mergedDepth = previousScene;
  }

  public PackTexture shadowDepth(int index) {
    return shadowDepths[index];
  }

  public PackTexture shadowColor(int index) {
    return shadowColors.computeIfAbsent(
        index,
        key -> {
          if (key < 0 || key > 7) throw new IllegalArgumentException("Shadow color outside 0..7");
          PackTexture texture =
              allocate("shadowcolor" + key, GpuFormat.RGBA8_UNORM, shadowSize, shadowSize, 1);
          device.createCommandEncoder().clearColorTexture(texture.texture, new Vector4f(1));
          return texture;
        });
  }

  public void beginFrame(Vector4fc fog) {
    fogColor = new Vector4f(fog);
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      if (!specification(entry.getKey()).clear()) continue;
      // Only the readable side survives into geometry. Screen passes fully overwrite their outputs.
      clearColor(encoder, entry.getValue().read(), clearColor(entry.getKey()));
    }
    for (PackTexture depth : depths) clearDepth(encoder, depth);
    for (PackTexture depth : shadowDepths) clearDepth(encoder, depth);
    for (PackTexture color : shadowColors.values()) clearColor(encoder, color, new Vector4f(1));
  }

  private void clearColor(CommandEncoder encoder, PackTexture texture, Vector4fc color) {
    if (lazyClears) pendingColors.put(texture, new Vector4f(color));
    else encoder.clearColorTexture(texture.texture, color);
  }

  private void clearDepth(CommandEncoder encoder, PackTexture texture) {
    if (lazyClears) pendingDepths.add(texture);
    else encoder.clearDepthTexture(texture.texture, 1);
  }

  /**
   * Resolve a sampled/read source before any native pass opens, including before mip generation.
   */
  public void ensureDefined(PackTexture texture) {
    Vector4fc color = pendingColors.remove(texture);
    if (color != null) device.createCommandEncoder().clearColorTexture(texture.texture, color);
    if (pendingDepths.remove(texture))
      device.createCommandEncoder().clearDepthTexture(texture.texture, 1);
  }

  /** These checks do not allocate a late target or encode work inside an existing scene pass. */
  public boolean needsColorDefinition(int index) {
    Pair pair = colors.get(index);
    return pair == null || pendingColors.containsKey(pair.read());
  }

  public boolean needsDepthDefinition(int index) {
    return pendingDepths.contains(depths[index]);
  }

  public boolean needsShadowColorDefinition(int index) {
    PackTexture texture = shadowColors.get(index);
    return texture == null || pendingColors.containsKey(texture);
  }

  public boolean needsShadowDepthDefinition(int index) {
    return pendingDepths.contains(shadowDepths[index]);
  }

  private Optional<Vector4fc> attachmentClear(PackTexture texture) {
    Vector4fc color = pendingColors.remove(texture);
    if (color == null) return Optional.empty();
    // The attachment load action clears only level zero. Keep every other mip defined exactly
    // as the eager all-level clear did; the graph has not proved these levels dead.
    var encoder = device.createCommandEncoder();
    for (int mip = 1; mip < texture.texture.getMipLevels(); mip++) {
      try (var ignored =
          encoder.createRenderPass(
              () -> "Shader pack pending mip clear", texture.level(mip), Optional.of(color))) {
        // A clear-only pass intentionally has no draw.
      }
    }
    return Optional.of(color);
  }

  private OptionalDouble depthAttachmentClear(PackTexture texture) {
    return pendingDepths.remove(texture) ? OptionalDouble.of(1) : OptionalDouble.empty();
  }

  /** Discard temporal images after a camera discontinuity, including targets with Clear=false. */
  public void resetHistory(Vector4fc fog) {
    fogColor = new Vector4f(fog);
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      Vector4fc clear = clearColor(entry.getKey());
      pendingColors.remove(entry.getValue().read());
      pendingColors.remove(entry.getValue().write());
      encoder.clearColorTexture(entry.getValue().read().texture, clear);
      encoder.clearColorTexture(entry.getValue().write().texture, clear);
    }
  }

  public RenderPassDescriptor descriptor(
      String name, List<Integer> outputs, boolean screen, boolean shadow, boolean withDepth) {
    return descriptor(name, outputs, screen, shadow, withDepth, 0);
  }

  public RenderPassDescriptor descriptor(
      String name,
      List<Integer> outputs,
      boolean screen,
      boolean shadow,
      boolean withDepth,
      int depthIndex) {
    if (withDepth && (depthIndex < 0 || depthIndex >= depths.length))
      throw new IllegalArgumentException("Depth attachment outside 0..2");
    RenderPassDescriptor.Builder result = RenderPassDescriptor.builder(() -> name);
    for (int output : outputs) {
      if (output < 0) result.withUnusedColorAttachment();
      else {
        PackTexture target =
            shadow ? shadowColor(output) : screen ? alternate(output) : color(output);
        result.withColorAttachment(target.level(0), attachmentClear(target));
      }
    }
    if (withDepth) {
      PackTexture target = shadow ? shadowDepths[0] : depths[depthIndex];
      result.withDepthAttachment(target.level(0), depthAttachmentClear(target));
    }
    return result.build();
  }

  public void flip(List<Integer> outputs) {
    // A logical attachment changes sides once even if malformed metadata repeats it.
    outputs.stream().filter(index -> index >= 0).distinct().forEach(index -> pair(index).flip());
  }

  /**
   * Capture once after opaque world geometry, before any transparent or hand draw. depthtex2 must
   * retain this image; depthtex1 can subsequently receive opaque-hand depth independently.
   */
  public void snapshotOpaqueDepth() {
    copyDepth(1);
    copyDepth(2);
  }

  public void copyDepth(int destination) {
    copy(depths[0], depths[destination], width, height);
  }

  public void copyShadowDepth() {
    copy(shadowDepths[0], shadowDepths[1], shadowSize, shadowSize);
  }

  /** Keep the opaque-terrain shadow images in a cache slot so later frames can reuse them. */
  public void saveShadowCache(int slot) {
    if (shadowDepthCaches[slot] == null)
      shadowDepthCaches[slot] =
          allocate("shadowtex0 cache " + slot, GpuFormat.D32_FLOAT, shadowSize, shadowSize, 1);
    copy(shadowDepths[0], shadowDepthCaches[slot], shadowSize, shadowSize);
    for (var entry : shadowColors.entrySet()) {
      PackTexture cache =
          shadowColorCaches[slot].computeIfAbsent(
              entry.getKey(),
              key ->
                  allocate(
                      "shadowcolor" + key + " cache " + slot,
                      GpuFormat.RGBA8_UNORM,
                      shadowSize,
                      shadowSize,
                      1));
      copy(entry.getValue(), cache, shadowSize, shadowSize);
    }
  }

  /** False when the slot holds no complete cache; the shadow images are then left untouched. */
  public boolean restoreShadowCache(int slot) {
    if (shadowDepthCaches[slot] == null
        || !shadowColorCaches[slot].keySet().containsAll(shadowColors.keySet())) return false;
    copy(shadowDepthCaches[slot], shadowDepths[0], shadowSize, shadowSize);
    for (var entry : shadowColors.entrySet())
      copy(shadowColorCaches[slot].get(entry.getKey()), entry.getValue(), shadowSize, shadowSize);
    return true;
  }

  private void copy(PackTexture from, PackTexture to, int w, int h) {
    // All callers copy an entire single-level texture. Its queued clear is now dead.
    if (to.texture.getMipLevels() != 1
        || w != to.texture.getWidth(0)
        || h != to.texture.getHeight(0))
      throw new IllegalStateException("Snapshot must overwrite the whole destination");
    ensureDefined(from);
    device
        .createCommandEncoder()
        .copyTextureToTexture(from.texture, to.texture, 0, 0, 0, 0, 0, w, h);
    pendingDepths.remove(to);
    pendingColors.remove(to);
  }

  @Override
  public void close() {
    for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    owned.clear();
    colors.clear();
    shadowColors.clear();
    for (var caches : shadowColorCaches) caches.clear();
    java.util.Arrays.fill(shadowDepthCaches, null);
    pendingColors.clear();
    pendingDepths.clear();
  }

  private static final class Pair {
    private final PackTexture a, b;
    private boolean flipped;

    Pair(PackTexture a, PackTexture b) {
      this.a = a;
      this.b = b;
    }

    PackTexture read() {
      return flipped ? b : a;
    }

    PackTexture write() {
      return flipped ? a : b;
    }

    void flip() {
      flipped = !flipped;
    }
  }
}

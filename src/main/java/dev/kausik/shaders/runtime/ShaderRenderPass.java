package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/**
 * Adapts one logical Minecraft pass to the pack's attachment groups. Draw buffers and indirect
 * commands are forwarded unchanged; only a target change or an explicit graph boundary splits it.
 */
public final class ShaderRenderPass implements RenderPass {
  public record TextureBinding(GpuTextureView texture, GpuSampler sampler) {}

  private final ShaderRuntime runtime;
  private final Map<String, GpuBufferSlice> buffers = new LinkedHashMap<>();
  private final Map<String, TextureBinding> textures = new LinkedHashMap<>();
  private final GpuBufferSlice[] vertices = new GpuBufferSlice[MAX_VERTEX_BUFFERS];
  private final List<Supplier<String>> groups = new ArrayList<>();
  private GpuBuffer index;
  private IndexType indexType;
  private ByteBuffer constants;
  private int[] scissor;
  private RenderPass delegate;
  private PackPipeline pipeline;
  private PackPipeline bound;
  private FeatureDrawContext.DrawTag boundTag;
  private boolean closed;
  private boolean skipDraw;

  public ShaderRenderPass(ShaderRuntime runtime) {
    this.runtime = runtime;
    runtime.opened(this);
  }

  public Map<String, TextureBinding> textures() {
    return textures;
  }

  private RenderPass ready() {
    if (closed) throw new IllegalStateException("Shader render pass is closed");
    if (pipeline == null) throw new IllegalStateException("No scene pipeline selected");
    if (delegate == null) {
      runtime.prepare(pipeline);
      delegate = runtime.open(pipeline);
      for (var group : groups) delegate.pushDebugGroup(group);
      for (var uniform : buffers.entrySet())
        delegate.setUniform(uniform.getKey(), uniform.getValue());
      for (var uniform : textures.entrySet())
        delegate.setUniform(
            uniform.getKey(), uniform.getValue().texture(), uniform.getValue().sampler());
      for (int slot = 0; slot < vertices.length; slot++) {
        if (vertices[slot] != null) delegate.setVertexBuffer(slot, vertices[slot]);
      }
      if (index != null) delegate.setIndexBuffer(index, indexType);
      if (scissor != null) delegate.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
      bound = null;
    }
    if (bound != pipeline) {
      delegate.setPipeline(pipeline.compiled());
      if (constants != null
          && pipeline.compiled()
              instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend
          && frontend.pushConstantSize() > 0) delegate.pushConstants(constants.duplicate());
      runtime.bind(pipeline, delegate, textures);
      bound = pipeline;
      boundTag = FeatureDrawContext.currentDrawTag();
    } else if (!FeatureDrawContext.currentDrawTag().equals(boundTag)) {
      // Prepared feature groups can share a pipeline while changing material IDs or damage tint.
      runtime.bindUniforms(pipeline, delegate);
      boundTag = FeatureDrawContext.currentDrawTag();
    }
    return delegate;
  }

  /** End the native pass before depth snapshots or screen passes; keep the logical draw state. */
  public void suspend() {
    if (delegate != null) {
      for (int i = groups.size() - 1; i >= 0; i--) delegate.popDebugGroup();
      delegate.close();
      delegate = null;
      bound = null;
    }
  }

  @Override
  public void setPipeline(CompiledRenderPipeline original) {
    var description = OriginalPipelines.get(original);
    skipDraw = runtime.isShadow() && ShadowRenderer.skipPipeline(description);
    if (skipDraw) return;
    PackPipeline next = runtime.pipeline(description);
    if (pipeline != null
        && (!pipeline.translated().drawBuffers().equals(next.translated().drawBuffers())
            || pipeline.depth() != next.depth()
            || pipeline.depthTarget() != next.depthTarget()
            || pipeline.shadow() != next.shadow()
            || pipeline != next && runtime.requiresMipGeneration(next)
            || runtime.requiresReadPreparation(next))) suspend();
    pipeline = next;
  }

  @Override
  public void setUniform(String name, GpuTextureView texture, GpuSampler sampler) {
    textures.put(name, new TextureBinding(texture, sampler));
    if (delegate != null) {
      delegate.setUniform(name, texture, sampler);
      if (bound != null) runtime.bindSuppliedTexture(bound, delegate, textures, name);
    }
  }

  @Override
  public void setUniform(String name, GpuBuffer buffer) {
    setUniform(name, buffer.slice());
  }

  @Override
  public void setUniform(String name, GpuBufferSlice buffer) {
    buffers.put(name, buffer);
    if (delegate != null) delegate.setUniform(name, buffer);
  }

  @Override
  public void setVertexBuffer(int slot, GpuBufferSlice buffer) {
    vertices[slot] = buffer;
    if (delegate != null) delegate.setVertexBuffer(slot, buffer);
  }

  @Override
  public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
    index = buffer;
    indexType = type;
    if (delegate != null) delegate.setIndexBuffer(buffer, type);
  }

  @Override
  public void pushConstants(ByteBuffer values) {
    if (constants == null || constants.capacity() < values.remaining())
      constants = ByteBuffer.allocateDirect(values.remaining());
    constants.clear().put(values.duplicate()).flip();
    if (delegate != null) delegate.pushConstants(constants.duplicate());
  }

  @Override
  public void enableScissor(int x, int y, int width, int height) {
    scissor = new int[] {x, y, width, height};
    if (delegate != null) delegate.enableScissor(x, y, width, height);
  }

  @Override
  public void disableScissor() {
    scissor = null;
    if (delegate != null) delegate.disableScissor();
  }

  @Override
  public void pushDebugGroup(Supplier<String> name) {
    groups.add(name);
    if (delegate != null) delegate.pushDebugGroup(name);
  }

  @Override
  public void popDebugGroup() {
    if (groups.isEmpty()) throw new IllegalStateException("No debug group to close");
    groups.removeLast();
    if (delegate != null) delegate.popDebugGroup();
  }

  @Override
  public void writeTimestamp(GpuQueryPool pool, int query) {
    if (delegate != null) delegate.writeTimestamp(pool, query);
    else runtime.device().createCommandEncoder().writeTimestamp(pool, query);
  }

  @Override
  public void drawIndexed(int a, int b, int c, int d, int e) {
    if (!skipDraw) ready().drawIndexed(a, b, c, d, e);
  }

  @Override
  public void multiDrawIndexed(IntBuffer a, int b, int c, int d) {
    if (!skipDraw) ready().multiDrawIndexed(a, b, c, d);
  }

  @Override
  public void multiDrawIndexed(PointerBuffer a, IntBuffer b, IntBuffer c, int d) {
    if (!skipDraw) ready().multiDrawIndexed(a, b, c, d);
  }

  @Override
  public void drawIndexedIndirect(GpuBufferSlice a, int b) {
    if (!skipDraw) ready().drawIndexedIndirect(a, b);
  }

  @Override
  public <T> void drawMultipleIndexed(
      Collection<Draw<T>> a, GpuBuffer b, IndexType c, Collection<String> d, T e) {
    if (!skipDraw) ready().drawMultipleIndexed(a, b, c, d, e);
  }

  @Override
  public void draw(int a, int b, int c, int d) {
    if (!skipDraw) ready().draw(a, b, c, d);
  }

  @Override
  public void multiDraw(IntBuffer a, int b, int c, int d) {
    if (!skipDraw) ready().multiDraw(a, b, c, d);
  }

  @Override
  public void multiDraw(IntBuffer a, IntBuffer b, int c) {
    if (!skipDraw) ready().multiDraw(a, b, c);
  }

  @Override
  public void drawIndirect(GpuBufferSlice a, int b) {
    if (!skipDraw) ready().drawIndirect(a, b);
  }

  @Override
  public void close() {
    if (closed) return;
    suspend();
    closed = true;
    runtime.closed(this);
  }
}

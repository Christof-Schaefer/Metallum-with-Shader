package dev.kausik.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityFeatureTagMixin {
  @WrapMethod(method = "submit")
  private void shaders$tagEntity(
      EntityRenderState state,
      CameraRenderState camera,
      double x,
      double y,
      double z,
      PoseStack poses,
      SubmitNodeCollector output,
      Operation<Void> original) {
    var previous = FeatureDrawContext.setSubmission(FeatureDrawContext.entityTag(state));
    try {
      original.call(state, camera, x, y, z, poses, output);
    } finally {
      FeatureDrawContext.setSubmission(previous);
    }
  }
}

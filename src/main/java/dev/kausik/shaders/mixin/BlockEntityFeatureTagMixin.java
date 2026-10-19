package dev.kausik.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityFeatureTagMixin {
  @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
  private void shaders$extractBlockMaterial(
      BlockEntity block,
      float partialTicks,
      ModelFeatureRenderer.CrumblingOverlay breaking,
      boolean globallyRendered,
      CallbackInfoReturnable<BlockEntityRenderState> callback) {
    FeatureDrawContext.extractedBlockEntity(callback.getReturnValue(), block.getBlockState());
  }

  @WrapMethod(method = "submit")
  private void shaders$tagBlockEntity(
      BlockEntityRenderState state,
      PoseStack poses,
      SubmitNodeCollector output,
      CameraRenderState camera,
      Operation<Void> original) {
    var previous = FeatureDrawContext.setSubmission(FeatureDrawContext.blockEntityTag(state));
    try {
      original.call(state, poses, output, camera);
    } finally {
      FeatureDrawContext.setSubmission(previous);
    }
  }
}

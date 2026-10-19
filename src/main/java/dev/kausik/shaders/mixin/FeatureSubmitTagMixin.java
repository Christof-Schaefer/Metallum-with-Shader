package dev.kausik.shaders.mixin;

import dev.kausik.shaders.geometry.FeatureDrawContext;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleFeatureRenderPhase.class)
public abstract class FeatureSubmitTagMixin {
  @Inject(method = "submit", at = @At("HEAD"))
  private void shaders$tagSubmit(SubmitNode submit, CallbackInfo callback) {
    FeatureDrawContext.recordSubmit(submit);
  }
}

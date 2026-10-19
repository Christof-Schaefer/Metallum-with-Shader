package dev.kausik.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import dev.kausik.shaders.compile.ShadercFragmentOptimization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Changes only selected stages of our pack shaders before normal reflection/linkage. */
@Mixin(GlslCompiler.class)
public abstract class ShaderGlslCompilerMixin {
  @ModifyExpressionValue(
      method = "compileToSpv",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lcom/mojang/renderpearl/frontend/shaders/GlslCompiler;createBaseShaderOptions()J"))
  private long shaders$fragmentOptimization(
      long options,
      @Local(argsOnly = true, ordinal = 0) String name,
      @Local(argsOnly = true) ShaderType stage) {
    return ShadercFragmentOptimization.configure(options, name, stage);
  }
}

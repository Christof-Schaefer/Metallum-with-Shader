package dev.kausik.shaders;

import dev.kausik.shaders.geometry.FeatureDrawContext;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.MaterialMappings;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderPackException;
import dev.kausik.shaders.runtime.BlockMaterialMap;
import dev.kausik.shaders.runtime.PackEnvironment;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the optional shader-pack loader, developed separately from GPU execution. */
public final class ShaderLoaderMod implements ClientModInitializer {
  public static final String ID = "minecraft_shader_loader";
  private static final Logger LOGGER = LoggerFactory.getLogger(ID);

  @Override
  public void onInitializeClient() {
    Path selected = null;
    try {
      FabricLoader loader = FabricLoader.getInstance();
      ShaderLoaderConfig config =
          ShaderLoaderConfig.load(loader.getGameDir(), loader.getConfigDir());
      selected = config.pack();
      if (selected == null) {
        LOGGER.info("Shader loader ready; no shader pack selected.");
        return;
      }
      ShaderPack pack = ShaderPack.load(selected);
      var options = config.effectiveOptions(pack);
      var environment = PackEnvironment.definitions();
      var properties = pack.properties(options, environment);
      // Validate CPU-side configuration before changing the terrain's vertex layout. Shader/GPU
      // resources are prepared by the runtime after a world and graphics device are available.
      CustomUniforms.compile(properties);
      String separateAo = properties.get("separateAo", "false");
      if (!separateAo.equals("true") && !separateAo.equals("false")) {
        throw new ShaderPackException("Expected true or false for separateAo, found " + separateAo);
      }
      BlockMaterialMap materials =
          new BlockMaterialMap(MaterialMappings.load(pack, options, environment));
      ShaderRuntime runtime = new ShaderRuntime(pack, options, properties);
      runtime.uniforms().setItemIdResolver(materials::itemId);
      TerrainShaderGeometry.configure(
          materials, Boolean.parseBoolean(separateAo), materials.allBlockIdsFitSigned16());
      FeatureDrawContext.configure(materials::entityTypeId, materials);
      ShaderRuntime.install(runtime);
      LOGGER.info(
          "Selected shader pack {} with {} cached block states; render programs initialize when"
              + " entering a world.",
          pack.name(),
          materials.stateCount());
    } catch (IOException | RuntimeException failure) {
      String context =
          selected == null ? "shader-loader configuration" : "selected shader pack " + selected;
      LOGGER.error(
          "Cannot initialize {}; shader-pack rendering was not enabled.", context, failure);
      throw new IllegalStateException(
          "Cannot initialize " + context + ": " + failure.getMessage(), failure);
    }
  }
}

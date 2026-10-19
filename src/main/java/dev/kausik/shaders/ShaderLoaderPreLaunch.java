package dev.kausik.shaders;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.LoggerFactory;

/** Tuning flags are read once into static finals; publish config-file values before any loads. */
public final class ShaderLoaderPreLaunch implements PreLaunchEntrypoint {
  @Override
  public void onPreLaunch() {
    var config = FabricLoader.getInstance().getConfigDir().resolve("minecraft-shader-loader.properties");
    if (!Files.isRegularFile(config)) return;
    Properties properties = new Properties();
    try (var input = Files.newBufferedReader(config)) {
      properties.load(input);
    } catch (IOException failure) {
      LoggerFactory.getLogger("minecraft_shader_loader").warn("Cannot read {}", config, failure);
      return;
    }
    for (String key : properties.stringPropertyNames()) {
      boolean flag =
          key.startsWith("minecraftShaders.")
              || key.startsWith("minecraftScene.")
              || key.startsWith("metallum.");
      // Explicit JVM arguments win over the file.
      if (flag && System.getProperty(key) == null)
        System.setProperty(key, properties.getProperty(key).trim());
    }
  }
}

package dev.kausik.shaders;

import dev.kausik.shaders.pack.ShaderPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** A selected pack is external content. Neither its source nor its settings enter the mod JAR. */
public record ShaderLoaderConfig(Path pack, Map<String, String> options, String profile) {
  public ShaderLoaderConfig {
    options = Map.copyOf(options);
  }

  public static ShaderLoaderConfig load(Path gameDirectory, Path configDirectory)
      throws IOException {
    Path packs = gameDirectory.resolve("shaderpacks");
    Files.createDirectories(packs);
    Properties properties = new Properties();
    Path config = configDirectory.resolve("minecraft-shader-loader.properties");
    if (Files.isRegularFile(config)) {
      try (var input = Files.newBufferedReader(config)) {
        properties.load(input);
      }
    }
    String selected =
        System.getProperty("minecraftShaders.pack", properties.getProperty("pack", "")).trim();
    String profile =
        System.getProperty("minecraftShaders.profile", properties.getProperty("profile", ""))
            .trim();
    Map<String, String> options = new TreeMap<>();
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith("option."))
        options.put(key.substring(7), properties.getProperty(key).trim());
    }
    for (String key : System.getProperties().stringPropertyNames()) {
      if (key.startsWith("minecraftShaders.option."))
        options.put(key.substring("minecraftShaders.option.".length()), System.getProperty(key));
    }
    Path location = selected.isEmpty() ? null : Path.of(selected);
    if (location != null && !location.isAbsolute()) location = packs.resolve(location).normalize();
    return new ShaderLoaderConfig(location, options, profile);
  }

  public Map<String, String> effectiveOptions(ShaderPack snapshot) throws IOException {
    Map<String, String> result = new TreeMap<>();
    if (!profile.isEmpty()) result.putAll(snapshot.profileOverrides(profile));
    result.putAll(options);
    snapshot.optionValues(result);
    return Map.copyOf(result);
  }
}

package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.ShaderCompatibilityCompiler;
import dev.kausik.shaders.compile.TranslatedProgram;
import dev.kausik.shaders.pack.ShaderDirectives;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderPackException;
import dev.kausik.shaders.pack.ShaderProgram;
import dev.kausik.shaders.pack.ShaderProperties;
import dev.kausik.shaders.pack.ShaderStage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.joml.Vector4f;

/** CPU-only program discovery and validation complete before allocating a new frame graph. */
public final class PackPrograms implements AutoCloseable {
  private static final boolean EARLY_ALPHA_DEMOTE =
      Boolean.getBoolean("minecraftShaders.earlyAlphaDemote");

  public record Source(
      String name,
      String vertex,
      String fragment,
      TranslatedProgram translated,
      ShaderDirectives directives,
      boolean relaxedFragmentMath) {
    /** External/test/derived sources do not inherit a pack's opt-in policy implicitly. */
    public Source(
        String name,
        String vertex,
        String fragment,
        TranslatedProgram translated,
        ShaderDirectives directives) {
      this(name, vertex, fragment, translated, directives, false);
    }
  }

  private static final Map<String, String> FALLBACK =
      Map.ofEntries(
          Map.entry("gbuffers_terrain", "gbuffers_textured_lit"),
          Map.entry("gbuffers_damagedblock", "gbuffers_terrain"),
          Map.entry("gbuffers_water", "gbuffers_terrain"),
          Map.entry("gbuffers_block", "gbuffers_terrain"),
          Map.entry("gbuffers_entities", "gbuffers_textured_lit"),
          Map.entry("gbuffers_entities_glowing", "gbuffers_entities"),
          Map.entry("gbuffers_entities_translucent", "gbuffers_entities"),
          Map.entry("gbuffers_hand", "gbuffers_textured_lit"),
          Map.entry("gbuffers_hand_water", "gbuffers_hand"),
          Map.entry("gbuffers_armor_glint", "gbuffers_textured"),
          Map.entry("gbuffers_textured_lit", "gbuffers_textured"),
          Map.entry("gbuffers_weather", "gbuffers_textured_lit"),
          Map.entry("gbuffers_clouds", "gbuffers_textured"),
          Map.entry("gbuffers_skytextured", "gbuffers_textured"),
          Map.entry("gbuffers_skybasic", "gbuffers_basic"),
          Map.entry("gbuffers_textured", "gbuffers_basic"),
          Map.entry("gbuffers_beaconbeam", "gbuffers_textured"));
  private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
  private static final Pattern UNIFORM_DECLARATION = Pattern.compile("\\buniform\\s+[^;]+;");
  private static final Pattern CENTER_DEPTH = Pattern.compile("\\bcenterDepthSmooth\\b");
  public final ShaderPack pack;
  public final Map<String, String> options, environment;
  public final ShaderProperties properties;
  public final String directory;
  public final ShaderDirectives globals;
  public final ShadowCullingEligibility.Result shadowCullingEligibility;
  public final boolean relaxedFragmentMath;
  private final Map<String, Source> sources = new HashMap<>();
  private final Map<String, List<Source>> sequences = new HashMap<>();
  private final Set<String> samplers = new HashSet<>();
  private final Set<String> postSamplers = new HashSet<>();
  private final ShaderCompatibilityCompiler compiler;

  public PackPrograms(ShaderPack pack, Map<String, String> options, String dimension)
      throws ShaderPackException {
    this(pack, options, dimension, ShaderCompatibilityCompiler.ShadowComparison.EMULATED);
  }

  public PackPrograms(
      ShaderPack pack,
      Map<String, String> options,
      String dimension,
      ShaderCompatibilityCompiler.ShadowComparison shadowComparison)
      throws ShaderPackException {
    this.pack = pack;
    this.options = Map.copyOf(options);
    environment = PackEnvironment.definitions();
    directory = pack.dimensionFolder(dimension);
    properties = pack.properties(options, environment);
    Map<String, String> constants = new TreeMap<>();
    Map<String, String> definitions = pack.definitions(options, environment);
    Map<String, String> effectiveOptions = pack.optionValues(options);
    shadowCullingEligibility =
        ShadowCullingEligibility.assess(pack, options, dimension, environment);
    // The same immutable content/defaults/environment identity was used for the BSL image audit.
    // This is a bounded experiment, not a policy inferred from a pack name or shader style.
    relaxedFragmentMath =
        Boolean.getBoolean("minecraftShaders.relaxedFragmentMath")
            && shadowCullingEligibility.eligible()
            && shadowComparison == ShaderCompatibilityCompiler.ShadowComparison.HARDWARE;
    for (String property : properties.values().keySet()) {
      if (property.startsWith("size.buffer.")) throw unsupported("pack configuration", property);
    }
    // Validate properties/options before taking ownership of the native preprocessor.
    compiler = new ShaderCompatibilityCompiler(shadowComparison);
    try {
      for (ShaderProgram program : pack.programs(directory)) {
        if (program.name().startsWith("dh_") || program.name().startsWith("voxy")) continue;
        if (!properties.programEnabled(program.identifier(), definitions)) continue;
        validateProgramProperties(program);
        if (program.paths().containsKey(ShaderStage.COMPUTE))
          throw new UnsupportedOperationException(
              "Enabled compute program requires a compute-capable loader: " + program.identifier());
        if (!program.paths().containsKey(ShaderStage.VERTEX)
            || !program.paths().containsKey(ShaderStage.FRAGMENT))
          throw new ShaderPackException("Incomplete vertex/fragment pair: " + program.identifier());
        if (program.paths().size() != 2)
          throw new UnsupportedOperationException(
              "Additional shader stage in " + program.identifier());
        String vertex = pack.source(program.paths().get(ShaderStage.VERTEX), options, environment);
        String fragment =
            pack.source(program.paths().get(ShaderStage.FRAGMENT), options, environment);
        boolean screen =
            !program.name().startsWith("gbuffers_") && !program.name().equals("shadow");
        TranslatedProgram translated =
            compiler.translate(
                vertex,
                fragment,
                program.identifier(),
                screen
                    ? ShaderCompatibilityCompiler.VertexMode.FULLSCREEN
                    : ShaderCompatibilityCompiler.VertexMode.GEOMETRY);
        validateFocusSupport(translated, effectiveOptions);
        for (var sampler : translated.samplers()) {
          samplers.add(sampler.name());
          if (program.name().equals("final") || program.name().matches("composite[0-9]*"))
            postSamplers.add(sampler.name());
        }
        ShaderDirectives v = ShaderDirectives.parse(translated.preprocessedVertex());
        ShaderDirectives f = ShaderDirectives.parse(translated.preprocessedFragment());
        Map<String, String> local = new TreeMap<>(v.constants());
        merge(local, f.constants());
        for (var declaration : local.entrySet()) {
          String directive = declaration.getKey();
          // shortcut: Mipmap=false is the default and Clear=false is treated as the default
          // per-frame clear (only differs for texels no shadow pass wrote); upgrade if a pack
          // needs persistent shadow-color contents.
          if (directive.matches("shadow[Cc]olor\\d+(?:Mipmap|Clear)")
              && declaration.getValue().trim().equals("false")) continue;
          // RGBA8 is the default shadow-color format.
          if (directive.matches("shadow[Cc]olor\\d+Format")
              && declaration.getValue().trim().equals("RGBA8")) continue;
          if (directive.matches("shadow[Cc]olor\\d+(?:Format|Mipmap|Nearest|Clear|ClearColor)")
              || directive.equals("generateShadowColorMipmap"))
            throw unsupported(program.identifier(), directive);
        }
        // MipmapEnabled describes input preparation for this pass; it is not a global toggle.
        for (var constant : local.entrySet()) {
          if (!constant.getKey().endsWith("MipmapEnabled"))
            merge(constants, Map.of(constant.getKey(), constant.getValue()));
        }
        sources.put(
            program.name(),
            new Source(
                program.name(),
                vertex,
                fragment,
                translated,
                new ShaderDirectives(translated.drawBuffers(), local),
                relaxedFragmentMath));
      }
      globals = new ShaderDirectives(List.of(), constants);
    } catch (Throwable failure) {
      compiler.close();
      throw failure;
    }
  }

  private void validateProgramProperties(ShaderProgram program) {
    if (program.name().matches("setup[0-9]*"))
      throw unsupported(program.identifier(), "setup passes");
    for (String property : properties.values().keySet()) {
      if (property.equals("scale." + program.name())
          || property.equals("scale." + program.identifier())
          || property.startsWith("flip." + program.name() + ".")
          || property.startsWith("flip." + program.identifier() + "."))
        throw unsupported(program.identifier(), property);
    }
  }

  private static UnsupportedOperationException unsupported(String program, String declaration) {
    return new UnsupportedOperationException(
        "Shader pack "
            + program
            + " requires unsupported rendering declaration "
            + declaration
            + ". This loader cannot preserve that declaration's rendering behavior.");
  }

  /**
   * Do not advertise BSL's host-driven autofocus while no asynchronous center-depth reader exists.
   */
  static void validateFocusSupport(TranslatedProgram translated, Map<String, String> options) {
    if (!"true".equals(options.get("DOF")) || !"0".equals(options.get("DOF_FOCUS_MODE"))) return;
    if (usesCenterDepth(translated.preprocessedVertex())
        || usesCenterDepth(translated.preprocessedFragment())) {
      throw new UnsupportedOperationException(
          "Program "
              + translated.label()
              + " requires centerDepthSmooth autofocus, which this loader does not yet supply. "
              + "Set DOF_FOCUS_MODE=1 for the pack's depth-texture focus point, or disable DOF.");
    }
  }

  private static boolean usesCenterDepth(String preprocessed) {
    String code = COMMENTS.matcher(preprocessed).replaceAll("");
    code = UNIFORM_DECLARATION.matcher(code).replaceAll("");
    return CENTER_DEPTH.matcher(code).find();
  }

  private static void merge(Map<String, String> into, Map<String, String> values)
      throws ShaderPackException {
    for (var entry : values.entrySet()) {
      String existing = into.putIfAbsent(entry.getKey(), entry.getValue());
      if (existing != null && !existing.equals(entry.getValue()))
        throw new ShaderPackException(
            "Conflicting active pack setting "
                + entry.getKey()
                + ": "
                + existing
                + " / "
                + entry.getValue());
    }
  }

  public Source find(String name) {
    return sources.get(name);
  }

  public boolean readsSampler(String name) {
    return samplers.contains(name);
  }

  public boolean postReadsSampler(String name) {
    return postSamplers.contains(name);
  }

  public Source resolve(String name) {
    Set<String> visited = new HashSet<>();
    while (name != null && visited.add(name)) {
      Source source = sources.get(name);
      if (source != null) return source;
      name = FALLBACK.get(name);
    }
    throw new UnsupportedOperationException("Pack has no applicable geometry program: " + visited);
  }

  /** Called for every phase each frame; sources are fixed after construction, so cache it. */
  public List<Source> sequence(String prefix) {
    return sequences.computeIfAbsent(prefix, this::computeSequence);
  }

  private List<Source> computeSequence(String prefix) {
    Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "[0-9]*");
    return sources.values().stream()
        .filter(source -> pattern.matcher(source.name()).matches())
        .sorted(
            Comparator.comparingInt(
                source ->
                    source.name().equals(prefix)
                        ? 0
                        : Integer.parseInt(source.name().substring(prefix.length()))))
        .toList();
  }

  public TranslatedProgram geometry(
      Source source, ShaderCompatibilityCompiler.VertexAdapter adapter) {
    return compiler.translate(
        source.vertex(), source.fragment(), directory + "/" + source.name(), adapter);
  }

  public TranslatedProgram geometry(
      Source source,
      ShaderCompatibilityCompiler.VertexAdapter adapter,
      dev.kausik.shaders.pack.AlphaTestPolicy alphaTest) {
    return compiler.translate(
        source.vertex(),
        source.fragment(),
        directory + "/" + source.name(),
        adapter,
        alphaTest,
        EARLY_ALPHA_DEMOTE
            && shadowCullingEligibility.eligible()
            && source.name().equals("gbuffers_terrain"));
  }

  public Map<Integer, PackRenderTargets.BufferSpec> bufferSpecifications()
      throws ShaderPackException {
    Map<Integer, String> formats = globals.bufferFormats();
    Map<Integer, Boolean> clear = globals.bufferClear();
    Map<Integer, List<Float>> clearColors = globals.bufferClearColors();
    Set<Integer> mipmaps = new HashSet<>();
    Set<Integer> used = new HashSet<>();
    for (Source source : sources.values()) {
      mipmaps.addAll(source.directives().mipmapBuffers());
      used.addAll(source.translated().drawBuffers());
      for (var sampler : source.translated().samplers()) {
        if (sampler.name().matches("colortex[0-9]+|gcolor|gdepth|gnormal|composite|gaux[1-4]"))
          used.add(ShaderDirectives.colorBufferIndex(sampler.name()));
      }
    }
    used.remove(-1);
    Map<Integer, PackRenderTargets.BufferSpec> result = new TreeMap<>();
    for (int index : used) {
      List<Float> color = clearColors.get(index);
      result.put(
          index,
          new PackRenderTargets.BufferSpec(
              PackFormats.color(formats.getOrDefault(index, "RGBA8")),
              clear.getOrDefault(index, true),
              color == null
                  ? null
                  : new Vector4f(color.get(0), color.get(1), color.get(2), color.get(3)),
              mipmaps.contains(index)));
    }
    return Map.copyOf(result);
  }

  @Override
  public void close() {
    compiler.close();
  }
}

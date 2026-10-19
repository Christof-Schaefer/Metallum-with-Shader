package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import dev.kausik.shaders.pack.AlphaTestPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lowers the legacy GLSL interface used by OptiFine-format packs to RenderPearl GLSL. Conditional
 * compilation precedes interface extraction: disabled effects cannot create bindings, and
 * unsupported active features produce an error rather than silently changing the pack.
 */
public final class ShaderCompatibilityCompiler implements AutoCloseable {
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
  private static final Pattern LINE = Pattern.compile("(?m)^\\s*#line[^\\r\\n]*");
  private static final Pattern EXTENSION = Pattern.compile("(?m)^\\s*#extension[^\\r\\n]*");
  private static final Pattern DRAW =
      Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,Nn ]+)\\s*\\*/");
  private static final Pattern SENTINEL =
      Pattern.compile("const\\s+int\\s+sl_directive_(\\d+)\\s*=\\s*0\\s*;");
  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\b((?:(?:flat|smooth|noperspective|centroid|sample)\\s+)*)(uniform|varying|attribute)\\s+(?:(lowp|mediump|highp)\\s+)?(\\w+)\\s+([^;{}]+);");
  private static final Pattern NAME = Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[\\s*(\\d+)\\s*])?");
  private static final Pattern OUTPUT = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]");
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
  // Valid legacy GLSL identifiers which are C++/Metal keywords. SPIRV-Cross does not escape all
  // of these (BSL's deferred shader uses `new`), so preserve them under a private prefix.
  private static final Set<String> METAL_KEYWORDS =
      Set.of(
          "new",
          "delete",
          "operator",
          "template",
          "class",
          "namespace",
          "using",
          "this",
          "alignas",
          "alignof",
          "catch",
          "decltype",
          "explicit",
          "friend",
          "mutable",
          "noexcept",
          "nullptr",
          "private",
          "protected",
          "public",
          "reinterpret_cast",
          "static_assert",
          "thread_local",
          "throw",
          "try",
          "typeid",
          "typename",
          "virtual",
          "wchar_t");
  private static final Map<String, String> BUILTIN_UNIFORMS =
      Map.of(
          "gl_ModelViewMatrix",
          "mat4",
          "gl_ProjectionMatrix",
          "mat4",
          "gl_ModelViewProjectionMatrix",
          "mat4",
          "gl_NormalMatrix",
          "mat3",
          "gl_TextureMatrix",
          "mat4");
  private static final List<TranslatedProgram.Attribute> BUILTIN_ATTRIBUTES =
      List.of(
          new TranslatedProgram.Attribute("gl_Vertex", "sl_Vertex", "vec4", 0),
          new TranslatedProgram.Attribute("gl_Color", "sl_Color", "vec4", 1),
          new TranslatedProgram.Attribute("gl_MultiTexCoord0", "sl_MultiTexCoord0", "vec4", 2),
          new TranslatedProgram.Attribute("gl_MultiTexCoord1", "sl_MultiTexCoord1", "vec4", 3),
          new TranslatedProgram.Attribute("gl_Normal", "sl_Normal", "vec3", 4));
  private long compiler = shaderc_compiler_initialize();
  private final ShadowComparison shadowComparison;

  /** Hardware mode requires a linear, clamp-to-edge LEQUAL comparison sampler at runtime. */
  public enum ShadowComparison {
    EMULATED,
    HARDWARE
  }

  public enum VertexMode {
    GEOMETRY,
    FULLSCREEN
  }

  /** An explicit bridge from a Minecraft vertex format and vanilla draw uniforms to pack inputs. */
  public record VertexAdapter(
      String declarations,
      String initialization,
      Map<String, String> legacyExpressions,
      List<TranslatedProgram.Attribute> attributes,
      Map<String, UniformLayout.Declaration> uniforms) {
    public VertexAdapter(
        String declarations,
        String initialization,
        Map<String, String> legacyExpressions,
        List<TranslatedProgram.Attribute> attributes) {
      this(declarations, initialization, legacyExpressions, attributes, Map.of());
    }

    public VertexAdapter {
      legacyExpressions = Map.copyOf(legacyExpressions);
      attributes = List.copyOf(attributes);
      uniforms = Map.copyOf(uniforms);
    }
  }

  public ShaderCompatibilityCompiler() {
    this(ShadowComparison.EMULATED);
  }

  public ShaderCompatibilityCompiler(ShadowComparison shadowComparison) {
    this.shadowComparison = java.util.Objects.requireNonNull(shadowComparison);
    if (compiler == 0) throw new IllegalStateException("Cannot initialize shader preprocessor");
  }

  public TranslatedProgram translate(String vertex, String fragment, String label) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY);
  }

  public synchronized TranslatedProgram translate(
      String vertex, String fragment, String label, VertexMode mode) {
    return translate(vertex, fragment, label, mode, null, AlphaTestPolicy.OFF);
  }

  public TranslatedProgram translate(
      String vertex, String fragment, String label, VertexAdapter adapter) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY, adapter, AlphaTestPolicy.OFF);
  }

  public TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, VertexMode.GEOMETRY, adapter, alphaTest);
  }

  public TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest,
      boolean earlyAlphaDemote) {
    return translate(
        vertex, fragment, label, VertexMode.GEOMETRY, adapter, alphaTest, earlyAlphaDemote);
  }

  public TranslatedProgram translate(
      String vertex, String fragment, String label, VertexMode mode, AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, mode, null, alphaTest);
  }

  private synchronized TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexMode mode,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest) {
    return translate(vertex, fragment, label, mode, adapter, alphaTest, false);
  }

  private synchronized TranslatedProgram translate(
      String vertex,
      String fragment,
      String label,
      VertexMode mode,
      VertexAdapter adapter,
      AlphaTestPolicy alphaTest,
      boolean earlyAlphaDemote) {
    if (compiler == 0) throw new IllegalStateException("Shader compiler is closed");
    if (mode == VertexMode.FULLSCREEN && alphaTest.enabled())
      throw unsupported(label, "host alpha testing on a screen pass");
    if (token(vertex, "gl_FogFragCoord") || token(fragment, "gl_FogFragCoord")) {
      // Legacy GLSL 1.20 fog varying: becomes an ordinary float varying shared by both stages.
      vertex = legacyFogVarying(vertex);
      fragment = legacyFogVarying(fragment);
    }
    Preprocessed v = preprocess(vertex, shaderc_vertex_shader, label + ".vsh");
    Preprocessed f = preprocess(fragment, shaderc_fragment_shader, label + ".fsh");
    Map<String, UniformLayout.Declaration> uniforms = new LinkedHashMap<>();
    Map<String, String> samplers = new LinkedHashMap<>();
    Map<String, UniformLayout.Declaration> varyings = new LinkedHashMap<>();
    Map<String, String> varyingQualifiers = new LinkedHashMap<>();
    List<TranslatedProgram.Attribute> attributes = new ArrayList<>();
    String vertexBody =
        declarations(v.source(), true, uniforms, samplers, varyings, varyingQualifiers, attributes);
    String fragmentBody =
        declarations(
            f.source(), false, uniforms, samplers, varyings, varyingQualifiers, attributes);
    // ftransform() is a legacy fixed-function matrix transform, not a backend operation.
    vertexBody =
        vertexBody.replaceAll(
            "\\bftransform\\s*\\(\\s*\\)", "(gl_ModelViewProjectionMatrix * gl_Vertex)");
    if (adapter != null) {
      adapter.uniforms().forEach((name, declaration) -> merge(uniforms, name, declaration));
      // A single token pass prevents replacement expressions from being recursively rewritten.
      Matcher identifiers = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(vertexBody);
      StringBuffer adapted = new StringBuffer();
      while (identifiers.find()) {
        String expression = adapter.legacyExpressions().get(identifiers.group());
        if (expression != null)
          identifiers.appendReplacement(adapted, Matcher.quoteReplacement("(" + expression + ")"));
      }
      identifiers.appendTail(adapted);
      vertexBody = adapted.toString();
      for (var attribute : attributes) {
        if (token(vertexBody, attribute.legacyName())
            && !adapter.legacyExpressions().containsKey(attribute.legacyName()))
          throw unsupported(label, "vertex adapter mapping for " + attribute.legacyName());
      }
      attributes.clear();
    }
    for (var builtin :
        BUILTIN_UNIFORMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (token(vertexBody, builtin.getKey()) || token(fragmentBody, builtin.getKey())) {
        String name = builtin.getKey().replace("gl_", "sl_");
        merge(
            uniforms,
            name,
            new UniformLayout.Declaration(
                builtin.getValue(), builtin.getKey().equals("gl_TextureMatrix") ? 8 : 0));
        vertexBody = replaceToken(vertexBody, builtin.getKey(), name);
        fragmentBody = replaceToken(fragmentBody, builtin.getKey(), name);
      }
    }
    for (var attribute : BUILTIN_ATTRIBUTES) {
      if (token(fragmentBody, attribute.legacyName()))
        throw unsupported(label, "fixed-function fragment input " + attribute.legacyName());
      if (token(vertexBody, attribute.legacyName())) {
        if (adapter != null)
          throw unsupported(label, "vertex adapter mapping for " + attribute.legacyName());
        attributes.add(attribute);
        vertexBody = replaceToken(vertexBody, attribute.legacyName(), attribute.shaderName());
      }
    }
    attributes.sort(java.util.Comparator.comparingInt(TranslatedProgram.Attribute::location));
    Set<String> knownVertex =
        new LinkedHashSet<>(
            List.of(
                "gl_Position",
                "gl_VertexIndex",
                "gl_InstanceIndex",
                "gl_ClipDistance",
                "gl_PointSize"));
    Set<String> knownFragment =
        new LinkedHashSet<>(
            List.of(
                "gl_FragCoord",
                "gl_FrontFacing",
                "gl_FragDepth",
                "gl_PointCoord",
                "gl_SampleID",
                "gl_SamplePosition",
                "gl_SampleMaskIn",
                "gl_SampleMask"));
    vertexBody =
        replaceToken(
            replaceToken(vertexBody, "gl_VertexID", "gl_VertexIndex"),
            "gl_InstanceID",
            "gl_InstanceIndex");
    List<Integer> drawBuffers = f.drawBuffers();
    Matcher output = OUTPUT.matcher(fragmentBody);
    StringBuffer lowered = new StringBuffer();
    int outputCount = 0;
    while (output.find()) {
      int index = Integer.parseInt(output.group(1));
      if (index >= 8) throw unsupported(label, "more than eight simultaneous color outputs");
      outputCount = Math.max(outputCount, index + 1);
      output.appendReplacement(lowered, "sl_FragData" + index);
    }
    output.appendTail(lowered);
    fragmentBody = lowered.toString();
    if (token(fragmentBody, "gl_FragColor")) {
      fragmentBody = replaceToken(fragmentBody, "gl_FragColor", "sl_FragData0");
      outputCount = Math.max(outputCount, 1);
    }
    if (drawBuffers.isEmpty()) {
      List<Integer> inferred = new ArrayList<>();
      for (int i = 0; i < outputCount; i++) inferred.add(i);
      drawBuffers = List.copyOf(inferred);
    }
    if (outputCount > drawBuffers.size())
      throw unsupported(label, "fragment output outside declared DRAWBUFFERS");
    boolean alphaDemoted = false;
    if (alphaTest.enabled()) {
      if (outputCount == 0)
        throw unsupported(label, "host alpha testing without color output zero");
      merge(uniforms, "alphaTestRef", new UniformLayout.Declaration("float", 0));
      if (earlyAlphaDemote) {
        var demotion = EarlyAlphaDemotion.apply(fragmentBody, alphaTest);
        fragmentBody = demotion.source();
        alphaDemoted = demotion.applied();
      }
      Matcher fragmentMain = MAIN.matcher(fragmentBody);
      if (!fragmentMain.find())
        throw new IllegalArgumentException("Missing fragment main in " + label);
      fragmentBody =
          fragmentMain.replaceFirst("void sl_pack_fragment_main()")
              + "\nvoid main() { sl_pack_fragment_main(); if (!("
              + alphaTest.passCondition("sl_FragData0.a", "alphaTestRef")
              + ")) discard; }\n";
    }
    validate(vertexBody, knownVertex, label);
    validate(fragmentBody, knownFragment, label);
    UniformLayout layout = UniformLayout.of(uniforms);
    StringBuilder common = new StringBuilder("#version 450\n");
    common.append(layout.declaration());
    // Renaming the legacy sampler called `texture` avoids collision with modern texture().
    samplers.forEach(
        (name, type) ->
            common
                .append("uniform ")
                .append(
                    type.equals("sampler2DShadow") && shadowComparison == ShadowComparison.EMULATED
                        ? "sampler2D"
                        : type)
                .append(' ')
                .append(name.equals("texture") ? "sl_texture" : name)
                .append(";\n"));
    StringBuilder vin = new StringBuilder();
    if (adapter != null) vin.append(adapter.declarations()).append('\n');
    for (var attribute : attributes) {
      if (mode == VertexMode.FULLSCREEN) {
        if (attribute.legacyName().equals("gl_Vertex")) vin.append("vec4 sl_Vertex;\n");
        else if (attribute.legacyName().equals("gl_MultiTexCoord0"))
          vin.append("vec4 sl_MultiTexCoord0;\n");
        else throw unsupported(label, "fullscreen attribute " + attribute.legacyName());
      } else
        vin.append("layout(location=")
            .append(attribute.location())
            .append(") in ")
            .append(attribute.type())
            .append(' ')
            .append(attribute.shaderName())
            .append(";\n");
    }
    StringBuilder vout = new StringBuilder();
    StringBuilder fin = new StringBuilder();
    int location = 0;
    for (var varying : varyings.entrySet()) {
      String suffix =
          varying.getValue().arrayLength() > 0 ? "[" + varying.getValue().arrayLength() + "]" : "";
      String declaration = varying.getValue().type() + " " + varying.getKey() + suffix + ";\n";
      String interpolation = varyingQualifiers.getOrDefault(varying.getKey(), "");
      if (interpolation.isEmpty() && varying.getValue().type().matches("[ibu].*"))
        interpolation = "flat";
      if (!interpolation.isEmpty()) interpolation += " ";
      vout.append("layout(location=")
          .append(location)
          .append(") ")
          .append(interpolation)
          .append("out ")
          .append(declaration);
      fin.append("layout(location=")
          .append(location)
          .append(") ")
          .append(interpolation)
          .append("in ")
          .append(declaration);
      location += varyingLocations(varying.getValue());
    }
    StringBuilder fout = new StringBuilder();
    for (int i = 0; i < outputCount; i++)
      fout.append("layout(location=")
          .append(i)
          .append(") out vec4 sl_FragData")
          .append(i)
          .append(";\n");
    vertexBody = modernize(vertexBody, samplers);
    fragmentBody = modernize(fragmentBody, samplers);
    Matcher main = MAIN.matcher(vertexBody);
    if (!main.find()) throw new IllegalArgumentException("Missing vertex main in " + label);
    vertexBody = main.replaceFirst("void sl_pack_main()");
    String entry = "\nvoid main() {\n";
    if (adapter != null) entry += adapter.initialization() + "\n";
    if (mode == VertexMode.FULLSCREEN) {
      entry += "vec2 sl_uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n";
      if (attributes.stream().anyMatch(a -> a.shaderName().equals("sl_Vertex")))
        entry += "sl_Vertex = vec4(sl_uv * 2.0 - 1.0, 0.0, 1.0);\n";
      if (attributes.stream().anyMatch(a -> a.shaderName().equals("sl_MultiTexCoord0")))
        entry += "sl_MultiTexCoord0 = vec4(sl_uv, 0.0, 1.0);\n";
    }
    // Pack matrices and sampled depth remain OpenGL-style; rasterization expects [0, 1] NDC Z.
    entry += "sl_pack_main();\ngl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
    String compatibility =
        shadowComparison == ShadowComparison.HARDWARE
            ? """
            float sl_shadowCompare(sampler2DShadow s, vec3 p) { return texture(s, p); }
            vec4 sl_shadow2D(sampler2DShadow s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }
            """
            : """
            // Explicit four-tap comparison filtering preserves linear LEQUAL shadow sampling on
            // backends whose sampler interface does not expose hardware depth comparisons.
            float sl_shadowCompare(sampler2D s, vec3 p) {
              ivec2 extent = textureSize(s, 0);
              vec2 pixel = p.xy * vec2(extent) - 0.5;
              ivec2 base = ivec2(floor(pixel));
              vec2 weight = fract(pixel);
              ivec2 upper = extent - ivec2(1);
              float a = step(p.z, texelFetch(s, clamp(base, ivec2(0), upper), 0).r);
              float b = step(p.z, texelFetch(s, clamp(base + ivec2(1, 0), ivec2(0), upper), 0).r);
              float c = step(p.z, texelFetch(s, clamp(base + ivec2(0, 1), ivec2(0), upper), 0).r);
              float d = step(p.z, texelFetch(s, clamp(base + ivec2(1, 1), ivec2(0), upper), 0).r);
              return mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y);
            }
            vec4 sl_shadow2D(sampler2D s, vec3 p) { return vec4(sl_shadowCompare(s, p)); }
            """;
    TranslatedProgram translated =
        new TranslatedProgram(
            label + (alphaDemoted ? "/early_alpha_demote" : ""),
            escapeMetalKeywords(
                common + vin.toString() + vout + compatibility + vertexBody + entry),
            escapeMetalKeywords(
                (alphaDemoted
                        ? common
                            .toString()
                            .replace(
                                "#version 450\n",
                                "#version 450\n"
                                    + "#extension GL_EXT_demote_to_helper_invocation : require\n")
                        : common.toString())
                    + fin
                    + fout
                    + compatibility
                    + fragmentBody),
            layout,
            samplers.entrySet().stream()
                .map(e -> new TranslatedProgram.Sampler(e.getKey(), e.getValue()))
                .toList(),
            mode == VertexMode.FULLSCREEN
                ? List.of()
                : adapter == null ? attributes : adapter.attributes(),
            drawBuffers,
            v.metadataSource(),
            f.metadataSource());
    return mode == VertexMode.FULLSCREEN
            && Boolean.getBoolean("minecraftShaders.liftUniformInitializers")
        ? UniformInitializerLifting.apply(translated).program()
        : translated;
  }

  public static String samplerShaderName(String name) {
    return name.equals("texture")
        ? "sl_texture"
        : METAL_KEYWORDS.contains(name) ? "sl_cpp_" + name : name;
  }

  private static String escapeMetalKeywords(String source) {
    Matcher tokens = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(source);
    StringBuffer escaped = new StringBuffer();
    while (tokens.find()) {
      if (METAL_KEYWORDS.contains(tokens.group())) {
        String replacement = "sl_cpp_" + tokens.group();
        if (token(source, replacement))
          throw new UnsupportedOperationException(
              "Shader identifier collides with reserved translation name " + replacement);
        tokens.appendReplacement(escaped, replacement);
      }
    }
    tokens.appendTail(escaped);
    return escaped.toString();
  }

  private String declarations(
      String source,
      boolean vertex,
      Map<String, UniformLayout.Declaration> uniforms,
      Map<String, String> samplers,
      Map<String, UniformLayout.Declaration> varyings,
      Map<String, String> varyingQualifiers,
      List<TranslatedProgram.Attribute> attributes) {
    Matcher matcher = DECLARATION.matcher(source);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String qualifier = matcher.group(1).trim().replaceAll("\\s+", " ");
      String kind = matcher.group(2), type = matcher.group(4);
      for (String part : matcher.group(5).split(",")) {
        Matcher name = NAME.matcher(part.strip());
        if (!name.matches())
          throw new UnsupportedOperationException(
              "Unsupported " + kind + " declaration: " + matcher.group());
        String identifier = name.group(1);
        int length = name.group(2) == null ? 0 : Integer.parseInt(name.group(2));
        if (length == 0 && name.group(2) != null)
          throw new IllegalArgumentException("Zero-length array: " + identifier);
        var declaration = new UniformLayout.Declaration(type, length);
        switch (kind) {
          case "uniform" -> {
            if (type.matches("[iu]?sampler.*")) {
              if (length > 0)
                throw new UnsupportedOperationException("Sampler arrays: " + identifier);
              if (!type.matches("sampler2D|sampler2DShadow|samplerCube|[iu]sampler2D"))
                throw new UnsupportedOperationException(
                    "Unsupported sampler type " + type + " for " + identifier);
              String previous = samplers.putIfAbsent(identifier, type);
              if (previous != null && !previous.equals(type))
                throw new IllegalArgumentException("Conflicting sampler types: " + identifier);
            } else merge(uniforms, identifier, declaration);
          }
          case "varying" -> {
            merge(varyings, identifier, declaration);
            String previous = varyingQualifiers.putIfAbsent(identifier, qualifier);
            if (previous != null && !previous.equals(qualifier))
              throw new IllegalArgumentException(
                  "Conflicting interpolation qualifiers: " + identifier);
          }
          case "attribute" -> {
            // Some packs (MakeUp) declare the per-draw id uniforms as attributes; the host
            // already supplies them as uniforms.
            if (length == 0 && type.equals("int")
                && (identifier.equals("blockEntityId") || identifier.equals("entityId"))) {
              merge(uniforms, identifier, declaration);
              continue;
            }
            if (!vertex || length > 0)
              throw new UnsupportedOperationException("Unsupported attribute: " + identifier);
            int location =
                switch (identifier) {
                  case "mc_Entity" -> 5;
                  case "mc_midTexCoord" -> 6;
                  case "at_tangent" -> 7;
                  case "at_midBlock" -> 8;
                  default ->
                      throw new UnsupportedOperationException(
                          "Unknown legacy attribute " + identifier);
                };
            attributes.add(new TranslatedProgram.Attribute(identifier, identifier, type, location));
          }
          default -> throw new AssertionError(kind);
        }
      }
      matcher.appendReplacement(result, "");
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private Preprocessed preprocess(String source, int kind, String label) {
    List<List<Integer>> directives = new ArrayList<>();
    Matcher matcher = DRAW.matcher(source);
    StringBuffer marked = new StringBuffer();
    while (matcher.find()) {
      String value = matcher.group(2).replace(" ", "");
      List<Integer> targets = new ArrayList<>();
      if (matcher.group(1).equals("DRAWBUFFERS")) {
        for (char c : value.toCharArray()) targets.add(c == 'N' || c == 'n' ? -1 : c - '0');
      } else {
        for (String field : value.split(","))
          targets.add(field.equalsIgnoreCase("N") ? -1 : Integer.parseInt(field));
      }
      matcher.appendReplacement(marked, "const int sl_directive_" + directives.size() + " = 0;");
      directives.add(List.copyOf(targets));
    }
    matcher.appendTail(marked);
    List<String> metadata = new ArrayList<>();
    Matcher comment = Pattern.compile("/\\*[\\s\\S]*?\\*/").matcher(marked);
    StringBuffer constants = new StringBuffer();
    while (comment.find()) {
      if (!Pattern.compile("\\bconst\\s+(?:bool|int|float|vec4)\\b")
          .matcher(comment.group())
          .find()) continue;
      metadata.add(comment.group());
      comment.appendReplacement(
          constants, "const int sl_metadata_" + (metadata.size() - 1) + " = 0;");
    }
    comment.appendTail(constants);
    String input = VERSION.matcher(constants).replaceAll("");
    // Preprocessing resolves preprocessor directives, not legacy shader syntax. Version 450
    // lets shaderc accept Vulkan preprocessing while preserving all active legacy expressions.
    input = "#version 450\n" + EXTENSION.matcher(input).replaceAll("");
    long options = shaderc_compile_options_initialize();
    long result = 0;
    var sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(input, false);
    var labelBytes = org.lwjgl.system.MemoryUtil.memUTF8(label);
    var entryBytes = org.lwjgl.system.MemoryUtil.memUTF8("main");
    try {
      result =
          shaderc_compile_into_preprocessed_text(
              compiler, sourceBytes, kind, labelBytes, entryBytes, options);
      if (result == 0
          || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
        throw new IllegalArgumentException(
            "Preprocessing "
                + label
                + ": "
                + (result == 0 ? "no result" : shaderc_result_get_error_message(result)));
      String output =
          java.nio.charset.StandardCharsets.UTF_8
              .decode(shaderc_result_get_bytes(result))
              .toString();
      output = VERSION.matcher(LINE.matcher(output).replaceAll("")).replaceAll("");
      Matcher active = SENTINEL.matcher(output);
      List<Integer> drawBuffers = List.of();
      while (active.find()) drawBuffers = directives.get(Integer.parseInt(active.group(1)));
      output = active.replaceAll("");
      Matcher activeMetadata =
          Pattern.compile("const\\s+int\\s+sl_metadata_(\\d+)\\s*=\\s*0\\s*;").matcher(output);
      StringBuffer restored = new StringBuffer();
      while (activeMetadata.find())
        activeMetadata.appendReplacement(
            restored,
            Matcher.quoteReplacement(metadata.get(Integer.parseInt(activeMetadata.group(1)))));
      activeMetadata.appendTail(restored);
      return new Preprocessed(activeMetadata.replaceAll(""), drawBuffers, restored.toString());
    } finally {
      if (result != 0) shaderc_result_release(result);
      shaderc_compile_options_release(options);
      org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
      org.lwjgl.system.MemoryUtil.memFree(labelBytes);
      org.lwjgl.system.MemoryUtil.memFree(entryBytes);
    }
  }

  private String modernize(String source, Map<String, String> samplers) {
    Set<String> comparisonNames = new LinkedHashSet<>();
    samplers.forEach(
        (name, type) -> {
          if (type.equals("sampler2DShadow")) comparisonNames.add(name);
        });
    Matcher comparisonParameters = Pattern.compile("\\bsampler2DShadow\\s+(\\w+)").matcher(source);
    while (comparisonParameters.find()) comparisonNames.add(comparisonParameters.group(1));
    for (String name : comparisonNames)
      source =
          source.replaceAll(
              "\\btexture\\s*\\(\\s*" + Pattern.quote(name) + "\\s*,",
              "sl_shadowCompare(" + name + ",");
    if (shadowComparison == ShadowComparison.EMULATED)
      source = replaceToken(source, "sampler2DShadow", "sampler2D");
    if (samplers.containsKey("texture")) source = replaceToken(source, "texture", "sl_texture");
    for (String old : List.of("texture2D", "texture3D", "textureCube"))
      source = replaceToken(source, old, "texture");
    for (String old : List.of("texture2DLod", "texture2DLodARB", "texture3DLod", "textureCubeLod"))
      source = replaceToken(source, old, "textureLod");
    source = replaceToken(source, "texture2DGradARB", "textureGrad");
    source = replaceToken(source, "texture2DProj", "textureProj");
    source = replaceToken(source, "shadow2D", "sl_shadow2D");
    return source;
  }

  private static void validate(String source, Set<String> known, String label) {
    Matcher builtins = Pattern.compile("\\bgl_\\w+").matcher(source);
    while (builtins.find())
      if (!known.contains(builtins.group())) throw unsupported(label, builtins.group());
    if (Pattern.compile("\\b(?:[iu]?image[123]D|buffer|atomic_uint)\\b").matcher(source).find())
      throw unsupported(label, "storage images, buffers, or atomics");
  }

  private static String legacyFogVarying(String source) {
    source = replaceToken(source, "gl_FogFragCoord", "sl_FogFragCoord");
    String declaration = "varying float sl_FogFragCoord;\n";
    Matcher version = Pattern.compile("(?m)^[ \\t]*#version[^\\n]*\\n").matcher(source);
    return version.find()
        ? source.substring(0, version.end()) + declaration + source.substring(version.end())
        : declaration + source;
  }

  private static UnsupportedOperationException unsupported(String label, String feature) {
    return new UnsupportedOperationException(
        "Shader pack program " + label + " requires unsupported " + feature);
  }

  private static boolean token(String source, String token) {
    return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(source).find();
  }

  private static String replaceToken(String source, String old, String replacement) {
    return source.replaceAll(
        "\\b" + Pattern.quote(old) + "\\b", Matcher.quoteReplacement(replacement));
  }

  private static void merge(
      Map<String, UniformLayout.Declaration> map, String name, UniformLayout.Declaration value) {
    var previous = map.putIfAbsent(name, value);
    if (previous != null && !previous.equals(value))
      throw new IllegalArgumentException("Conflicting declarations: " + name);
  }

  private static int varyingLocations(UniformLayout.Declaration declaration) {
    int slots = declaration.type().startsWith("mat") ? declaration.type().charAt(3) - '0' : 1;
    return slots * Math.max(1, declaration.arrayLength());
  }

  @Override
  public synchronized void close() {
    if (compiler != 0) shaderc_compiler_release(compiler);
    compiler = 0;
  }

  private record Preprocessed(String source, List<Integer> drawBuffers, String metadataSource) {}
}

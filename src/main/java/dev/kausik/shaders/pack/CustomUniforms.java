package dev.kausik.shaders.pack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiled scalar custom-uniform expressions. One instance belongs to one active pack/world and is
 * evaluated once per frame on the render thread. Missing inputs and unsupported types are errors.
 */
public final class CustomUniforms {
  private interface Node {
    double evaluate(Evaluation evaluation) throws ShaderPackException;
  }

  private record Definition(
      String type, boolean uniform, Node expression, Set<String> references) {}

  private static final Pattern NUMBER =
      Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fF]?");
  private final Map<String, Definition> definitions;
  private final List<Smoothing> smoothing;
  private final Set<String> inputs;
  private final List<String> vec2Uniforms;

  private CustomUniforms(
      Map<String, Definition> definitions,
      List<Smoothing> smoothing,
      Set<String> inputs,
      List<String> vec2Uniforms) {
    this.vec2Uniforms = List.copyOf(vec2Uniforms);
    this.definitions = Collections.unmodifiableMap(new TreeMap<>(definitions));
    this.smoothing = List.copyOf(smoothing);
    this.inputs = Set.copyOf(inputs);
  }

  public static CustomUniforms compile(ShaderProperties properties) throws ShaderPackException {
    Map<String, Definition> definitions = new TreeMap<>();
    List<Smoothing> smoothing = new ArrayList<>();
    List<String> vec2Uniforms = new ArrayList<>();
    for (var entry : properties.values().entrySet()) {
      String[] key = entry.getKey().split("\\.", 3);
      if (key.length != 3 || (!key[0].equals("variable") && !key[0].equals("uniform"))) continue;
      if (key[1].equals("vec2")) {
        // A vec2 is stored as two scalar definitions "<name>.x" and "<name>.y"; the dot also lets
        // other expressions read the components.
        if (!key[2].matches("[A-Za-z_][A-Za-z_0-9]*"))
          throw new ShaderPackException("Invalid custom uniform name: " + key[2]);
        String[] parts = splitVec2(entry.getValue(), key[2]);
        String[] suffix = {".x", ".y"};
        for (int i = 0; i < 2; i++) {
          Parser parser = new Parser(parts[i], key[2] + suffix[i], smoothing);
          Node expression = parser.parse();
          if (definitions.putIfAbsent(
                  key[2] + suffix[i],
                  new Definition(
                      "float",
                      key[0].equals("uniform"),
                      expression,
                      Set.copyOf(parser.references)))
              != null) {
            throw new ShaderPackException("Duplicate custom uniform or variable: " + key[2]);
          }
        }
        if (key[0].equals("uniform")) vec2Uniforms.add(key[2]);
        continue;
      }
      if (!Set.of("float", "int", "bool").contains(key[1])) {
        throw new ShaderPackException(
            "Unsupported custom uniform type " + key[1] + " for " + key[2]);
      }
      if (!key[2].matches("[A-Za-z_][A-Za-z_0-9]*"))
        throw new ShaderPackException("Invalid custom uniform name: " + key[2]);
      if (definitions.size() >= 512 || entry.getValue().length() > 64 * 1024) {
        throw new ShaderPackException("Custom-uniform expression limit exceeded");
      }
      Parser parser = new Parser(entry.getValue(), key[2], smoothing);
      Node expression = parser.parse();
      if (definitions.putIfAbsent(
              key[2],
              new Definition(
                  key[1], key[0].equals("uniform"), expression, Set.copyOf(parser.references)))
          != null) {
        throw new ShaderPackException("Duplicate custom uniform or variable: " + key[2]);
      }
    }
    Set<String> inputs = new HashSet<>();
    Set<String> checked = new HashSet<>();
    for (String name : definitions.keySet())
      validateReferences(name, definitions, new ArrayDeque<>(), checked, inputs);
    return new CustomUniforms(definitions, smoothing, inputs, vec2Uniforms);
  }

  /** Splits "vec2(a, b)" at its single top-level comma. */
  private static String[] splitVec2(String value, String name) throws ShaderPackException {
    String text = value.trim();
    if (!text.startsWith("vec2(") || !text.endsWith(")"))
      throw new ShaderPackException("vec2 custom uniform must be vec2(x, y): " + name);
    String inner = text.substring(5, text.length() - 1);
    int depth = 0;
    int comma = -1;
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth--;
      else if (c == ',' && depth == 0) {
        if (comma >= 0) throw new ShaderPackException("vec2 needs exactly two components: " + name);
        comma = i;
      }
      if (depth < 0) throw new ShaderPackException("Unbalanced parentheses in " + name);
    }
    if (comma < 0 || depth != 0)
      throw new ShaderPackException("vec2 needs exactly two components: " + name);
    return new String[] {inner.substring(0, comma), inner.substring(comma + 1)};
  }

  /** vec2 uniforms assembled from the result of {@link #evaluate}. */
  public Map<String, float[]> vec2Values(Map<String, Double> evaluated) {
    Map<String, float[]> result = new HashMap<>();
    for (String name : vec2Uniforms)
      result.put(
          name, new float[] {evaluated.get(name + ".x").floatValue(), evaluated.get(name + ".y").floatValue()});
    return result;
  }

  private static void validateReferences(
      String name,
      Map<String, Definition> definitions,
      ArrayDeque<String> path,
      Set<String> checked,
      Set<String> inputs)
      throws ShaderPackException {
    if (checked.contains(name)) return;
    if (path.contains(name) || path.size() >= 128)
      throw new ShaderPackException("Custom uniform dependency cycle: " + path + " -> " + name);
    path.push(name);
    for (String referenced : definitions.get(name).references()) {
      if (definitions.containsKey(referenced))
        validateReferences(referenced, definitions, path, checked, inputs);
      else inputs.add(referenced);
    }
    path.pop();
    checked.add(name);
  }

  public Set<String> requiredInputs() {
    return inputs;
  }

  public Map<String, Double> evaluate(Map<String, Double> scalarInputs, double deltaSeconds)
      throws ShaderPackException {
    if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0)
      throw new ShaderPackException("Invalid custom uniform frame duration");
    Evaluation evaluation = new Evaluation(scalarInputs, deltaSeconds);
    Map<String, Double> result = new TreeMap<>();
    for (var entry : definitions.entrySet()) {
      if (entry.getValue().uniform()) result.put(entry.getKey(), evaluation.value(entry.getKey()));
    }
    return Collections.unmodifiableMap(result);
  }

  public void reset() {
    smoothing.forEach(state -> state.initialized = false);
  }

  private final class Evaluation {
    final Map<String, Double> inputs;
    final double deltaSeconds;
    final Map<String, Double> values = new HashMap<>();

    Evaluation(Map<String, Double> inputs, double deltaSeconds) {
      this.inputs = inputs;
      this.deltaSeconds = deltaSeconds;
    }

    double value(String name) throws ShaderPackException {
      Double cached = values.get(name);
      if (cached != null) return cached;
      Definition definition = definitions.get(name);
      double value;
      if (definition == null) {
        Double input = inputs.get(name);
        if (input == null) throw new ShaderPackException("Missing custom uniform input: " + name);
        value = input;
      } else {
        value = definition.expression().evaluate(this);
        if (definition.type().equals("int")) value = (int) value;
        if (definition.type().equals("bool")) value = value != 0 ? 1 : 0;
      }
      if (!Double.isFinite(value))
        throw new ShaderPackException("Non-finite custom uniform value: " + name);
      values.put(name, value);
      return value;
    }
  }

  private static final class Smoothing {
    boolean initialized;
    double previous;

    double evaluate(double target, double up, double down, double seconds)
        throws ShaderPackException {
      if (!Double.isFinite(target)
          || !Double.isFinite(up)
          || !Double.isFinite(down)
          || up < 0
          || down < 0) {
        throw new ShaderPackException("Invalid smooth value or half-life");
      }
      if (!initialized) {
        initialized = true;
        previous = target;
      }
      double halfLifeTicks = target > previous ? up : down;
      // Iris documents custom-uniform smooth durations as half-life in ticks, at 20 ticks/second.
      double fraction =
          halfLifeTicks == 0 ? 1 : -Math.expm1(-Math.log(2) * seconds * 20 / halfLifeTicks);
      previous += (target - previous) * fraction;
      return previous;
    }
  }

  private static final class Parser {
    private static final String[][] OPERATORS = {
      {"||"}, {"&&"}, {"==", "!="}, {"<=", ">=", "<", ">"}, {"+", "-"}, {"*", "/", "%"}
    };
    private final String source;
    private final String label;
    private final List<Smoothing> smoothing;
    final Set<String> references = new HashSet<>();
    private int position;
    private int nesting;

    Parser(String source, String label, List<Smoothing> smoothing) {
      this.source = source;
      this.label = label;
      this.smoothing = smoothing;
    }

    Node parse() throws ShaderPackException {
      Node node = binary(0);
      whitespace();
      if (position != source.length()) throw error("Unexpected expression syntax");
      return node;
    }

    private Node binary(int level) throws ShaderPackException {
      if (level == OPERATORS.length) return unary();
      Node left = binary(level + 1);
      while (true) {
        String matched = null;
        for (String operator : OPERATORS[level]) {
          if (take(operator)) {
            matched = operator;
            break;
          }
        }
        if (matched == null) return left;
        Node first = left;
        Node second = binary(level + 1);
        String operator = matched;
        left =
            switch (operator) {
              case "&&" ->
                  evaluation ->
                      first.evaluate(evaluation) != 0 && second.evaluate(evaluation) != 0 ? 1 : 0;
              case "||" ->
                  evaluation ->
                      first.evaluate(evaluation) != 0 || second.evaluate(evaluation) != 0 ? 1 : 0;
              default ->
                  evaluation ->
                      calculate(operator, first.evaluate(evaluation), second.evaluate(evaluation));
            };
      }
    }

    private Node unary() throws ShaderPackException {
      if (++nesting > 128) throw error("Expression nesting limit exceeded");
      try {
        if (take("!")) {
          Node child = unary();
          return evaluation -> child.evaluate(evaluation) == 0 ? 1 : 0;
        }
        if (take("-")) {
          Node child = unary();
          return evaluation -> -child.evaluate(evaluation);
        }
        if (take("+")) return unary();
        if (take("(")) {
          Node child = binary(0);
          if (!take(")")) throw error("Expected closing parenthesis");
          return child;
        }
        whitespace();
        Matcher number = NUMBER.matcher(source).region(position, source.length());
        if (number.lookingAt()) {
          double value = Double.parseDouble(number.group().replaceFirst("[fF]$", ""));
          position = number.end();
          return evaluation -> value;
        }
        int start = position;
        if (position < source.length()
            && (Character.isLetter(source.charAt(position)) || source.charAt(position) == '_')) {
          position++;
          while (position < source.length()
              && (Character.isLetterOrDigit(source.charAt(position))
                  || source.charAt(position) == '_'
                  || source.charAt(position) == '.')) position++;
        }
        if (start == position) throw error("Expected number, variable, or function");
        String name = source.substring(start, position);
        if (take("(")) {
          List<Node> arguments = new ArrayList<>();
          if (!take(")")) {
            do {
              arguments.add(binary(0));
            } while (take(","));
            if (!take(")")) throw error("Expected closing function parenthesis");
          }
          return function(name, arguments);
        }
        return switch (name) {
          case "pi" -> evaluation -> Math.PI;
          case "true" -> evaluation -> 1;
          case "false" -> evaluation -> 0;
          default -> {
            references.add(name);
            yield evaluation -> evaluation.value(name);
          }
        };
      } finally {
        nesting--;
      }
    }

    private Node function(String name, List<Node> args) throws ShaderPackException {
      int count = args.size();
      if (name.equals("if") || name.equals("ifb")) {
        if (count < 3 || count % 2 != 1)
          throw error("if requires condition/value pairs and a fallback");
        return evaluation -> {
          for (int i = 0; i < args.size() - 1; i += 2) {
            if (args.get(i).evaluate(evaluation) != 0) return args.get(i + 1).evaluate(evaluation);
          }
          return args.getLast().evaluate(evaluation);
        };
      }
      if (name.equals("smooth")) {
        if (count < 1 || count > 4) throw error("smooth requires one to four arguments");
        Smoothing state = new Smoothing();
        smoothing.add(state);
        // Four arguments unambiguously contain the optional legacy ID. State belongs to the call,
        // so packs reusing legacy IDs in independent expressions cannot corrupt one another.
        int offset = count == 4 ? 1 : 0;
        return evaluation -> {
          double target = args.get(offset).evaluate(evaluation);
          double up = count > offset + 1 ? args.get(offset + 1).evaluate(evaluation) : 1;
          double down = count > offset + 2 ? args.get(offset + 2).evaluate(evaluation) : up;
          return state.evaluate(target, up, down, evaluation.deltaSeconds);
        };
      }
      int minimum;
      int maximum;
      switch (name) {
        case "sin",
            "cos",
            "tan",
            "asin",
            "acos",
            "exp",
            "exp2",
            "exp10",
            "log2",
            "log10",
            "sqrt",
            "abs",
            "sign",
            "signum",
            "floor",
            "ceil",
            "frac",
            "round",
            "torad",
            "radians",
            "todeg",
            "degrees" ->
            minimum = maximum = 1;
        case "atan", "log" -> {
          minimum = 1;
          maximum = 2;
        }
        case "atan2", "pow", "fmod", "edge" -> minimum = maximum = 2;
        case "clamp", "mix", "lerp", "between", "equals" -> minimum = maximum = 3;
        case "min", "max", "in" -> {
          minimum = 2;
          maximum = Integer.MAX_VALUE;
        }
        default -> throw error("Unsupported custom uniform function " + name);
      }
      if (count < minimum || count > maximum) throw error("Wrong argument count for " + name);
      // Expressions are render-thread confined. Reuse argument storage to avoid per-function,
      // per-frame garbage in packs with many biome and lighting expressions.
      double[] values = new double[args.size()];
      return evaluation -> {
        for (int i = 0; i < values.length; i++) values[i] = args.get(i).evaluate(evaluation);
        return call(name, values);
      };
    }

    private static double calculate(String operator, double a, double b) {
      return switch (operator) {
        case "+" -> a + b;
        case "-" -> a - b;
        case "*" -> a * b;
        case "/" -> a / b;
        case "%" -> a % b;
        case "==" -> a == b ? 1 : 0;
        case "!=" -> a != b ? 1 : 0;
        case "<" -> a < b ? 1 : 0;
        case ">" -> a > b ? 1 : 0;
        case "<=" -> a <= b ? 1 : 0;
        case ">=" -> a >= b ? 1 : 0;
        default -> throw new AssertionError(operator);
      };
    }

    private static double call(String name, double[] v) {
      double x = v[0];
      return switch (name) {
        case "sin" -> Math.sin(x);
        case "cos" -> Math.cos(x);
        case "tan" -> Math.tan(x);
        case "asin" -> Math.asin(x);
        case "acos" -> Math.acos(x);
        case "atan", "atan2" -> v.length == 1 ? Math.atan(x) : Math.atan2(x, v[1]);
        case "exp" -> Math.exp(x);
        case "exp2" -> Math.pow(2, x);
        case "exp10" -> Math.pow(10, x);
        case "log" -> v.length == 1 ? Math.log(x) : Math.log(v[1]) / Math.log(x);
        case "log2" -> Math.log(x) / Math.log(2);
        case "log10" -> Math.log10(x);
        case "sqrt" -> Math.sqrt(x);
        case "pow" -> Math.pow(x, v[1]);
        case "abs" -> Math.abs(x);
        case "sign", "signum" -> Math.signum(x);
        case "floor" -> Math.floor(x);
        case "ceil" -> Math.ceil(x);
        case "frac" -> x - Math.floor(x);
        case "round" -> Math.floor(x + .5);
        case "torad", "radians" -> Math.toRadians(x);
        case "todeg", "degrees" -> Math.toDegrees(x);
        case "fmod" -> x - v[1] * Math.floor(x / v[1]);
        case "edge" -> v[1] < x ? 0 : 1;
        case "clamp" -> Math.max(v[1], Math.min(v[2], x));
        case "mix" -> x + (v[1] - x) * v[2];
        case "lerp" -> v[1] + (v[2] - v[1]) * x;
        case "between" -> x >= v[1] && x <= v[2] ? 1 : 0;
        case "equals" -> Math.abs(x - v[1]) <= v[2] ? 1 : 0;
        case "min", "max" -> {
          double result = x;
          for (int i = 1; i < v.length; i++)
            result = name.equals("min") ? Math.min(result, v[i]) : Math.max(result, v[i]);
          yield result;
        }
        case "in" -> {
          boolean found = false;
          for (int i = 1; i < v.length; i++) found |= x == v[i];
          yield found ? 1 : 0;
        }
        default -> throw new AssertionError(name);
      };
    }

    private boolean take(String token) {
      whitespace();
      if (!source.startsWith(token, position)) return false;
      if (token.length() == 1 && position + 1 < source.length()) {
        char next = source.charAt(position + 1);
        if ((token.equals("<") || token.equals(">") || token.equals("!")) && next == '=')
          return false;
      }
      position += token.length();
      return true;
    }

    private void whitespace() {
      while (position < source.length() && Character.isWhitespace(source.charAt(position)))
        position++;
    }

    private ShaderPackException error(String message) {
      return new ShaderPackException(message + " in " + label + " at column " + (position + 1));
    }
  }
}

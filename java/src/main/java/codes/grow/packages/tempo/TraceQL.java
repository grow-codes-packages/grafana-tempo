package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.ValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable TraceQL query builder with escaping. Every method returns a new builder.
 *
 * <pre>{@code
 * String q = TraceQL.span(Map.of("resource.service.name", "api"))
 *     .and("duration", ">", "200ms")
 *     .select("span.http.status_code")
 *     .toString();
 * // { resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)
 * }</pre>
 *
 * <p>Field names need a scope ({@code resource.}, {@code span.}, {@code event.}, {@code link.},
 * {@code instrumentation.}, or {@code .} for span or resource) or must be an intrinsic ({@code
 * name}, {@code status}, {@code kind}, {@code duration}, {@code span:*}, {@code trace:*}, …).
 * {@code duration} is written as {@code span:duration}. Attribute names with spaces or other
 * special characters are quoted after the scope ({@code span."grow weird.key"}). Unknown scopes,
 * intrinsics and operators throw {@link ValidationException} at once, because some Tempo endpoints
 * silently ignore an invalid query.
 *
 * <p>Values: strings are quoted and escaped ({@code \} {@code "} and control characters), except
 * duration literals on duration fields ({@code 200ms}), {@code ok|error|unset} on {@code status}
 * and span kinds on {@code kind}. Integers, decimals ({@code 1.5}, {@code 2.0}) and booleans are
 * literals; a {@link Duration} becomes the largest exact unit ({@code 1500ms}, {@code 2s}).
 */
public final class TraceQL {
  /** Comparison operators accepted by {@link #and} and {@link #or}. */
  public static final List<String> OPERATORS = List.of("=", "!=", ">", ">=", "<", "<=", "=~", "!~");

  /** Span-set operators accepted by {@link #structural(String, TraceQL)}. */
  public static final List<String> STRUCTURAL_OPERATORS =
      List.of(">", ">>", "<", "<<", "~", "&&", "||");

  /** Attribute scopes ({@code ""} is the unscoped {@code .name} form). */
  public static final List<String> SCOPES =
      List.of("resource", "span", "event", "link", "instrumentation", "");

  private static final Set<String> BARE_INTRINSICS =
      Set.of(
          "name",
          "status",
          "statusMessage",
          "kind",
          "duration",
          "rootName",
          "rootServiceName",
          "traceDuration",
          "childCount",
          "nestedSetLeft",
          "nestedSetRight",
          "nestedSetParent");
  private static final Pattern SCOPED_INTRINSIC =
      Pattern.compile("^(span|trace|event|link|instrumentation):[A-Za-z][A-Za-z0-9_]*$");
  private static final Pattern SCOPED_ATTRIBUTE =
      Pattern.compile("^(resource|span|event|link|instrumentation)?\\.(.*)$", Pattern.DOTALL);
  private static final Pattern PLAIN_NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.]*$");
  private static final Pattern DURATION_LITERAL =
      Pattern.compile("^\\d+(\\.\\d+)?(ns|us|µs|ms|s|m|h)$");
  private static final Set<String> DURATION_FIELDS =
      Set.of("span:duration", "trace:duration", "traceDuration");
  private static final Set<String> STATUS_FIELDS = Set.of("status", "span:status");
  private static final Set<String> STATUS_WORDS = Set.of("ok", "error", "unset");
  private static final Set<String> KIND_FIELDS = Set.of("kind", "span:kind");
  private static final Set<String> KIND_WORDS =
      Set.of("unspecified", "internal", "server", "client", "producer", "consumer");

  /** One span-set filter and the structural operator before it. */
  private static final class Part {
    final String op;
    final List<String> tokens;

    Part(String op, List<String> tokens) {
      this.op = op;
      this.tokens = List.copyOf(tokens);
    }
  }

  private final List<Part> parts;
  private final List<String> selects;

  private TraceQL(List<Part> parts, List<String> selects) {
    this.parts = List.copyOf(parts);
    this.selects = List.copyOf(selects);
  }

  /**
   * An empty span-set filter ({@code {}}, every span).
   *
   * @return builder
   */
  public static TraceQL span() {
    return new TraceQL(List.of(new Part("", List.of())), List.of());
  }

  /**
   * A span-set filter with one {@code field = value} condition per entry, joined with {@code &&}.
   * The order is the map's iteration order for a {@link LinkedHashMap} or {@link SortedMap}, else
   * sorted by field name (so {@code Map.of(…)} renders the same text every time).
   *
   * @param filters field → value
   * @return builder
   * @throws ValidationException for an invalid field or value
   */
  public static TraceQL span(Map<String, ?> filters) {
    Objects.requireNonNull(filters, "filters");
    Map<String, ?> ordered =
        filters instanceof LinkedHashMap || filters instanceof SortedMap
            ? filters
            : new TreeMap<>(filters);
    TraceQL q = span();
    for (Map.Entry<String, ?> e : ordered.entrySet()) {
      q = q.and(e.getKey(), "=", e.getValue());
    }
    return q;
  }

  /**
   * A span-set filter with one condition: {@code { field op value }}.
   *
   * @param field field name
   * @param op comparison operator
   * @param value value
   * @return builder
   */
  public static TraceQL where(String field, String op, Object value) {
    return span().and(field, op, value);
  }

  /**
   * Renders a field name: validates the scope or intrinsic and quotes odd attribute names.
   *
   * @param name field name, e.g. {@code resource.service.name}, {@code duration}, {@code span.my
   *     key}
   * @return the TraceQL identifier
   * @throws ValidationException for an empty name, unknown scope or intrinsic
   */
  public static String field(String name) {
    String f = name == null ? "" : name.trim();
    if (f.isEmpty()) {
      throw new ValidationException("traceql: field name must not be empty");
    }
    if (f.equals("duration")) {
      return "span:duration";
    }
    if (BARE_INTRINSICS.contains(f) || SCOPED_INTRINSIC.matcher(f).matches()) {
      return f;
    }
    Matcher m = SCOPED_ATTRIBUTE.matcher(f);
    if (m.matches()) {
      String scope = m.group(1) == null ? "" : m.group(1);
      String rest = m.group(2);
      if (rest.startsWith("\"")) {
        if (rest.length() >= 2 && rest.endsWith("\"") && !rest.endsWith("\\\"")) {
          return f;
        }
        throw new ValidationException("traceql: invalid quoted attribute name in \"" + f + "\"");
      }
      if (rest.isEmpty()) {
        throw new ValidationException("traceql: attribute name missing after scope in " + f);
      }
      if (PLAIN_NAME.matcher(rest).matches()) {
        return f;
      }
      return scope + "." + quote(rest);
    }
    throw new ValidationException(
        "traceql: unknown scope or intrinsic in \""
            + f
            + "\" (use resource., span., event., link., instrumentation., . or an intrinsic)");
  }

  /**
   * Quotes text as a TraceQL string literal: {@code "…"} with {@code \}, {@code "}, newline, tab
   * and carriage return escaped; other characters (including Unicode) pass through.
   *
   * @param text text
   * @return quoted literal
   */
  public static String quote(String text) {
    StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '"':
          sb.append("\\\"");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\r':
          sb.append("\\r");
          break;
        default:
          sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  /**
   * Renders a value for a field already rendered by {@link #field(String)}.
   *
   * @param field rendered field
   * @param value string, number, boolean or {@link Duration}
   * @return literal
   * @throws ValidationException for a non-finite number or another value type
   */
  public static String value(String field, Object value) {
    if (value instanceof String) {
      String s = (String) value;
      if (DURATION_FIELDS.contains(field) && DURATION_LITERAL.matcher(s).matches()) {
        return s;
      }
      if (STATUS_FIELDS.contains(field) && STATUS_WORDS.contains(s)) {
        return s;
      }
      if (KIND_FIELDS.contains(field) && KIND_WORDS.contains(s)) {
        return s;
      }
      return quote(s);
    }
    if (value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return value.toString();
    }
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new ValidationException("traceql: numeric values must be finite");
      }
      return decimal(BigDecimal.valueOf(d));
    }
    if (value instanceof BigDecimal) {
      return decimal((BigDecimal) value);
    }
    if (value instanceof Duration) {
      return duration((Duration) value);
    }
    throw new ValidationException(
        "traceql: values must be strings, numbers, booleans or durations (field " + field + ")");
  }

  private static String decimal(BigDecimal d) {
    String s = d.toPlainString();
    return s.contains(".") ? s : s + ".0";
  }

  /**
   * Renders a duration with the largest exact unit ({@code 1h}, {@code 90s}, {@code 200ms}, {@code
   * 1500us}, {@code 7ns}).
   *
   * @param d duration
   * @return TraceQL duration literal
   */
  public static String duration(Duration d) {
    long nanos = d.toNanos();
    if (nanos == 0) {
      return "0s";
    }
    long[] units = {3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
    String[] names = {"h", "m", "s", "ms", "us"};
    for (int i = 0; i < units.length; i++) {
      if (nanos % units[i] == 0) {
        return (nanos / units[i]) + names[i];
      }
    }
    return nanos + "ns";
  }

  private TraceQL add(String join, String field, String op, Object value) {
    if (!OPERATORS.contains(op)) {
      throw new ValidationException("traceql: unsupported operator \"" + op + "\"");
    }
    String name = field(field);
    String literal;
    if (op.equals("=~") || op.equals("!~")) {
      if (!(value instanceof String)) {
        throw new ValidationException("traceql: operator " + op + " needs a string value");
      }
      literal = quote((String) value);
    } else {
      literal = value(name, value);
    }
    List<Part> out = new ArrayList<>(parts);
    Part last = out.remove(out.size() - 1);
    List<String> tokens = new ArrayList<>(last.tokens);
    if (!tokens.isEmpty()) {
      tokens.add(join);
    }
    tokens.add(name + " " + op + " " + literal);
    out.add(new Part(last.op, tokens));
    return new TraceQL(out, selects);
  }

  /**
   * Adds {@code && field op value} to the last span-set filter.
   *
   * @param field field name
   * @param op one of {@link #OPERATORS}
   * @param value string, number, boolean or {@link Duration} (regex operators need a string)
   * @return new builder
   * @throws ValidationException for an invalid field, operator or value
   */
  public TraceQL and(String field, String op, Object value) {
    return add("&&", field, op, value);
  }

  /**
   * Adds {@code || field op value} to the last span-set filter.
   *
   * @param field field name
   * @param op one of {@link #OPERATORS}
   * @param value value
   * @return new builder
   */
  public TraceQL or(String field, String op, Object value) {
    return add("||", field, op, value);
  }

  /**
   * Joins another query's span sets with a span-set operator: {@code >} (child), {@code >>}
   * (descendant), {@code <} (parent), {@code <<} (ancestor), {@code ~} (sibling), {@code &&} or
   * {@code ||}. The other query's {@code select()} is ignored.
   *
   * @param op one of {@link #STRUCTURAL_OPERATORS}
   * @param other right-hand query
   * @return new builder
   */
  public TraceQL structural(String op, TraceQL other) {
    if (!STRUCTURAL_OPERATORS.contains(op)) {
      throw new ValidationException("traceql: unknown span-set operator \"" + op + "\"");
    }
    Objects.requireNonNull(other, "other");
    List<Part> out = new ArrayList<>(parts);
    for (int i = 0; i < other.parts.size(); i++) {
      Part p = other.parts.get(i);
      out.add(new Part(i == 0 ? op : p.op, p.tokens));
    }
    return new TraceQL(out, selects);
  }

  /**
   * {@code structural(">", other)}: spans of other that are direct children.
   *
   * @param other child query
   * @return new builder
   */
  public TraceQL child(TraceQL other) {
    return structural(">", other);
  }

  /**
   * {@code structural(">>", other)}: spans of other that are descendants.
   *
   * @param other descendant query
   * @return new builder
   */
  public TraceQL descendant(TraceQL other) {
    return structural(">>", other);
  }

  /**
   * {@code structural("<", other)}: spans of other that are direct parents.
   *
   * @param other parent query
   * @return new builder
   */
  public TraceQL parent(TraceQL other) {
    return structural("<", other);
  }

  /**
   * {@code structural("<<", other)}: spans of other that are ancestors.
   *
   * @param other ancestor query
   * @return new builder
   */
  public TraceQL ancestor(TraceQL other) {
    return structural("<<", other);
  }

  /**
   * {@code structural("~", other)}: spans of other that are siblings.
   *
   * @param other sibling query
   * @return new builder
   */
  public TraceQL sibling(TraceQL other) {
    return structural("~", other);
  }

  /**
   * Adds fields to {@code | select(…)} so search returns them on each matched span.
   *
   * @param fields at least one field name
   * @return new builder
   * @throws ValidationException when no field is given or a name is invalid
   */
  public TraceQL select(String... fields) {
    if (fields.length == 0) {
      throw new ValidationException("traceql: select() needs a field");
    }
    List<String> out = new ArrayList<>(selects);
    for (String f : fields) {
      out.add(field(f));
    }
    return new TraceQL(parts, out);
  }

  /**
   * The TraceQL text (same as {@link #toString()}).
   *
   * @return query text
   */
  public String build() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      Part p = parts.get(i);
      if (i > 0) {
        sb.append(' ').append(p.op).append(' ');
      }
      sb.append(p.tokens.isEmpty() ? "{}" : "{ " + String.join(" ", p.tokens) + " }");
    }
    if (!selects.isEmpty()) {
      sb.append(" | select(").append(String.join(", ", selects)).append(')');
    }
    return sb.toString();
  }

  /**
   * The selected fields.
   *
   * @return rendered field names
   */
  public List<String> selects() {
    return Collections.unmodifiableList(selects);
  }

  @Override
  public String toString() {
    return build();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TraceQL && ((TraceQL) o).build().equals(build());
  }

  @Override
  public int hashCode() {
    return build().hashCode();
  }

  /**
   * Light client-side check of a TraceQL text: it must start with an opening brace, and quotes,
   * braces and parentheses must balance. Tempo's tag endpoints ignore an invalid query and return
   * unfiltered values, so the SDK refuses obviously broken queries there.
   *
   * @param query query text
   * @param operation operation name for the error message
   * @return the trimmed query
   * @throws ValidationException when the query is malformed
   */
  public static String check(String query, String operation) {
    String text = query == null ? "" : query.trim();
    if (!text.startsWith("{")) {
      throw new ValidationException(operation + ": TraceQL query must start with \"{\"");
    }
    Deque<Character> stack = new ArrayDeque<>();
    boolean inString = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '{') {
        stack.push('}');
      } else if (c == '(') {
        stack.push(')');
      } else if (c == '}' || c == ')') {
        Character open = stack.poll();
        if (open == null || open != c) {
          throw new ValidationException(operation + ": unbalanced TraceQL query");
        }
      }
    }
    if (inString || !stack.isEmpty()) {
      throw new ValidationException(operation + ": unbalanced TraceQL query");
    }
    return text;
  }
}

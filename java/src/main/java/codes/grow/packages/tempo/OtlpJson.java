package codes.grow.packages.tempo;

import codes.grow.packages.tempo.tracing.query.Span;
import codes.grow.packages.tempo.tracing.query.Traces;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts the OTLP JSON that Tempo returns for a trace by id (protobuf JSON: ids in base64, times
 * as decimal nanosecond strings, enums as names) into the normalized {@link Span} model.
 */
public final class OtlpJson {
  private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]+$");
  private static final Pattern ZEROS = Pattern.compile("^0+$");
  private static final BigInteger THOUSAND = BigInteger.valueOf(1000);
  private static final Span.Kind[] KINDS = {
    Span.Kind.UNSPECIFIED,
    Span.Kind.INTERNAL,
    Span.Kind.SERVER,
    Span.Kind.CLIENT,
    Span.Kind.PRODUCER,
    Span.Kind.CONSUMER
  };

  private OtlpJson() {}

  /**
   * Converts an id from the trace-by-id JSON (base64 protobuf bytes) to lower-case hex, left-padded
   * to {@code hexLength}. An id that already is hex of that length is kept.
   *
   * @param id base64 or hex id, or {@code null}
   * @param hexLength 32 for trace ids, 16 for span ids
   * @return the hex id, or {@code null} when empty, all zero or not decodable
   */
  public static String idToHex(String id, int hexLength) {
    if (id == null || id.isEmpty()) {
      return null;
    }
    String hex;
    if (id.length() == hexLength && HEX.matcher(id).matches()) {
      hex = id.toLowerCase(Locale.ROOT);
    } else {
      try {
        hex = HexFormat.of().formatHex(Base64.getDecoder().decode(id));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }
    if (hex.isEmpty() || hex.length() > hexLength || ZEROS.matcher(hex).matches()) {
      return null;
    }
    return "0".repeat(hexLength - hex.length()) + hex;
  }

  /**
   * Converts a decimal nanosecond value (string or number node) to whole microseconds.
   *
   * @param node value node or {@code null}
   * @return microseconds, 0 when missing or not a number
   */
  public static long nanosToMicros(JsonNode node) {
    if (node == null || node.isNull()) {
      return 0;
    }
    if (node.isNumber()) {
      return node.bigIntegerValue().divide(THOUSAND).longValue();
    }
    String text = node.asText().trim();
    if (text.isEmpty()) {
      return 0;
    }
    try {
      return new BigInteger(text).divide(THOUSAND).longValue();
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Reads a 64-bit integer that protobuf JSON may send as a string or a number.
   *
   * @param node value node or {@code null}
   * @return the value, 0 when missing or not a number
   */
  public static long longValue(JsonNode node) {
    if (node == null || node.isNull()) {
      return 0;
    }
    if (node.isNumber()) {
      return node.asLong();
    }
    try {
      return new BigInteger(node.asText().trim()).longValue();
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Converts an OTLP {@code AnyValue}: strings, booleans, {@code Long} (intValue), {@code Double}
   * (doubleValue), lists (arrayValue), maps (kvlistValue) and base64 text (bytesValue).
   *
   * @param value AnyValue node or {@code null}
   * @return the value, or {@code null} for an empty value
   */
  public static Object fromAnyValue(JsonNode value) {
    if (value == null || !value.isObject()) {
      return null;
    }
    if (value.has("stringValue")) {
      return value.get("stringValue").asText();
    }
    if (value.has("boolValue")) {
      return value.get("boolValue").asBoolean();
    }
    if (value.has("intValue")) {
      return longValue(value.get("intValue"));
    }
    if (value.has("doubleValue")) {
      return value.get("doubleValue").asDouble();
    }
    if (value.has("arrayValue")) {
      List<Object> out = new ArrayList<>();
      for (JsonNode v : value.get("arrayValue").path("values")) {
        Object converted = fromAnyValue(v);
        if (converted != null) {
          out.add(converted);
        }
      }
      return out;
    }
    if (value.has("kvlistValue")) {
      return fromKeyValues(value.get("kvlistValue").path("values"));
    }
    if (value.has("bytesValue")) {
      return value.get("bytesValue").asText();
    }
    return null;
  }

  /**
   * Converts an OTLP attribute list ({@code [{key, value}]}) to a map; empty values are dropped.
   *
   * @param list array node or {@code null}
   * @return attributes in list order
   */
  public static Map<String, Object> fromKeyValues(JsonNode list) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (list == null) {
      return out;
    }
    for (JsonNode kv : list) {
      JsonNode key = kv.get("key");
      if (key == null || !key.isTextual()) {
        continue;
      }
      Object v = fromAnyValue(kv.get("value"));
      if (v != null) {
        out.put(key.asText(), v);
      }
    }
    return out;
  }

  /**
   * Flattens OTLP {@code resourceSpans → scopeSpans → spans} (v2 {@code trace.resourceSpans}, v1
   * {@code batches}) into model spans: ids base64 → hex, nanoseconds → microseconds, enums → model
   * values, {@code serviceName} from the resource {@code service.name}. Sorted by start, then span
   * id.
   *
   * @param resourceSpans array node or {@code null}
   * @return spans
   */
  public static List<Span> spansFromResourceSpans(JsonNode resourceSpans) {
    List<Span> spans = new ArrayList<>();
    if (resourceSpans == null) {
      return spans;
    }
    for (JsonNode rs : resourceSpans) {
      Object service = fromKeyValues(rs.path("resource").get("attributes")).get("service.name");
      String serviceName = service instanceof String ? (String) service : "";
      List<JsonNode> scopes = new ArrayList<>();
      rs.path("scopeSpans").forEach(scopes::add);
      rs.path("instrumentationLibrarySpans").forEach(scopes::add);
      for (JsonNode scope : scopes) {
        for (JsonNode span : scope.path("spans")) {
          Span converted = convert(span, serviceName);
          if (converted != null) {
            spans.add(converted);
          }
        }
      }
    }
    return Traces.sortSpans(spans);
  }

  private static Span convert(JsonNode span, String serviceName) {
    String traceId = idToHex(span.path("traceId").asText(null), 32);
    String spanId = idToHex(span.path("spanId").asText(null), 16);
    if (traceId == null || spanId == null) {
      return null;
    }
    long start = nanosToMicros(span.get("startTimeUnixNano"));
    long end = nanosToMicros(span.get("endTimeUnixNano"));
    JsonNode status = span.path("status");
    String message = status.path("message").asText("");
    Span.Builder b =
        Span.builder(traceId, spanId, span.path("name").asText(""))
            .parentSpanId(idToHex(span.path("parentSpanId").asText(null), 16))
            .kind(kind(span.get("kind")))
            .serviceName(serviceName)
            .times(start, end)
            .durationMicros(Math.max(0, end - start))
            .status(statusCode(status.get("code")), message.isEmpty() ? null : message)
            .attributes(fromKeyValues(span.get("attributes")));
    for (JsonNode e : span.path("events")) {
      b.event(
          new Span.Event(
              e.path("name").asText(""),
              nanosToMicros(e.get("timeUnixNano")),
              fromKeyValues(e.get("attributes"))));
    }
    for (JsonNode l : span.path("links")) {
      String linkTrace = idToHex(l.path("traceId").asText(null), 32);
      String linkSpan = idToHex(l.path("spanId").asText(null), 16);
      if (linkTrace != null && linkSpan != null) {
        b.link(new Span.Link(linkTrace, linkSpan, fromKeyValues(l.get("attributes"))));
      }
    }
    return b.build();
  }

  static Span.Kind kind(JsonNode node) {
    if (node == null || node.isNull()) {
      return Span.Kind.UNSPECIFIED;
    }
    if (node.isNumber()) {
      int i = node.asInt();
      return i >= 0 && i < KINDS.length ? KINDS[i] : Span.Kind.UNSPECIFIED;
    }
    return Span.Kind.parse(node.asText());
  }

  static Span.StatusCode statusCode(JsonNode node) {
    if (node == null || node.isNull()) {
      return Span.StatusCode.UNSET;
    }
    if (node.isNumber()) {
      int i = node.asInt();
      return i == 1 ? Span.StatusCode.OK : i == 2 ? Span.StatusCode.ERROR : Span.StatusCode.UNSET;
    }
    return Span.StatusCode.parse(node.asText());
  }
}

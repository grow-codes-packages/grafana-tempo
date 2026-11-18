package codes.grow.packages.tempo;

import codes.grow.packages.tempo.core.MockServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Recorded Tempo 3.1.1 responses (test resources {@code fixtures/<function>.<case>.json}). */
final class Fixtures {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Fixtures() {}

  /** One recorded exchange. */
  record Fixture(JsonNode request, int status, String contentType, String body) {}

  static Fixture load(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".json")) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      JsonNode root = MAPPER.readTree(in);
      JsonNode resp = root.get("response");
      JsonNode body = resp.get("body");
      String text =
          body == null || body.isNull() ? "" : body.isTextual() ? body.asText() : body.toString();
      String ct = resp.path("contentType").asText("");
      return new Fixture(root.get("request"), resp.get("status").asInt(), ct, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A responder that replays the fixture (status, content type, body). */
  static MockServer.Responder replay(String name) {
    Fixture f = load(name);
    return ex -> {
      if (!f.contentType().isEmpty()) {
        ex.getResponseHeaders().add("Content-Type", f.contentType());
      } else {
        ex.getResponseHeaders().add("Content-Type", "text/plain");
      }
      MockServer.send(ex, f.status(), f.body());
    };
  }

  /** A text/plain responder. */
  static MockServer.Responder text(int status, String body) {
    return ex -> {
      ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
      MockServer.send(ex, status, body);
    };
  }
}

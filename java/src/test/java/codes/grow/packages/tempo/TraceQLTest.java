package codes.grow.packages.tempo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.tempo.core.ValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class TraceQLTest {
  @Test
  void ticketExampleRendersLikeTypeScriptAndGo() {
    assertThat(
            TraceQL.span(Map.of("resource.service.name", "api"))
                .and("duration", ">", "200ms")
                .select("span.http.status_code")
                .toString())
        .isEqualTo(
            "{ resource.service.name = \"api\" && span:duration > 200ms }"
                + " | select(span.http.status_code)");
  }

  @Test
  void emptyAndWhere() {
    assertThat(TraceQL.span().build()).isEqualTo("{}");
    assertThat(TraceQL.span(Map.of()).toString()).isEqualTo("{}");
    assertThat(TraceQL.where("status", "=", "error").toString()).isEqualTo("{ status = error }");
  }

  @Test
  void mapOrder() {
    LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("span.b", 1);
    ordered.put("span.a", 2);
    assertThat(TraceQL.span(ordered).toString()).isEqualTo("{ span.b = 1 && span.a = 2 }");
    TreeMap<String, Object> sorted = new TreeMap<>(ordered);
    assertThat(TraceQL.span(sorted).toString()).isEqualTo("{ span.a = 2 && span.b = 1 }");
    assertThat(TraceQL.span(Map.of("span.z", 1, "span.a", 2, "span.m", 3)).toString())
        .isEqualTo("{ span.a = 2 && span.m = 3 && span.z = 1 }");
  }

  @Test
  void escapingAndQuoting() {
    assertThat(TraceQL.where("span.grow.note", "=", "a \"quoted\" \\ value ü").toString())
        .isEqualTo("{ span.grow.note = \"a \\\"quoted\\\" \\\\ value ü\" }");
    assertThat(TraceQL.quote("l1\nl2\tt\rr")).isEqualTo("\"l1\\nl2\\tt\\rr\"");
    assertThat(TraceQL.field("span.grow weird.key")).isEqualTo("span.\"grow weird.key\"");
    assertThat(TraceQL.field("span.\"grow weird.key\"")).isEqualTo("span.\"grow weird.key\"");
    assertThat(TraceQL.field(".http-method")).isEqualTo(".\"http-method\"");
    assertThat(TraceQL.field("resource.k8s/pod")).isEqualTo("resource.\"k8s/pod\"");
    assertThat(TraceQL.field(" .service.name ")).isEqualTo(".service.name");
    assertThat(TraceQL.field("span.a.")).isEqualTo("span.a.");
  }

  @Test
  void intrinsicsAndKeywords() {
    assertThat(TraceQL.field("duration")).isEqualTo("span:duration");
    assertThat(TraceQL.field("trace:duration")).isEqualTo("trace:duration");
    assertThat(TraceQL.field("name")).isEqualTo("name");
    assertThat(TraceQL.where("kind", "=", "server").toString()).isEqualTo("{ kind = server }");
    assertThat(TraceQL.where("kind", "=", "weird").toString()).isEqualTo("{ kind = \"weird\" }");
    assertThat(TraceQL.where("status", "=", "fine").toString()).isEqualTo("{ status = \"fine\" }");
    assertThat(TraceQL.where("trace:duration", ">=", "1.5s").toString())
        .isEqualTo("{ trace:duration >= 1.5s }");
    assertThat(TraceQL.where("span.d", "=", "200ms").toString())
        .isEqualTo("{ span.d = \"200ms\" }");
  }

  @Test
  void typedValues() {
    assertThat(TraceQL.value("span.x", 5)).isEqualTo("5");
    assertThat(TraceQL.value("span.x", 5L)).isEqualTo("5");
    assertThat(TraceQL.value("span.x", (short) 5)).isEqualTo("5");
    assertThat(TraceQL.value("span.x", (byte) 5)).isEqualTo("5");
    assertThat(TraceQL.value("span.x", BigInteger.TEN)).isEqualTo("10");
    assertThat(TraceQL.value("span.x", 1.5)).isEqualTo("1.5");
    assertThat(TraceQL.value("span.x", 2.0)).isEqualTo("2.0");
    assertThat(TraceQL.value("span.x", 2.5f)).isEqualTo("2.5");
    assertThat(TraceQL.value("span.x", new BigDecimal("3"))).isEqualTo("3.0");
    assertThat(TraceQL.value("span.x", true)).isEqualTo("true");
    assertThat(TraceQL.value("span:duration", Duration.ofMillis(1500))).isEqualTo("1500ms");
    assertThat(TraceQL.duration(Duration.ZERO)).isEqualTo("0s");
    assertThat(TraceQL.duration(Duration.ofHours(2))).isEqualTo("2h");
    assertThat(TraceQL.duration(Duration.ofMinutes(3))).isEqualTo("3m");
    assertThat(TraceQL.duration(Duration.ofSeconds(90))).isEqualTo("90s");
    assertThat(TraceQL.duration(Duration.ofNanos(1500_000))).isEqualTo("1500us");
    assertThat(TraceQL.duration(Duration.ofNanos(7))).isEqualTo("7ns");
    assertThatThrownBy(() -> TraceQL.value("span.x", Double.NaN))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.value("span.x", Double.POSITIVE_INFINITY))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.value("span.x", new Object()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void orRegexAndStructural() {
    TraceQL a = TraceQL.where("span.x", "=~", "re.*").or("span.y", "!~", "z");
    assertThat(a.toString()).isEqualTo("{ span.x =~ \"re.*\" || span.y !~ \"z\" }");
    assertThatThrownBy(() -> TraceQL.where("span.x", "=~", 1))
        .isInstanceOf(ValidationException.class);
    TraceQL root = TraceQL.where("resource.service.name", "=", "api");
    TraceQL db = TraceQL.where("span.db", "=", true).select("name");
    assertThat(root.descendant(db).toString())
        .isEqualTo("{ resource.service.name = \"api\" } >> { span.db = true }");
    assertThat(root.child(db).toString()).contains(" > ");
    assertThat(root.parent(db).toString()).contains(" < ");
    assertThat(root.ancestor(db).toString()).contains(" << ");
    assertThat(root.sibling(TraceQL.span()).toString())
        .isEqualTo("{ resource.service.name = \"api\" } ~ {}");
    TraceQL chain = root.child(db.child(TraceQL.span())).and("span.k", "=", 1);
    assertThat(chain.toString())
        .isEqualTo("{ resource.service.name = \"api\" } > { span.db = true } > { span.k = 1 }");
    assertThat(root.structural("&&", db).select("status").selects()).containsExactly("status");
    assertThatThrownBy(() -> root.structural("!", db)).isInstanceOf(ValidationException.class);
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> TraceQL.where("service.name", "=", "x"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("unknown scope");
    assertThatThrownBy(() -> TraceQL.field(" ")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.field(null)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.field("span.")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.field("span.\"bad\\\""))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.field("span.\"")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.where("span.x", "==", 1))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> TraceQL.span().select()).isInstanceOf(ValidationException.class);
  }

  @Test
  void equalityAndCheck() {
    assertThat(TraceQL.where("name", "=", "a")).isEqualTo(TraceQL.where("name", "=", "a"));
    assertThat(TraceQL.where("name", "=", "a")).isNotEqualTo("{ name = \"a\" }");
    assertThat(TraceQL.where("name", "=", "a").hashCode()).isEqualTo("{ name = \"a\" }".hashCode());
    assertThat(TraceQL.check(" { name = \"a}\\\"\" } | select(name) ", "op"))
        .isEqualTo("{ name = \"a}\\\"\" } | select(name)");
    for (String bad : new String[] {"name", "{ bad", "{ a ) }", "{ \"x }", "{ } )", "", null}) {
      assertThatThrownBy(() -> TraceQL.check(bad, "op")).isInstanceOf(ValidationException.class);
    }
  }
}

package codes.grow.packages.tempo;

import static org.assertj.core.api.Assertions.assertThat;

import codes.grow.packages.tempo.tracing.query.Span;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OtlpJsonTest {
  private static final ObjectMapper M = new ObjectMapper();

  private static JsonNode json(String s) throws Exception {
    return M.readTree(s.replace('\'', '"'));
  }

  @Test
  void ids() {
    assertThat(OtlpJson.idToHex("APdLwbBYoai4X5rgIx5AAQ==", 32))
        .isEqualTo("00f74bc1b058a1a8b85f9ae0231e4001");
    assertThat(OtlpJson.idToHex("ABCDEF0123456789", 16)).isEqualTo("abcdef0123456789");
    assertThat(OtlpJson.idToHex("AQ==", 16)).isEqualTo("0000000000000001");
    assertThat(OtlpJson.idToHex("AAAAAAAAAAA=", 16)).isNull();
    assertThat(OtlpJson.idToHex("!!", 16)).isNull();
    assertThat(OtlpJson.idToHex("", 16)).isNull();
    assertThat(OtlpJson.idToHex(null, 16)).isNull();
    assertThat(OtlpJson.idToHex("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQ==", 16)).isNull();
  }

  @Test
  void numbers() throws Exception {
    assertThat(OtlpJson.nanosToMicros(null)).isZero();
    assertThat(OtlpJson.nanosToMicros(json("null"))).isZero();
    assertThat(OtlpJson.nanosToMicros(json("1500"))).isEqualTo(1);
    assertThat(OtlpJson.nanosToMicros(json("'1795035005729668338'"))).isEqualTo(1795035005729668L);
    assertThat(OtlpJson.nanosToMicros(json("' '"))).isZero();
    assertThat(OtlpJson.nanosToMicros(json("'x'"))).isZero();
    assertThat(OtlpJson.longValue(null)).isZero();
    assertThat(OtlpJson.longValue(json("null"))).isZero();
    assertThat(OtlpJson.longValue(json("7"))).isEqualTo(7);
    assertThat(OtlpJson.longValue(json("'8'"))).isEqualTo(8);
    assertThat(OtlpJson.longValue(json("'x'"))).isZero();
  }

  @Test
  void anyValues() throws Exception {
    assertThat(OtlpJson.fromAnyValue(null)).isNull();
    assertThat(OtlpJson.fromAnyValue(json("'x'"))).isNull();
    assertThat(OtlpJson.fromAnyValue(json("{}"))).isNull();
    assertThat(OtlpJson.fromAnyValue(json("{'boolValue':true}"))).isEqualTo(true);
    assertThat(OtlpJson.fromAnyValue(json("{'intValue':'42'}"))).isEqualTo(42L);
    assertThat(OtlpJson.fromAnyValue(json("{'doubleValue':0.5}"))).isEqualTo(0.5);
    assertThat(OtlpJson.fromAnyValue(json("{'bytesValue':'AQI='}"))).isEqualTo("AQI=");
    assertThat(
            OtlpJson.fromAnyValue(
                json("{'arrayValue':{'values':[{'stringValue':'a'},{},{'intValue':1}]}}")))
        .isEqualTo(List.of("a", 1L));
    assertThat(
            OtlpJson.fromAnyValue(
                json("{'kvlistValue':{'values':[{'key':'k','value':{'stringValue':'v'}}]}}")))
        .isEqualTo(Map.of("k", "v"));
    assertThat(OtlpJson.fromKeyValues(null)).isEmpty();
    assertThat(
            OtlpJson.fromKeyValues(
                json(
                    "[{'value':{'stringValue':'x'}},{'key':1},{'key':'e'},{'key':'k','value':{'stringValue':'v'}}]")))
        .containsExactly(Map.entry("k", "v"));
  }

  @Test
  void spansFromResourceSpansHandlesVariants() throws Exception {
    assertThat(OtlpJson.spansFromResourceSpans(null)).isEmpty();
    JsonNode rs =
        json(
            "[{'resource':{'attributes':[{'key':'service.name','value':{'intValue':'1'}}]},"
                + "'instrumentationLibrarySpans':[{'spans':["
                + "{'traceId':'0000000000000000000000000000000a','spanId':'000000000000000b','name':'n',"
                + "'kind':2,'status':{'code':2,'message':'boom'},'startTimeUnixNano':5000,'endTimeUnixNano':2000,"
                + "'links':[{'traceId':'AA==','spanId':'AQ=='},{'traceId':'AQ==','spanId':'Ag==','attributes':[]}]},"
                + "{'traceId':'AA==','spanId':'AQ=='},"
                + "{'traceId':'0000000000000000000000000000000a','spanId':'000000000000000c','kind':9,'status':{'code':1}},"
                + "{'traceId':'0000000000000000000000000000000a','spanId':'000000000000000d','kind':'SPAN_KIND_CONSUMER','status':{'code':7}}"
                + "]}]}]");
    List<Span> spans = OtlpJson.spansFromResourceSpans(rs);
    assertThat(spans).hasSize(3);
    Span c = spans.get(0);
    assertThat(c.spanId()).isEqualTo("000000000000000c");
    assertThat(c.kind()).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(c.status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(c.serviceName()).isEmpty();
    assertThat(spans.get(1).kind()).isEqualTo(Span.Kind.CONSUMER);
    assertThat(spans.get(1).status().code()).isEqualTo(Span.StatusCode.UNSET);
    Span b = spans.get(2);
    assertThat(b.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(b.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(b.status().message()).contains("boom");
    assertThat(b.durationMicros()).isZero();
    assertThat(b.links()).hasSize(1);
    assertThat(OtlpJson.kind(null)).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(OtlpJson.kind(json("null"))).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(OtlpJson.kind(json("-1"))).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(OtlpJson.statusCode(null)).isEqualTo(Span.StatusCode.UNSET);
    assertThat(OtlpJson.statusCode(json("null"))).isEqualTo(Span.StatusCode.UNSET);
    assertThat(OtlpJson.statusCode(json("'STATUS_CODE_OK'"))).isEqualTo(Span.StatusCode.OK);
  }
}

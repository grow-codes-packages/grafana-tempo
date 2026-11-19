package tempo

import (
	"errors"
	"math"
	"testing"
	"time"
)

func TestTraceQLBuilder(t *testing.T) {
	cases := []struct {
		name string
		q    *TraceQLQuery
		want string
	}{
		{"ticket example", TraceQL.Span(map[string]any{"resource.service.name": "api"}).And("duration", ">", "200ms").Select("span.http.status_code"),
			`{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)`},
		{"empty", TraceQL.Span(nil), `{}`},
		{"escaping", TraceQL.Where("span.grow.note", "=", "a \"quoted\" \\ value ü\n"), `{ span.grow.note = "a \"quoted\" \\ value ü\n" }`},
		{"quoted name", TraceQL.Where("span.grow weird.key", "=", "x y"), `{ span."grow weird.key" = "x y" }`},
		{"pre-quoted name", TraceQL.Where(`span."grow weird.key"`, "!=", "x"), `{ span."grow weird.key" != "x" }`},
		{"unscoped", TraceQL.Where(".http.method", "=", "GET").Or("span.n", ">=", 3), `{ .http.method = "GET" || span.n >= 3 }`},
		{"keywords", TraceQL.Where("status", "=", "error").And("kind", "=", "server").And("span:status", "!=", "ok"),
			`{ status = error && kind = server && span:status != ok }`},
		{"keyword-like strings stay quoted elsewhere", TraceQL.Where("span.state", "=", "error").And("name", "=", "server"),
			`{ span.state = "error" && name = "server" }`},
		{"typed values", TraceQL.Where("span.code", ">=", int64(500)).And("span.ok", "=", true).And("span.ratio", "<", 0.5).
			And("span.f", "=", float32(2)).And("span.u", "=", uint(7)).And("span.i8", "=", int8(-1)).And("span.i", "=", 1),
			`{ span.code >= 500 && span.ok = true && span.ratio < 0.5 && span.f = 2.0 && span.u = 7 && span.i8 = -1 && span.i = 1 }`},
		{"durations", TraceQL.Where("trace:duration", ">", 90*time.Second).And("span:duration", "<", 1500*time.Microsecond).
			And("traceDuration", "<", "1.5s").And("span:duration", "!=", time.Duration(7)),
			`{ trace:duration > 90s && span:duration < 1500us && traceDuration < 1.5s && span:duration != 7ns }`},
		{"non-duration string on duration stays quoted", TraceQL.Where("span:duration", "=", "soon"), `{ span:duration = "soon" }`},
		{"regex", TraceQL.Where("resource.service.name", "=~", "grow-.*"), `{ resource.service.name =~ "grow-.*" }`},
		{"structural", TraceQL.Where("name", "=", "a").Descendant(TraceQL.Where("name", "=", "b")).Child(TraceQL.Span(nil)).
			Parent(TraceQL.Span(nil)).Ancestor(TraceQL.Span(nil)).Sibling(TraceQL.Span(nil)).Structural("&&", TraceQL.Span(nil)),
			`{ name = "a" } >> { name = "b" } > {} < {} << {} ~ {} && {}`},
		{"select intrinsics", TraceQL.Span(nil).Select("name", "status", "span:id"), `{} | select(name, status, span:id)`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := tc.q.Build()
			if err != nil || got != tc.want || tc.q.String() != tc.want || tc.q.Err() != nil {
				t.Fatalf("got %q err %v\nwant %q", got, err, tc.want)
			}
		})
	}

	t.Run("immutable", func(t *testing.T) {
		base := TraceQL.Where("name", "=", "a")
		_ = base.And("name", "=", "b")
		if base.String() != `{ name = "a" }` {
			t.Fatalf("base changed: %s", base)
		}
	})

	bad := map[string]*TraceQLQuery{
		"unscoped name":         TraceQL.Where("service.name", "=", "x"),
		"empty name":            TraceQL.Where("", "=", "x"),
		"empty scope rest":      TraceQL.Where("span.", "=", "x"),
		"bad quoted":            TraceQL.Where(`span."open`, "=", "x"),
		"operator":              TraceQL.Where("name", "==", "x"),
		"regex needs string":    TraceQL.Where("name", "=~", 1),
		"value type":            TraceQL.Where("name", "=", []string{"x"}),
		"nil value":             TraceQL.Where("name", "=", nil),
		"nan":                   TraceQL.Where("span.x", "=", math.NaN()),
		"structural op":         TraceQL.Span(nil).Structural("??", TraceQL.Span(nil)),
		"structural nil":        TraceQL.Span(nil).Child(nil),
		"structural bad other":  TraceQL.Span(nil).Child(TraceQL.Where("bad", "=", 1)),
		"select bad":            TraceQL.Span(nil).Select("nope"),
		"first error is kept":   TraceQL.Where("bad", "=", 1).And("name", "==", 1),
		"error survives select": TraceQL.Where("bad", "=", 1).Select("name"),
	}
	for name, q := range bad {
		t.Run(name, func(t *testing.T) {
			if s, err := q.Build(); !errors.Is(err, ErrValidation) || s != "" || q.String() != "" || !errors.Is(q.Err(), ErrValidation) {
				t.Fatalf("got %q %v", s, err)
			}
		})
	}
}

func TestTraceQLHelpers(t *testing.T) {
	if got := EscapeTraceQLString("a\"b\\c\td\r"); got != `"a\"b\\c\td\r"` {
		t.Fatalf("escape %s", got)
	}
	for d, want := range map[time.Duration]string{0: "0s", time.Hour: "1h", 2 * time.Minute: "2m", 1500 * time.Millisecond: "1500ms"} {
		if got := traceQLDuration(d); got != want {
			t.Errorf("%s: %s", d, got)
		}
	}
	for _, ok := range []string{`{ name = "a{" }`, `{ .a = "x\"}" } | rate()`, `{}`} {
		if err := checkTraceQL(ok); err != nil {
			t.Errorf("%q: %v", ok, err)
		}
	}
	for _, bad := range []string{`{ bad`, `}{`, `{ name = "x }`, `rate()`, `{ (a }`, `{ a ) }`} {
		if err := checkTraceQL(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}

//! TraceQL builder, escaping and the client-side check.

use std::time::Duration;

use grow_grafana_tempo as sdk;
use sdk::core::ErrorCode;
use sdk::{
    check_traceql, escape_traceql_string, traceql_duration, traceql_field, TraceQL, TraceQLValue,
};

#[test]
fn ticket_example() {
    let q = TraceQL::span([("resource.service.name", "api")])
        .and("duration", ">", "200ms")
        .select(["span.http.status_code"]);
    assert_eq!(
        q.build().unwrap(),
        r#"{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)"#
    );
    assert_eq!(q.to_string(), q.build().unwrap());
    assert!(q.err().is_none());
}

#[test]
fn empty_and_key_order() {
    assert_eq!(TraceQL::new().build().unwrap(), "{}");
    assert_eq!(TraceQL::default().build().unwrap(), "{}");
    let none: [(&str, &str); 0] = [];
    assert_eq!(TraceQL::span(none).build().unwrap(), "{}");
    assert_eq!(
        TraceQL::span([("span.b", 1), ("resource.a", 2)])
            .build()
            .unwrap(),
        "{ resource.a = 2 && span.b = 1 }"
    );
}

#[test]
fn escaping_and_quoted_names() {
    assert_eq!(
        escape_traceql_string("a \"quoted\" \\ value ü\n\t\r"),
        r#""a \"quoted\" \\ value ü\n\t\r""#
    );
    assert_eq!(
        TraceQL::where_("span.grow weird.key", "=", "x y")
            .build()
            .unwrap(),
        r#"{ span."grow weird.key" = "x y" }"#
    );
    assert_eq!(
        TraceQL::where_("span.\"grow weird.key\"", "=", "x")
            .build()
            .unwrap(),
        r#"{ span."grow weird.key" = "x" }"#
    );
    assert_eq!(
        TraceQL::where_(".http-route", "=", "a").build().unwrap(),
        r#"{ ."http-route" = "a" }"#
    );
    assert_eq!(traceql_field("event.name").unwrap(), "event.name");
    assert_eq!(
        traceql_field("trace:rootService").unwrap(),
        "trace:rootService"
    );
    assert_eq!(traceql_field(" statusMessage ").unwrap(), "statusMessage");
    for bad in [
        "service.name",
        "span.",
        "span.\"open",
        "span.\"x\\\"",
        "span:9bad",
        "foo:bar",
        "span:",
        "nocolon",
    ] {
        assert_eq!(
            traceql_field(bad).unwrap_err().code(),
            ErrorCode::Validation,
            "{bad:?}"
        );
    }
}

#[test]
fn typed_values() {
    let q = TraceQL::new()
        .and("span.http.response.status_code", ">=", 500)
        .and("span.n", "<", 3_i64)
        .and("span.u", "<=", 4_u32)
        .and("span.u64", "<=", 5_u64)
        .and("span.us", "<=", 6_usize)
        .and("span.f", ">", 1.5)
        .and("span.f2", ">", 2.0_f32)
        .and("span.ok", "=", true)
        .and("trace:duration", ">", Duration::from_millis(1500))
        .and("status", "=", "error")
        .and("kind", "=", "server")
        .and("span:status", "!=", "ok")
        .and("span:kind", "=", "client")
        .and("name", "=", "error")
        .and("span.raw", "=", TraceQLValue::raw("nil"))
        .and("traceDuration", ">", "1.5s")
        .and("span:duration", ">", "abc")
        .or("span.s", "=~", "re.*")
        .and("span.s", "!~", String::from("x"))
        .and("span.r", "=", TraceQLValue::from(&String::from("y")));
    assert_eq!(
        q.build().unwrap(),
        concat!(
            "{ span.http.response.status_code >= 500 && span.n < 3 && span.u <= 4 && span.u64 <= 5",
            " && span.us <= 6 && span.f > 1.5 && span.f2 > 2.0 && span.ok = true",
            " && trace:duration > 1500ms && status = error && kind = server && span:status != ok",
            " && span:kind = client && name = \"error\" && span.raw = nil && traceDuration > 1.5s",
            " && span:duration > \"abc\" || span.s =~ \"re.*\" && span.s !~ \"x\" && span.r = \"y\" }"
        )
    );
}

#[test]
fn structural_and_select() {
    let api = TraceQL::span([("resource.service.name", "api")]);
    let db = TraceQL::span([("resource.service.name", "db")]);
    assert_eq!(
        api.descendant(&db).build().unwrap(),
        r#"{ resource.service.name = "api" } >> { resource.service.name = "db" }"#
    );
    assert_eq!(
        api.child(&db)
            .parent(&db)
            .ancestor(&db)
            .sibling(&db)
            .build()
            .unwrap(),
        r#"{ resource.service.name = "api" } > { resource.service.name = "db" } < { resource.service.name = "db" } << { resource.service.name = "db" } ~ { resource.service.name = "db" }"#
    );
    let nested = api.structural("&&", &db.descendant(&api));
    assert_eq!(
        nested.build().unwrap(),
        r#"{ resource.service.name = "api" } && { resource.service.name = "db" } >> { resource.service.name = "api" }"#
    );
    assert_eq!(
        api.select(["name", "status", "duration"]).build().unwrap(),
        r#"{ resource.service.name = "api" } | select(name, status, span:duration)"#
    );
    // The base query is unchanged (immutable builder).
    assert_eq!(api.build().unwrap(), r#"{ resource.service.name = "api" }"#);
}

#[test]
fn errors_are_reported_by_build() {
    let cases = [
        TraceQL::where_("service.name", "=", "x"),
        TraceQL::where_("span.a", "==", "x"),
        TraceQL::where_("span.a", "=~", 1),
        TraceQL::where_("span.a", "=", f64::NAN),
        TraceQL::new().structural("??", &TraceQL::new()),
        TraceQL::new().descendant(&TraceQL::where_("bad", "=", 1)),
        TraceQL::new().select(["service.name"]),
    ];
    for q in cases {
        let err = q.build().unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation);
        assert!(err.message().starts_with("traceql: "), "{err}");
        assert_eq!(q.err().unwrap().code(), ErrorCode::Validation);
        assert_eq!(q.to_string(), "");
    }
    // The first error wins.
    let q = TraceQL::where_("bad", "=", 1).and("span.a", "==", 2);
    assert!(q.build().unwrap_err().message().contains("bad"));
}

#[test]
fn durations() {
    assert_eq!(traceql_duration(Duration::ZERO), "0s");
    assert_eq!(traceql_duration(Duration::from_secs(7200)), "2h");
    assert_eq!(traceql_duration(Duration::from_secs(120)), "2m");
    assert_eq!(traceql_duration(Duration::from_secs(90)), "90s");
    assert_eq!(traceql_duration(Duration::from_millis(200)), "200ms");
    assert_eq!(traceql_duration(Duration::from_micros(1500)), "1500us");
    assert_eq!(traceql_duration(Duration::from_nanos(7)), "7ns");
    for (v, bare) in [
        ("200ms", true),
        ("1.5s", true),
        ("10µs", true),
        ("5ns", true),
        ("3h", true),
        ("1.s", false),
        (".5s", false),
        ("5", false),
        ("5d", false),
        ("1.2.3s", false),
    ] {
        let q = TraceQL::where_("trace:duration", ">", v).build().unwrap();
        assert_eq!(!q.contains('"'), bare, "{v}: {q}");
    }
}

#[test]
fn check() {
    for ok in [
        "{}",
        r#"{ span.a = "}" }"#,
        r#"{ span.a = "\"" } | select(name)"#,
    ] {
        check_traceql(ok, "op").unwrap();
    }
    for bad in ["{ bad", "}{", "{ a = \"x }", "no filter", "{ ( }", "{ ) }"] {
        let err = check_traceql(bad, "op").unwrap_err();
        assert_eq!(err.code(), ErrorCode::Validation, "{bad}");
        assert!(err.message().starts_with("op: invalid TraceQL"), "{err}");
    }
}

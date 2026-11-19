# TraceQL builder

`TraceQL` builds [TraceQL](https://grafana.com/docs/tempo/latest/traceql/) queries safely. Every
method returns a new, immutable builder; rendering is identical in all five languages.

| Function | Description |
| - | - |
| `span(filters)` | spanset filter; each `field: value` entry becomes `field = value`, joined with `&&` (Go sorts the map keys, the other languages keep insertion order). `span()` alone is `{}` |
| `where(field, op, value)` | spanset filter with one condition (Rust: `where_`) |
| `and(field, op, value)` / `or(…)` | add `&& …` / `\|\| …` to the last spanset (Python: `and_`, `or_`) |
| `child(other)`, `descendant(other)`, `parent(other)`, `ancestor(other)`, `sibling(other)` | structural operators `>`, `>>`, `<`, `<<`, `~` |
| `structural(op, other)` | any spanset operator: `>`, `>>`, `<`, `<<`, `~`, `&&`, `\|\|`; `other`'s `select` is ignored |
| `select(fields…)` | `\| select(…)`: return these fields on every matched span |
| `field(name)` | validate and render one field name |
| `quote(text)` | render a string literal (Go: `EscapeTraceQLString`, Rust: `escape_traceql_string`) |
| `checkTraceQL(text)` | light client-side check (starts with `{`, balanced braces, parentheses and quotes) |

**Rules (the same in every language):**

- Operators: `=`, `!=`, `>`, `>=`, `<`, `<=`, `=~`, `!~`; `=~` / `!~` need a string. Anything
  else is `VALIDATION`.
- Fields need a scope (`resource.`, `span.`, `event.`, `link.`, `instrumentation.`, or `.` for
  "span or resource") or must be an intrinsic (`name`, `status`, `statusMessage`, `kind`,
  `duration`, `rootName`, `rootServiceName`, `traceDuration`, `childCount`, `nestedSet*`, or a
  `span:` / `trace:` / `event:` / `link:` / `instrumentation:` intrinsic). Bare `duration` is
  written as `span:duration`. Unknown scopes are `VALIDATION`, because some Tempo endpoints silently
  ignore an invalid query.
- Attribute names with any character outside `[A-Za-z0-9_.]` are quoted after the scope:
  `span.grow weird.key` → `span."grow weird.key"`. Already quoted names are kept.
- String values are quoted, escaping `\`, `"`, newline, tab and carriage return; other characters
  (Unicode included) pass through. Exceptions: duration literals (`200ms`) on duration fields,
  `ok` / `error` / `unset` on `status`, and span kinds (`server`, `client`, …) on `kind`.
- Numbers and booleans are literals. Rust, Go, Python and Java also take a duration value
  (`Duration`, `time.Duration`, `timedelta`) and render the largest exact unit (`1500ms` → `1500ms`,
  `2s`).

## Example

=== "TypeScript"

    ```ts
    import { TraceQL } from "@grow-codes/grafana-tempo";

    const q = TraceQL.span({ "resource.service.name": "api" })
      .and("duration", ">", "200ms")
      .select("span.http.status_code");
    q.toString(); // { resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)

    TraceQL.span({ "resource.service.name": "api" }).child(TraceQL.where("status", "=", "error")).toString();
    // { resource.service.name = "api" } > { status = error }
    ```

=== "Rust"

    ```rust
    use grow_grafana_tempo::TraceQL;

    let q = TraceQL::span([("resource.service.name", "api")])
        .and("duration", ">", "200ms")
        .select(["span.http.status_code"])
        .build()?;
    let structural = TraceQL::span([("resource.service.name", "api")])
        .child(&TraceQL::where_("status", "=", "error"))
        .build()?; // { resource.service.name = "api" } > { status = error }
    ```

=== "Go"

    ```go
    q, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).
        And("duration", ">", 200*time.Millisecond).
        Select("span.http.status_code").
        Build()
    structural, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).
        Child(tempo.TraceQL.Where("status", "=", "error")).
        Build() // { resource.service.name = "api" } > { status = error }
    ```

    Go builders record the first error; `Build()` returns it as a `VALIDATION` error.

=== "Python"

    ```python
    from grow_grafana_tempo import TraceQL

    q = (
        TraceQL.span({"resource.service.name": "api"})
        .and_("duration", ">", "200ms")
        .select("span.http.status_code")
    )
    str(q)  # '{ resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)'
    str(TraceQL.span({"resource.service.name": "api"}).child(TraceQL.where("status", "=", "error")))
    ```

=== "Java"

    ```java
    TraceQL q =
        TraceQL.span(Map.of("resource.service.name", "api"))
            .and("duration", ">", "200ms")
            .select("span.http.status_code");
    String text = q.build();
    TraceQL structural =
        TraceQL.span(Map.of("resource.service.name", "api"))
            .child(TraceQL.where("status", "=", "error")); // { resource.service.name = "api" } > { status = error }
    ```

package tempo

import (
	"errors"
	"fmt"
	"math"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// TraceQLBuilder starts TraceQL queries; use the TraceQL value:
//
//	q, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).
//		And("duration", ">", "200ms").
//		Select("span.http.status_code").
//		Build()
//	// { resource.service.name = "api" && span:duration > 200ms } | select(span.http.status_code)
//
// Attribute names need a scope (resource., span., event., link., instrumentation., or
// "." for any) or must be an intrinsic (name, status, kind, duration, statusMessage,
// span:*, trace:*, ...). Names with spaces or other special characters are quoted
// (span."my key"); string values are quoted and escaped. Unknown operators and
// unscoped names are VALIDATION errors, reported by Build.
type TraceQLBuilder struct{}

// TraceQL is the entry point of the TraceQL builder.
var TraceQL TraceQLBuilder

// Span starts a query with one span-set filter whose conditions are attribute =
// value, joined with && in key order. A nil or empty map gives `{}` (all spans).
func (TraceQLBuilder) Span(conditions map[string]any) *TraceQLQuery {
	q := &TraceQLQuery{parts: []traceQLPart{{}}}
	keys := make([]string, 0, len(conditions))
	for k := range conditions {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		q = q.And(k, "=", conditions[k])
	}
	return q
}

// Where starts a query with one condition: `{ attr op value }`.
func (b TraceQLBuilder) Where(attr, op string, value any) *TraceQLQuery {
	return b.Span(nil).And(attr, op, value)
}

// TraceQLQuery is an immutable TraceQL query under construction: every method returns
// a new query, so a query can be reused as a base.
type TraceQLQuery struct {
	parts   []traceQLPart
	selects []string
	err     error
}

type traceQLPart struct {
	op    string // structural operator before this span set ("" for the first)
	conds []string
}

func (q *TraceQLQuery) clone() *TraceQLQuery {
	out := &TraceQLQuery{err: q.err, selects: append([]string(nil), q.selects...)}
	out.parts = make([]traceQLPart, len(q.parts))
	for i, p := range q.parts {
		out.parts[i] = traceQLPart{op: p.op, conds: append([]string(nil), p.conds...)}
	}
	return out
}

func (q *TraceQLQuery) fail(err error) *TraceQLQuery {
	out := q.clone()
	if out.err == nil {
		out.err = err
	}
	return out
}

func (q *TraceQLQuery) add(join, attr, op string, value any) *TraceQLQuery {
	cond, err := traceQLCondition(attr, op, value)
	if err != nil {
		return q.fail(err)
	}
	out := q.clone()
	last := &out.parts[len(out.parts)-1]
	if len(last.conds) > 0 {
		last.conds = append(last.conds, join)
	}
	last.conds = append(last.conds, cond)
	return out
}

// And adds `&& attr op value` to the last span-set filter. Operators: = != > >= < <=
// =~ !~. value may be a string, a number, a bool or a time.Duration; strings that
// are durations ("200ms") for duration intrinsics, and status/kind keywords ("error",
// "server"), are written without quotes.
func (q *TraceQLQuery) And(attr, op string, value any) *TraceQLQuery {
	return q.add("&&", attr, op, value)
}

// Or adds `|| attr op value` to the last span-set filter.
func (q *TraceQLQuery) Or(attr, op string, value any) *TraceQLQuery {
	return q.add("||", attr, op, value)
}

// Structural joins another query's span sets with a span-set operator: > (child),
// >> (descendant), < (parent), << (ancestor), ~ (sibling), && or || (both / either).
// other's select() is ignored.
func (q *TraceQLQuery) Structural(op string, other *TraceQLQuery) *TraceQLQuery {
	switch op {
	case ">", ">>", "<", "<<", "~", "&&", "||":
	default:
		return q.fail(errors.New("unknown span-set operator " + strconv.Quote(op)))
	}
	if other == nil {
		return q.fail(errors.New("span-set operand is nil"))
	}
	if other.err != nil {
		return q.fail(other.err)
	}
	out := q.clone()
	for i, p := range other.parts {
		if i == 0 {
			p.op = op
		}
		out.parts = append(out.parts, traceQLPart{op: p.op, conds: append([]string(nil), p.conds...)})
	}
	return out
}

// Child is Structural(">", other): spans of other that are direct children.
func (q *TraceQLQuery) Child(other *TraceQLQuery) *TraceQLQuery { return q.Structural(">", other) }

// Descendant is Structural(">>", other).
func (q *TraceQLQuery) Descendant(other *TraceQLQuery) *TraceQLQuery {
	return q.Structural(">>", other)
}

// Parent is Structural("<", other).
func (q *TraceQLQuery) Parent(other *TraceQLQuery) *TraceQLQuery { return q.Structural("<", other) }

// Ancestor is Structural("<<", other).
func (q *TraceQLQuery) Ancestor(other *TraceQLQuery) *TraceQLQuery {
	return q.Structural("<<", other)
}

// Sibling is Structural("~", other).
func (q *TraceQLQuery) Sibling(other *TraceQLQuery) *TraceQLQuery { return q.Structural("~", other) }

// Select appends `| select(attrs...)`, which returns these attributes (and "name" or
// "status" intrinsics) with every matching span.
func (q *TraceQLQuery) Select(attrs ...string) *TraceQLQuery {
	out := q.clone()
	for _, a := range attrs {
		name, err := traceQLAttribute(a)
		if err != nil {
			return q.fail(err)
		}
		out.selects = append(out.selects, name)
	}
	return out
}

// Err returns the first error recorded while building, or nil.
func (q *TraceQLQuery) Err() error {
	if q.err == nil {
		return nil
	}
	return ValidationError("traceql", q.err.Error())
}

// Build renders the query or returns the first VALIDATION error.
func (q *TraceQLQuery) Build() (string, error) {
	if err := q.Err(); err != nil {
		return "", err
	}
	var b strings.Builder
	for i, p := range q.parts {
		if i > 0 {
			b.WriteString(" " + p.op + " ")
		}
		if len(p.conds) == 0 {
			b.WriteString("{}")
			continue
		}
		b.WriteString("{ " + strings.Join(p.conds, " ") + " }")
	}
	if len(q.selects) > 0 {
		b.WriteString(" | select(" + strings.Join(q.selects, ", ") + ")")
	}
	return b.String(), nil
}

// String renders the query; it is empty when the query is invalid (see Build).
func (q *TraceQLQuery) String() string {
	s, _ := q.Build()
	return s
}

// EscapeTraceQLString quotes s as a TraceQL string literal (backslash and double
// quote escaped; other characters, including Unicode, pass through).
func EscapeTraceQLString(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "\n", `\n`, "\t", `\t`, "\r", `\r`)
	return `"` + r.Replace(s) + `"`
}

// rawTraceQL is a value written into the query as is.
type rawTraceQL string

var (
	traceQLScopes     = []string{"resource.", "span.", "event.", "link.", "instrumentation.", "."}
	traceQLIntrinsics = map[string]bool{
		"name": true, "status": true, "statusMessage": true, "kind": true, "duration": true,
		"rootName": true, "rootServiceName": true, "traceDuration": true, "childCount": true,
		"nestedSetLeft": true, "nestedSetRight": true, "nestedSetParent": true,
	}
	traceQLColonScope = regexp.MustCompile(`^(span|trace|event|link|instrumentation):[A-Za-z][A-Za-z0-9_]*$`)
	traceQLPlainName  = regexp.MustCompile(`^[A-Za-z0-9_][A-Za-z0-9_.]*$`)
	traceQLDurationRe = regexp.MustCompile(`^[0-9]+(\.[0-9]+)?(ns|us|µs|ms|s|m|h)$`)
	traceQLStatuses   = map[string]bool{"error": true, "ok": true, "unset": true}
	traceQLKinds      = map[string]bool{
		"unspecified": true, "internal": true, "server": true, "client": true, "producer": true, "consumer": true,
	}
	traceQLOperators = map[string]bool{"=": true, "!=": true, ">": true, ">=": true, "<": true, "<=": true, "=~": true, "!~": true}
)

// traceQLAttribute validates and renders an attribute name.
func traceQLAttribute(name string) (string, error) {
	name = strings.TrimSpace(name)
	switch {
	case name == "duration":
		return "span:duration", nil
	case traceQLIntrinsics[name], traceQLColonScope.MatchString(name):
		return name, nil
	}
	for _, scope := range traceQLScopes {
		rest, ok := strings.CutPrefix(name, scope)
		if !ok {
			continue
		}
		switch {
		case rest == "":
		case strings.HasPrefix(rest, `"`):
			if len(rest) >= 2 && strings.HasSuffix(rest, `"`) && !strings.HasSuffix(rest, `\"`) {
				return name, nil
			}
		case traceQLPlainName.MatchString(rest):
			return name, nil
		default:
			return scope + EscapeTraceQLString(rest), nil
		}
		return "", fmt.Errorf("invalid attribute name %q", name)
	}
	return "", fmt.Errorf("attribute %q needs a scope (resource., span., event., link., instrumentation. or .) or must be an intrinsic", name)
}

func isDurationAttr(a string) bool {
	return a == "span:duration" || a == "trace:duration" || a == "traceDuration"
}

// traceQLCondition renders `attr op value`.
func traceQLCondition(attr, op string, value any) (string, error) {
	name, err := traceQLAttribute(attr)
	if err != nil {
		return "", err
	}
	if !traceQLOperators[op] {
		return "", fmt.Errorf("unknown operator %q", op)
	}
	if op == "=~" || op == "!~" {
		s, ok := value.(string)
		if !ok {
			return "", fmt.Errorf("operator %s needs a string value", op)
		}
		return name + " " + op + " " + EscapeTraceQLString(s), nil
	}
	v, err := traceQLValue(name, value)
	if err != nil {
		return "", err
	}
	return name + " " + op + " " + v, nil
}

// traceQLValue renders a literal for attribute name.
func traceQLValue(name string, value any) (string, error) {
	switch v := value.(type) {
	case rawTraceQL:
		return string(v), nil
	case string:
		switch {
		case isDurationAttr(name) && traceQLDurationRe.MatchString(v):
			return v, nil
		case (name == "status" || name == "span:status") && traceQLStatuses[v]:
			return v, nil
		case (name == "kind" || name == "span:kind") && traceQLKinds[v]:
			return v, nil
		}
		return EscapeTraceQLString(v), nil
	case time.Duration:
		return traceQLDuration(v), nil
	case bool:
		return strconv.FormatBool(v), nil
	case int:
		return strconv.Itoa(v), nil
	case int8, int16, int32, int64:
		return fmt.Sprint(v), nil
	case uint, uint8, uint16, uint32, uint64:
		return fmt.Sprint(v), nil
	case float32:
		return traceQLFloat(float64(v))
	case float64:
		return traceQLFloat(v)
	}
	return "", fmt.Errorf("unsupported value type %T for %s", value, name)
}

func traceQLFloat(f float64) (string, error) {
	if math.IsNaN(f) || math.IsInf(f, 0) {
		return "", errors.New("value must be a finite number")
	}
	s := strconv.FormatFloat(f, 'f', -1, 64)
	if !strings.Contains(s, ".") {
		s += ".0"
	}
	return s, nil
}

// traceQLDuration renders d with the largest exact unit (1h, 90s, 200ms, 1500us, 7ns).
func traceQLDuration(d time.Duration) string {
	units := []struct {
		d    time.Duration
		name string
	}{{time.Hour, "h"}, {time.Minute, "m"}, {time.Second, "s"}, {time.Millisecond, "ms"}, {time.Microsecond, "us"}}
	if d == 0 {
		return "0s"
	}
	for _, u := range units {
		if d%u.d == 0 {
			return strconv.FormatInt(int64(d/u.d), 10) + u.name
		}
	}
	return strconv.FormatInt(int64(d), 10) + "ns"
}

// checkTraceQL is a light client-side check (Tempo's tag endpoints ignore invalid
// queries instead of failing): the query must contain a span-set filter, with balanced
// braces and parentheses and closed string literals.
func checkTraceQL(q string) error {
	depth := map[rune]int{}
	inString := false
	escaped := false
	for _, r := range q {
		if inString {
			switch {
			case escaped:
				escaped = false
			case r == '\\':
				escaped = true
			case r == '"':
				inString = false
			}
			continue
		}
		switch r {
		case '"':
			inString = true
		case '{', '(':
			depth[r]++
		case '}':
			depth['{']--
		case ')':
			depth['(']--
		}
		if depth['{'] < 0 || depth['('] < 0 {
			return errors.New("invalid TraceQL: unbalanced braces or parentheses")
		}
	}
	switch {
	case inString:
		return errors.New("invalid TraceQL: unterminated string")
	case depth['{'] != 0 || depth['('] != 0:
		return errors.New("invalid TraceQL: unbalanced braces or parentheses")
	case !strings.Contains(q, "{"):
		return errors.New("invalid TraceQL: a span-set filter { ... } is required")
	}
	return nil
}

// Package tempo is the Grow Codes SDK for Grafana Tempo: OpenTelemetry tracing
// setup that exports to Tempo over OTLP, and a client for the Tempo HTTP query API
// (trace by id, TraceQL search, tag names and values, TraceQL metrics).
//
// Create a client from the environment (GROW_GRAFANA_TEMPO_URL / TEMPO_URL,
// GROW_GRAFANA_TEMPO_TENANT_ID / TEMPO_TENANT_ID, ...) or from a Config literal:
//
//	client, err := tempo.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	trace, err := client.Query().GetTrace(ctx, "5b8efff798038103d269b633813fc60c")
//
// Start tracing (OTLP/HTTP to http://127.0.0.1:4318 unless GROW_GRAFANA_TEMPO_OTLP_ENDPOINT
// or OTEL_EXPORTER_OTLP_* say otherwise):
//
//	handle, err := tempo.InitTracing(ctx, tempo.TracingConfig{ServiceName: "orders"})
//	if err != nil {
//		return err
//	}
//	defer handle.Shutdown(context.Background())
//
// Build TraceQL safely with the TraceQL builder:
//
//	q, _ := tempo.TraceQL.Span(map[string]any{"resource.service.name": "api"}).
//		And("duration", ">", "200ms").Select("span.http.status_code").Build()
//	res, err := client.Query().Search(ctx, tempo.SearchRequest{TraceQL: q})
//
// On a multi-tenant Tempo every query and export needs a tenant (Config.TenantID,
// WithTenant), sent as X-Scope-OrgID. Search, tags and metrics see new data about
// 30 s after ingest; trace by id sees it at once.
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, tempo.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Grafana Tempo is a trademark of Grafana Labs. This package is not affiliated
// with or endorsed by Grafana Labs.
package tempo

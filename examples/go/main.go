// Command example shows the Grafana Tempo Go SDK end to end: it sends a small trace
// to Tempo over OTLP, reads it back by id, then searches it with TraceQL.
//
//	GROW_GRAFANA_TEMPO_URL=http://127.0.0.1:3200 \
//	GROW_GRAFANA_TEMPO_OTLP_ENDPOINT=http://127.0.0.1:4318 \
//	go run .
//
// Set GROW_GRAFANA_TEMPO_TENANT_ID for a multi-tenant Tempo.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	tempo "github.com/grow-codes-packages/grafana-tempo/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	// Query client: GROW_GRAFANA_TEMPO_URL / TEMPO_URL, GROW_GRAFANA_TEMPO_TENANT_ID / TEMPO_TENANT_ID.
	client, err := tempo.NewClientFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	defer func() { _ = client.Close() }()
	fmt.Println("config:", client.Config())

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("health: healthy=%t status=%s\n", health.Healthy, health.Status)
	if _, err := client.Ping(ctx); errors.Is(err, tempo.ErrAuthentication) {
		return fmt.Errorf("set GROW_GRAFANA_TEMPO_TENANT_ID for a multi-tenant Tempo: %w", err)
	} else if err != nil {
		return err
	}
	version, err := client.ServerVersion(ctx)
	if err != nil {
		return err
	}
	fmt.Println("server version:", version)

	// Tracing: OTLP/HTTP to GROW_GRAFANA_TEMPO_OTLP_ENDPOINT (or OTEL_EXPORTER_OTLP_*), tenant header included.
	service := fmt.Sprintf("grow-tempo-example-%d", time.Now().Unix())
	handle, err := tempo.InitTracing(ctx, tempo.TracingConfig{ServiceName: service, ServiceVersion: "1.0.0"})
	if err != nil {
		return err
	}
	defer func() { _ = handle.Shutdown(context.Background()) }()

	traceID, err := tempo.WithSpan(ctx, "GET /orders", func(ctx context.Context) (string, error) {
		err := tempo.Traced(ctx, "load order", func(ctx context.Context) error {
			time.Sleep(250 * time.Millisecond)
			return nil
		}, tempo.WithSpanAttributes(map[string]any{"order.id": 42}))
		id, _ := tempo.CurrentTraceID(ctx)
		return id, err
	}, tempo.WithSpanAttributes(map[string]any{"http.response.status_code": 200}))
	if err != nil {
		return err
	}
	if err := handle.ForceFlush(ctx); err != nil {
		return err
	}

	// Trace by id is available right after the export.
	q := client.Query()
	trace, err := q.WaitForTrace(ctx, traceID, tempo.WaitForTraceOptions{MinSpans: 2})
	if err != nil {
		return err
	}
	for _, s := range trace.Spans {
		fmt.Printf("span %s %-12s %s %dus\n", s.SpanID, s.Name, s.ServiceName, s.DurationMicros)
	}

	// TraceQL search sees new traces about 30 s after ingest.
	query, err := tempo.TraceQL.Span(map[string]any{"resource.service.name": service}).
		And("duration", ">", "200ms").Select("span.order.id").Build()
	if err != nil {
		return err
	}
	fmt.Println("traceql:", query)
	found, err := tempo.PollUntil(ctx, "search", 90*time.Second, 2*time.Second,
		func(ctx context.Context) (*tempo.SearchResult, bool, error) {
			res, err := q.Search(ctx, tempo.SearchRequest{TraceQL: query, Limit: 5})
			return res, err == nil && len(res.Traces) > 0, err
		})
	if err != nil {
		return err
	}
	for _, t := range found.Traces {
		fmt.Printf("found %s root=%q duration=%s\n", t.TraceID, t.RootTraceName, t.Duration)
	}

	if _, err := q.GetDependencies(ctx, tempo.DependencyOptions{}); errors.Is(err, tempo.ErrUnsupported) {
		fmt.Println("dependencies: not offered by Tempo")
	}
	return nil
}

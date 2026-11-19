package integration

import (
	"context"
	"slices"
	"strings"
	"testing"
	"time"

	tempo "github.com/grow-codes-packages/grafana-tempo/go"
	"github.com/grow-codes-packages/grafana-tempo/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
// "dependencies" is left out: Tempo has no dependency graph API (docs/compatibility.md).
var capabilities = []string{"version", "query", "services"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("tracing", ",")...)
}

// searchVisibility is how long tag/search data may take to appear after ingest
// (Tempo cuts its live-store head block about 30 s after ingest).
const searchVisibility = 90 * time.Second

// visibleQuery waits for search-based data in ListServices: Tempo serves a trace by id
// at once but lists its service only about 30 s later.
type visibleQuery struct {
	*tempo.QueryClient
	service string
}

func (q visibleQuery) ListServices(ctx context.Context) ([]string, error) {
	return tempo.PollUntil(ctx, "listServices", searchVisibility, time.Second, func(ctx context.Context) ([]string, bool, error) {
		svcs, err := q.QueryClient.ListServices(ctx)
		return svcs, err == nil && slices.Contains(svcs, q.service), err
	})
}

// factories builds the conformance targets.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return tempo.NewClientFromEnv()
		}),
		"tracing": testkit.TracingFactory(func(ctx context.Context, serviceName string) (testkit.TracingTarget, error) {
			client, err := tempo.NewClientFromEnv()
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			t.Cleanup(func() { _ = client.Close() })
			h, err := tempo.InitTracing(ctx, tempo.TracingConfig{
				ServiceName:      serviceName,
				DisableSetGlobal: true,
				Batch:            tempo.TracingBatchOptions{ScheduledDelay: 200 * time.Millisecond},
			})
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			return testkit.TracingTarget{Handle: h, Query: visibleQuery{QueryClient: client.Query(), service: serviceName}}, nil
		}),
	}
}

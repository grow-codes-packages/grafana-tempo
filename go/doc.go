// Package tempo is the Grow Codes SDK for Grafana Tempo.
//
// Create a client from the environment (GROW_GRAFANA_TEMPO_URL,
// GROW_GRAFANA_TEMPO_TOKEN, ...) or from a Config literal:
//
//	client, err := tempo.NewClientFromEnv()
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, tempo.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Grafana Tempo is a trademark of Grafana Labs. This package is not affiliated
// with or endorsed by Grafana Labs.
package tempo

package tempo

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

// fixture is a response recorded from the real Tempo 3.1.1 (testdata/fixtures).
type fixture struct {
	Function string `json:"function"`
	Case     string `json:"case"`
	Request  struct {
		Method string            `json:"method"`
		Path   string            `json:"path"`
		Query  map[string]string `json:"query"`
	} `json:"request"`
	Response struct {
		Status      int             `json:"status"`
		ContentType string          `json:"contentType"`
		Body        json.RawMessage `json:"body"`
	} `json:"response"`
}

func loadFixture(t *testing.T, name string) fixture {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	var f fixture
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return f
}

// write replays the recorded response.
func (f fixture) write(w http.ResponseWriter) {
	body := []byte(f.Response.Body)
	var s string
	if json.Unmarshal(body, &s) == nil {
		body = []byte(s)
	}
	if f.Response.ContentType != "" {
		w.Header().Set("Content-Type", f.Response.ContentType)
	}
	w.WriteHeader(f.Response.Status)
	_, _ = w.Write(body)
}

// fixtureServer serves fixtures by URL path (the first fixture for a path wins);
// unknown paths answer Tempo's "404 page not found".
func fixtureServer(t *testing.T, names ...string) *testServer {
	t.Helper()
	routes := map[string]fixture{}
	for _, n := range names {
		f := loadFixture(t, n)
		u := f.Request.Path
		if p, err := url.PathUnescape(u); err == nil {
			u = p
		}
		if _, dup := routes[u]; !dup {
			routes[u] = f
		}
	}
	return newTestServer(t, func(w http.ResponseWriter, r *http.Request, _ int) {
		if f, ok := routes[r.URL.Path]; ok {
			f.write(w)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("404 page not found\n"))
	})
}

// newTempo builds a client against url with fast retries.
func newTempo(t *testing.T, url string, mutate func(*Config)) *Client {
	t.Helper()
	cfg := Config{CommonConfig: CommonConfig{URL: url, Retry: fastRetry(2)}}
	if mutate != nil {
		mutate(&cfg)
	}
	c, err := NewClient(cfg)
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

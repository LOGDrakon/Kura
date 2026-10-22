package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
)

// Chaque exemple de examples/<moteur> est testé sur une vraie base : SQLite toujours,
// les autres quand KURA_TEST_<MOTEUR>_DSN est fourni (services de la CI, ou base locale).
func TestExamples(t *testing.T) {
	for _, engine := range []string{"sqlite", "postgres", "mysql", "sqlserver"} {
		t.Run(engine, func(t *testing.T) {
			dsn := os.Getenv("KURA_TEST_" + strings.ToUpper(engine) + "_DSN")
			if engine == "sqlite" {
				dsn = "file:" + filepath.Join(t.TempDir(), "gmao.db")
			}
			if dsn == "" {
				t.Skip("KURA_TEST_" + strings.ToUpper(engine) + "_DSN absent")
			}
			exampleScenario(t, engine, dsn)
		})
	}
}

func exampleScenario(t *testing.T, engine, dsn string) {
	dir := filepath.Join("..", "..", "examples", engine)
	t.Setenv("KURA_TOKEN", token)
	t.Setenv("KURA_DB_PASSWORD", "inutilisé")
	c, err := config.Load(filepath.Join(dir, "kura-bridge.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	c.Database.DSN = dsn
	c.Auth.Type, c.Auth.Tokens = "bearer", []string{token}
	loadSchema(t, engine, dsn, filepath.Join(dir, "schema.sql"))

	a, err := Open(context.Background(), c, quiet)
	if err != nil {
		t.Fatal(err)
	}
	e := &env{t: t, app: a}
	e.srv = newTestServer(t, a)

	st, it := e.do("GET", "/items/REF-0042", nil)
	if st != 200 || it["name"] != "Roulement 6204-2RS" || it["quantity"] != 12.0 || it["location"] != "A-03-2" {
		t.Fatalf("get_item : %d %v", st, it)
	}
	if u, _ := it["updated_at"].(string); !strings.HasPrefix(u, "2026-10-01T08:00:00") {
		t.Fatalf("updated_at : %v", it["updated_at"])
	}
	if _, it := e.do("GET", "/items/REF-0100", nil); it["name"] != "Gants nitrile (boîte de 100)" {
		t.Fatalf("accents : %v", it["name"])
	}
	_, page := e.do("GET", "/items?limit=2", nil)
	if items := page["items"].([]any); len(items) != 2 || items[0].(map[string]any)["ref"] != "3760123456789" || page["next_cursor"] != "2" {
		t.Fatalf("list_items : %v", page)
	}
	_, page = e.do("GET", "/items?limit=2&cursor=2", nil)
	if len(page["items"].([]any)) != 1 || page["next_cursor"] != nil {
		t.Fatalf("list_items page 2 : %v", page)
	}
	_, page = e.do("GET", "/items?q=gants", nil)
	if len(page["items"].([]any)) != 1 {
		t.Fatalf("recherche : %v", page)
	}
	_, page = e.do("GET", "/items?updated_since=2026-10-02T00:00:00Z", nil)
	if len(page["items"].([]any)) != 2 {
		t.Fatalf("updated_since : %v", page)
	}

	o := op("REF-0042", "delta", -2, "2026-10-22T10:00:00Z")
	if _, r := e.do("POST", "/operations", o); r["status"] != "applied" || r["quantity"] != 10.0 {
		t.Fatalf("delta : %v", r)
	}
	if _, r := e.do("POST", "/operations", o); r["status"] != "duplicate" || r["quantity"] != 10.0 {
		t.Fatalf("renvoi : %v", r)
	}
	// Le stock ne change pas (10 + 0) : MySQL doit quand même compter la ligne (clientFoundRows).
	if _, r := e.do("POST", "/operations", op("REF-0042", "set", 10, "2026-10-22T10:05:00Z")); r["status"] != "applied" {
		t.Fatalf("set sans changement : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("REF-0042", "set", 20, "2026-10-22T11:00:00Z")); r["quantity"] != 20.0 {
		t.Fatalf("set : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("REF-0042", "delta", -1, "2026-10-22T10:30:00Z")); r["status"] != "applied" || r["quantity"] != 20.0 {
		t.Fatalf("delta antérieur à l'inventaire : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("REF-0042", "set", 50, "2026-10-22T09:00:00Z")); r["status"] != "superseded" {
		t.Fatalf("set ancien : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("NOPE", "delta", 1, "2026-10-22T10:00:00Z")); r["reason"] != "unknown_item" {
		t.Fatalf("inconnu : %v", r)
	}
	db, _ := a.Store.DB()
	var n int
	if err := db.QueryRow(`SELECT count(*) FROM mouvements WHERE code = 'REF-0042'`).Scan(&n); err != nil || n != 3 {
		t.Fatalf("after_apply : %d mouvements (%v), attendu 3", n, err)
	}

	ops := []map[string]any{
		op("REF-0100", "delta", -1, "2026-10-22T09:00:00Z"),
		op("REF-0100", "set", 40, "2026-10-22T10:00:00Z"),
		op("REF-0100", "delta", 3, "2026-10-22T11:00:00Z"),
		op("REF-0100", "set", 15, "2026-10-22T08:00:00Z"),
		op("REF-0100", "delta", -4, "2026-10-22T12:00:00.250+02:00"),
	}
	for i, order := range permutations(ops) {
		if i%7 != 0 && engine != "sqlite" {
			continue // un échantillon suffit sur les bases distantes
		}
		db.Exec(`UPDATE articles SET stock = 10 WHERE code = 'REF-0100'`)
		db.Exec(`DELETE FROM kura_operations WHERE item_id = 'REF-0100'`)
		for _, o := range order {
			b, _ := json.Marshal(o)
			if _, err := a.Engine.Apply(context.Background(), b); err != nil {
				t.Fatal(err)
			}
		}
		// 12:00:00.250+02:00 = 10:00:00.250Z : juste après le set de 10:00Z, donc compté.
		if got := e.stock("REF-0100"); got != 39 {
			t.Fatalf("ordre %d : %d, attendu 39", i, got)
		}
	}
}

func loadSchema(t *testing.T, engine, dsn, path string) {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	driver := map[string]string{"sqlite": "sqlite", "postgres": "pgx", "mysql": "mysql", "sqlserver": "sqlserver"}[engine]
	db, err := sql.Open(driver, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, stmt := range splitSQL(string(raw)) {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatalf("%s : %v\n%s", path, err, stmt)
		}
	}
}

// splitSQL découpe un script en instructions (point-virgule en fin de ligne, commentaires -- retirés).
func splitSQL(s string) []string {
	var out []string
	var cur strings.Builder
	for _, line := range strings.Split(s, "\n") {
		if i := strings.Index(line, "--"); i >= 0 && !strings.Contains(line[:i], "'") {
			line = line[:i]
		}
		cur.WriteString(line + "\n")
		if strings.HasSuffix(strings.TrimSpace(line), ";") {
			if st := strings.TrimSuffix(strings.TrimSpace(cur.String()), ";"); st != "" {
				out = append(out, st)
			}
			cur.Reset()
		}
	}
	if st := strings.TrimSpace(cur.String()); st != "" {
		out = append(out, st)
	}
	return out
}

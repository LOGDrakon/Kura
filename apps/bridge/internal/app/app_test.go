package app

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/x509"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/signing"
)

const token = "jeton-de-test-0123456789"

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// Base « GMAO » de test : table articles + table mouvements remplie par after_apply.
func seedDB(t *testing.T, dir string) string {
	t.Helper()
	path := filepath.Join(dir, "gmao.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, s := range []string{
		`CREATE TABLE articles (code TEXT PRIMARY KEY, designation TEXT, stock INTEGER NOT NULL, service TEXT, modifie_le TEXT)`,
		`CREATE TABLE mouvements (id INTEGER PRIMARY KEY, code TEXT, sens TEXT, valeur INTEGER, utilisateur TEXT, date TEXT)`,
		`INSERT INTO articles VALUES ('A', 'Article A', 10, 'Maintenance', '2026-10-01T08:00:00Z')`,
		`INSERT INTO articles VALUES ('B', 'Gants nitrile', 31, 'EPI', '2026-10-02T08:00:00Z')`,
		`INSERT INTO articles VALUES ('C', 'Roulement 6204', 4, 'Maintenance', '2026-10-03T08:00:00Z')`,
	} {
		if _, err := db.Exec(s); err != nil {
			t.Fatal(err)
		}
	}
	return path
}

const sqlConfig = `
listen: 127.0.0.1:0
auth:
  type: bearer
  tokens: ["` + token + `"]
cors_origins: ["https://studio.example"]
database:
  driver: sqlite
  dsn: file:%s
items:
  id_column: code
  quantity_column: stock
queries:
  get_item: |
    SELECT code AS ref, designation AS name, stock, service, modifie_le AS updated_at
    FROM articles WHERE code = :ref
  list_items: |
    SELECT code AS ref, designation AS name, stock, service, modifie_le AS updated_at
    FROM articles
    WHERE (:q = '' OR lower(code) LIKE :q_like OR lower(designation) LIKE :q_like)
      AND modifie_le >= :updated_since_iso
    ORDER BY modifie_le DESC, code
    LIMIT :limit OFFSET :offset
  apply_delta: UPDATE articles SET stock = stock + :value WHERE code = :ref
  apply_set: UPDATE articles SET stock = :value WHERE code = :ref
  after_apply: |
    INSERT INTO mouvements (code, sens, valeur, utilisateur, date)
    VALUES (:ref, :type, :sent_value, :user, :client_ts_iso)
%s`

type env struct {
	t   *testing.T
	app *App
	srv *httptest.Server
	dir string
	db  string
}

func open(t *testing.T, yaml string) *env {
	t.Helper()
	dir := t.TempDir()
	return openIn(t, dir, yaml)
}

func openIn(t *testing.T, dir, yaml string) *env {
	t.Helper()
	cfgPath := filepath.Join(dir, "kura-bridge.yaml")
	if err := os.WriteFile(cfgPath, []byte(yaml), 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	a, err := Open(context.Background(), c, quiet)
	if err != nil {
		t.Fatal(err)
	}
	return &env{t: t, app: a, srv: newTestServer(t, a), dir: dir}
}

func newTestServer(t *testing.T, a *App) *httptest.Server {
	srv := httptest.NewServer(a.Server.Handler())
	t.Cleanup(func() { srv.Close(); a.Close() })
	return srv
}

func sqlEnv(t *testing.T, extra string) *env {
	t.Helper()
	dir := t.TempDir()
	db := seedDB(t, dir)
	e := openIn(t, dir, fmt.Sprintf(sqlConfig, db, extra))
	e.db = db
	return e
}

func (e *env) do(method, path string, body any, headers ...string) (int, map[string]any) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, rd)
	req.Header.Set("Authorization", "Bearer "+token)
	for i := 0; i+1 < len(headers); i += 2 {
		if headers[i+1] == "" {
			req.Header.Del(headers[i])
		} else {
			req.Header.Set(headers[i], headers[i+1])
		}
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	var out map[string]any
	json.NewDecoder(res.Body).Decode(&out)
	return res.StatusCode, out
}

var opSeq int

func op(item, typ string, value int, ts string) map[string]any {
	opSeq++
	return map[string]any{
		"op_id":     fmt.Sprintf("00000000-0000-4000-8000-%012d", opSeq),
		"item_id":   item,
		"type":      typ,
		"value":     value,
		"client_ts": ts,
		"device_id": "test",
		"user":      "Marie",
	}
}

func (e *env) stock(ref string) int {
	e.t.Helper()
	_, it := e.do("GET", "/items/"+ref, nil)
	return int(it["quantity"].(float64))
}

func TestItemsAndAuth(t *testing.T) {
	e := sqlEnv(t, "")
	st, it := e.do("GET", "/items/B", nil)
	if st != 200 || it["ref"] != "B" || it["name"] != "Gants nitrile" || it["quantity"] != 31.0 || it["service"] != "EPI" {
		t.Fatalf("GET /items/B : %d %v", st, it)
	}
	if st, body := e.do("GET", "/items/ZZZ", nil); st != 404 || body["error"] != "not_found" {
		t.Fatalf("article inconnu : %d %v", st, body)
	}
	if st, _ := e.do("GET", "/items/B", nil, "Authorization", ""); st != 401 {
		t.Fatalf("sans jeton : %d", st)
	}
	if st, _ := e.do("GET", "/items/B", nil, "Authorization", "Bearer mauvais-jeton-0123456789"); st != 401 {
		t.Fatalf("mauvais jeton : %d", st)
	}
	if st, _ := e.do("GET", "/items/B", nil, "Authorization", "", "X-Api-Key", token); st != 200 {
		t.Fatalf("X-Api-Key : %d", st)
	}
	if st, body := e.do("GET", "/health", nil, "Authorization", ""); st != 200 || body["status"] != "ok" {
		t.Fatalf("/health : %d %v", st, body)
	}
}

func TestListItems(t *testing.T) {
	e := sqlEnv(t, "")
	_, page := e.do("GET", "/items?limit=2", nil)
	items := page["items"].([]any)
	if len(items) != 2 || items[0].(map[string]any)["ref"] != "C" || page["next_cursor"] != "2" {
		t.Fatalf("page 1 : %v", page)
	}
	_, page = e.do("GET", "/items?limit=2&cursor=2", nil)
	if len(page["items"].([]any)) != 1 || page["next_cursor"] != nil {
		t.Fatalf("page 2 : %v", page)
	}
	_, page = e.do("GET", "/items?q=GANTS", nil)
	if items := page["items"].([]any); len(items) != 1 || items[0].(map[string]any)["ref"] != "B" {
		t.Fatalf("recherche : %v", page)
	}
	_, page = e.do("GET", "/items?updated_since=2026-10-02T00:00:00Z", nil)
	if len(page["items"].([]any)) != 2 {
		t.Fatalf("updated_since : %v", page)
	}
	if st, _ := e.do("GET", "/items?limit=900", nil); st != 400 {
		t.Fatalf("limit hors bornes : %d", st)
	}
}

func TestOperations(t *testing.T) {
	e := sqlEnv(t, "")
	o := op("A", "delta", -3, "2026-10-22T10:00:00Z")
	st, r := e.do("POST", "/operations", o)
	if st != 200 || r["status"] != "applied" || r["quantity"] != 7.0 || r["server_received_at"] == nil {
		t.Fatalf("delta : %d %v", st, r)
	}
	if _, r := e.do("POST", "/operations", o); r["status"] != "duplicate" || r["quantity"] != 7.0 {
		t.Fatalf("renvoi : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("ZZZ", "delta", 1, "2026-10-22T10:00:00Z")); r["status"] != "rejected" || r["reason"] != "unknown_item" {
		t.Fatalf("article inconnu : %v", r)
	}
	for _, bad := range []map[string]any{
		op("A", "delta", 0, "2026-10-22T10:00:00Z"),
		op("A", "set", -1, "2026-10-22T10:00:00Z"),
		op("A", "delta", 1, "hier"),
		op("A", "delta", 1, "2026-10-22"),
		{"op_id": "abc", "item_id": "A", "type": "delta", "value": 1, "client_ts": "2026-10-22T10:00:00Z"},
		{"op_id": "00000000-0000-4000-8000-999999999999", "item_id": "A", "type": "delta", "value": 1.5, "client_ts": "2026-10-22T10:00:00Z"},
	} {
		if _, r := e.do("POST", "/operations", bad); r["status"] != "rejected" || r["reason"] != "invalid" {
			t.Fatalf("invalide %v : %v", bad, r)
		}
	}
	if e.stock("A") != 7 {
		t.Fatal("les rejets ne doivent rien changer")
	}
	// Inventaire puis mouvement : visible dans la table mouvements via after_apply.
	e.do("POST", "/operations", op("A", "set", 20, "2026-10-22T11:00:00Z"))
	if _, r := e.do("POST", "/operations", op("A", "set", 50, "2026-10-22T09:00:00Z")); r["status"] != "superseded" || r["quantity"] != 20.0 {
		t.Fatalf("set plus ancien : %v", r)
	}
	db, _ := e.app.Store.DB()
	var n int
	db.QueryRow(`SELECT count(*) FROM mouvements WHERE code = 'A' AND utilisateur = 'Marie'`).Scan(&n)
	if n != 2 {
		t.Fatalf("after_apply : %d mouvements (attendu 2 : le delta et le set appliqués)", n)
	}
	if st, body := e.do("POST", "/operations", "pas un objet"); st != 200 || body["reason"] != "invalid" {
		t.Fatalf("corps non objet : %d %v", st, body)
	}
	req, _ := http.NewRequest("POST", e.srv.URL+"/operations", strings.NewReader("{"))
	req.Header.Set("Authorization", "Bearer "+token)
	if res, _ := http.DefaultClient.Do(req); res.StatusCode != 400 {
		t.Fatalf("JSON illisible : %d", res.StatusCode)
	}
}

func TestBatch(t *testing.T) {
	e := sqlEnv(t, "")
	ops := []any{
		op("B", "delta", -1, "2026-10-22T10:00:00Z"),
		op("ZZZ", "delta", -1, "2026-10-22T10:00:01Z"),
		op("B", "delta", -1, "2026-10-22T10:00:02Z"),
	}
	st, r := e.do("POST", "/operations/batch", map[string]any{"operations": ops})
	results := r["results"].([]any)
	if st != 200 || len(results) != 3 {
		t.Fatalf("lot : %d %v", st, r)
	}
	for i, want := range []string{"applied", "rejected", "applied"} {
		res := results[i].(map[string]any)
		if res["status"] != want || res["op_id"] != ops[i].(map[string]any)["op_id"] {
			t.Fatalf("résultat %d : %v", i, res)
		}
	}
	if e.stock("B") != 29 {
		t.Fatalf("stock B : %d", e.stock("B"))
	}
	if st, _ := e.do("POST", "/operations/batch", map[string]any{"operations": []any{}}); st != 400 {
		t.Fatalf("lot vide : %d", st)
	}
}

func permutations(ops []map[string]any) [][]map[string]any {
	if len(ops) <= 1 {
		return [][]map[string]any{ops}
	}
	var out [][]map[string]any
	for i := range ops {
		rest := append(append([]map[string]any{}, ops[:i]...), ops[i+1:]...)
		for _, p := range permutations(rest) {
			out = append(out, append([]map[string]any{ops[i]}, p...))
		}
	}
	return out
}

// Même scénario que le serveur de démo (tools/mock-server/test/store.test.js) : 120 ordres d'arrivée, un seul stock.
func TestArrivalOrderDoesNotMatter(t *testing.T) {
	ops := []map[string]any{
		op("A", "delta", -1, "2026-10-22T09:00:00Z"),
		op("A", "set", 40, "2026-10-22T10:00:00Z"),
		op("A", "delta", 3, "2026-10-22T11:00:00Z"),
		op("A", "set", 15, "2026-10-22T08:00:00Z"),
		op("A", "delta", -4, "2026-10-22T12:00:00Z"),
	}
	e := sqlEnv(t, "")
	db, _ := e.app.Store.DB()
	for i, order := range permutations(ops) {
		db.Exec(`UPDATE articles SET stock = 10 WHERE code = 'A'`)
		db.Exec(`DELETE FROM kura_operations`)
		for _, o := range order {
			b, _ := json.Marshal(o)
			if _, err := e.app.Engine.Apply(context.Background(), b); err != nil {
				t.Fatal(err)
			}
		}
		if got := e.stock("A"); got != 39 {
			t.Fatalf("ordre %d : stock %d, attendu 39", i, got)
		}
	}
}

func TestSameTimestampSets(t *testing.T) {
	ts := "2026-10-22T10:00:00Z"
	a := map[string]any{"op_id": "00000000-0000-4000-8000-000000000001", "item_id": "A", "type": "set", "value": 1, "client_ts": ts}
	b := map[string]any{"op_id": "00000000-0000-4000-8000-000000000002", "item_id": "A", "type": "set", "value": 2, "client_ts": ts}
	for _, order := range [][]map[string]any{{a, b}, {b, a}} {
		e := sqlEnv(t, "")
		for _, o := range order {
			e.do("POST", "/operations", o)
		}
		if e.stock("A") != 2 {
			t.Fatalf("départage par op_id : %d", e.stock("A"))
		}
	}
}

func TestSeparateJournalFile(t *testing.T) {
	e := sqlEnv(t, "journal:\n  file: data/journal.db\n")
	o := op("C", "delta", 2, "2026-10-22T10:00:00Z")
	e.do("POST", "/operations", o)
	if _, r := e.do("POST", "/operations", o); r["status"] != "duplicate" || r["quantity"] != 6.0 {
		t.Fatalf("renvoi : %v", r)
	}
	if _, err := os.Stat(filepath.Join(e.dir, "data", "journal.db")); err != nil {
		t.Fatal(err)
	}
	db, _ := e.app.Store.DB()
	if _, err := db.Exec(`SELECT 1 FROM kura_operations`); err == nil {
		t.Fatal("la table kura_operations ne doit pas être créée dans la base de l'entreprise")
	}
}

func TestCORS(t *testing.T) {
	e := sqlEnv(t, "")
	req, _ := http.NewRequest("OPTIONS", e.srv.URL+"/items", nil)
	req.Header.Set("Origin", "https://studio.example")
	req.Header.Set("Access-Control-Request-Method", "GET")
	req.Header.Set("Access-Control-Request-Private-Network", "true")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	h := res.Header
	if res.StatusCode != 204 || h.Get("Access-Control-Allow-Origin") != "https://studio.example" ||
		!strings.Contains(h.Get("Access-Control-Allow-Headers"), "Authorization") || h.Get("Access-Control-Allow-Private-Network") != "true" {
		t.Fatalf("pré-vol CORS : %d %v", res.StatusCode, h)
	}
	req.Header.Set("Origin", "https://ailleurs.example")
	res, _ = http.DefaultClient.Do(req)
	if res.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Fatal("origine non autorisée acceptée")
	}
}

func TestRemoteConfigSignature(t *testing.T) {
	dir := t.TempDir()
	db := seedDB(t, dir)
	pub, err := signing.Generate(filepath.Join(dir, "kura-signing.key"))
	if err != nil {
		t.Fatal(err)
	}
	cfg := `{"version":1,"server":{"base_url":"https://kura.example"}}` + "\n"
	os.WriteFile(filepath.Join(dir, "kura-config.json"), []byte(cfg), 0o600)
	e := openIn(t, dir, fmt.Sprintf(sqlConfig, db, "remote_config:\n  file: kura-config.json\n  signing_key_file: kura-signing.key\n"))
	st, body := e.do("GET", "/config", nil)
	if st != 200 || body["config"] != cfg {
		t.Fatalf("/config : %d %v", st, body)
	}
	der, _ := base64.StdEncoding.DecodeString(pub)
	k, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		t.Fatal(err)
	}
	sig, _ := base64.StdEncoding.DecodeString(body["signature"].(string))
	if !ed25519.Verify(k.(ed25519.PublicKey), []byte(cfg), sig) {
		t.Fatal("signature invalide")
	}
}

func TestBadQueryParameter(t *testing.T) {
	dir := t.TempDir()
	db := seedDB(t, dir)
	yaml := strings.Replace(fmt.Sprintf(sqlConfig, db, ""), "WHERE code = :ref\n", "WHERE code = :reference\n", 1)
	cfgPath := filepath.Join(dir, "kura-bridge.yaml")
	os.WriteFile(cfgPath, []byte(yaml), 0o600)
	c, err := config.Load(cfgPath)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Open(context.Background(), c, quiet); err == nil || !strings.Contains(err.Error(), "get_item") || !strings.Contains(err.Error(), "reference") {
		t.Fatalf("paramètre inconnu non signalé : %v", err)
	}
}

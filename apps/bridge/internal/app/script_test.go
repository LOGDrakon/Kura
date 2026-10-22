package app

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// Teste le mode script avec l'adaptateur Python d'exemple (examples/script).
func TestScriptExample(t *testing.T) {
	py, err := exec.LookPath("python3")
	if err != nil {
		t.Skip("python3 absent")
	}
	dir := t.TempDir()
	for _, f := range []string{"adaptateur.py", "stock.csv"} {
		b, err := os.ReadFile(filepath.Join("..", "..", "examples", "script", f))
		if err != nil {
			t.Fatal(err)
		}
		os.WriteFile(filepath.Join(dir, f), b, 0o600)
	}
	e := openIn(t, dir, `
auth: {type: bearer, tokens: ["`+token+`"]}
script:
  command: ["`+py+`", "adaptateur.py"]
  env: {KURA_STOCK_CSV: stock.csv}
`)
	if st, it := e.do("GET", "/items/REF-0042", nil); st != 200 || it["quantity"] != 12.0 || it["service"] != "Maintenance" {
		t.Fatalf("get_item : %d %v", st, it)
	}
	if st, _ := e.do("GET", "/items/NOPE", nil); st != 404 {
		t.Fatalf("inconnu : %d", st)
	}
	_, page := e.do("GET", "/items?limit=2", nil)
	if len(page["items"].([]any)) != 2 || page["next_cursor"] != "2" {
		t.Fatalf("list_items : %v", page)
	}
	o := op("REF-0042", "delta", -2, "2026-10-22T10:00:00Z")
	if _, r := e.do("POST", "/operations", o); r["status"] != "applied" || r["quantity"] != 10.0 {
		t.Fatalf("delta : %v", r)
	}
	if _, r := e.do("POST", "/operations", o); r["status"] != "duplicate" || r["quantity"] != 10.0 {
		t.Fatalf("renvoi : %v", r)
	}
	e.do("POST", "/operations", op("REF-0042", "set", 20, "2026-10-22T11:00:00Z"))
	if _, r := e.do("POST", "/operations", op("REF-0042", "delta", -1, "2026-10-22T10:30:00Z")); r["status"] != "applied" || r["quantity"] != 20.0 {
		t.Fatalf("delta antérieur à l'inventaire : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("REF-0100", "delta", -50, "2026-10-22T10:00:00Z")); r["status"] != "rejected" || r["reason"] != "forbidden" {
		t.Fatalf("refus métier : %v", r)
	}
	if _, r := e.do("POST", "/operations", op("NOPE", "delta", 1, "2026-10-22T10:00:00Z")); r["reason"] != "unknown_item" {
		t.Fatalf("inconnu : %v", r)
	}
	csv, _ := os.ReadFile(filepath.Join(dir, "stock.csv"))
	if !strings.Contains(string(csv), "REF-0042;Roulement 6204-2RS;A-03-2;Maintenance;20") {
		t.Fatalf("CSV : %s", csv)
	}
}

// Un script qui plante : 503, l'appli garde l'opération et réessaiera.
func TestScriptFailureIsRetryable(t *testing.T) {
	sh, err := exec.LookPath("sh")
	if err != nil {
		t.Skip("sh absent")
	}
	e := open(t, `
auth: {type: bearer, tokens: ["`+token+`"]}
script:
  command: ["`+sh+`", "-c", "echo base indisponible >&2; exit 3"]
`)
	st, body := e.do("POST", "/operations/batch", map[string]any{"operations": []any{op("A", "delta", 1, "2026-10-22T10:00:00Z")}})
	if st != 503 || body["error"] != "server_error" || !strings.Contains(body["message"].(string), "base indisponible") {
		t.Fatalf("échec du script : %d %v", st, body)
	}
}

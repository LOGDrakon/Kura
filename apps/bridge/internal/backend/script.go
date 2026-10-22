package backend

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
)

// ScriptStore : un programme de l'entreprise (Python, PowerShell, PHP…) fait le lien avec son système.
// Le Bridge le lance à chaque demande : requête JSON sur l'entrée standard, réponse JSON sur la sortie standard.
type ScriptStore struct {
	command []string
	env     []string
	dir     string
	timeout time.Duration
}

// OpenScript prépare le script de la config.
func OpenScript(c *config.Config) (*ScriptStore, error) {
	s := c.Script
	cmd := append([]string(nil), s.Command...)
	// Un chemin relatif vers le script se rapporte au dossier de la config.
	for i, a := range cmd {
		if i > 0 && !strings.HasPrefix(a, "-") && strings.ContainsAny(a, "./\\") {
			if p := c.Path(a); fileExists(p) {
				cmd[i] = p
			}
		}
	}
	env := os.Environ()
	for k, v := range s.Env {
		env = append(env, k+"="+v)
	}
	return &ScriptStore{command: cmd, env: env, dir: c.Dir, timeout: s.Timeout()}, nil
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// ScriptError : le script a échoué ou répondu de travers.
type ScriptError struct {
	Msg    string
	Stderr string // fin de la sortie d'erreur du script, pour le journal du Bridge
}

func (e *ScriptError) Error() string { return "script : " + e.Msg }

// Call lance le script avec une requête et décode sa réponse.
func (s *ScriptStore) Call(ctx context.Context, req map[string]any, out any) error {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	in, _ := json.Marshal(req)
	cmd := exec.CommandContext(ctx, s.command[0], s.command[1:]...)
	cmd.Env = s.env
	cmd.Dir = s.dir
	cmd.Stdin = bytes.NewReader(in)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return &ScriptError{Msg: fmt.Sprintf("pas de réponse après %s (action %v)", s.timeout, req["action"])}
	}
	if err != nil {
		full := strings.TrimSpace(stderr.String())
		if len(full) > 4000 {
			full = full[len(full)-4000:]
		}
		// Dernière ligne seulement dans la réponse (ex. la dernière ligne d'une trace Python).
		last := full
		if i := strings.LastIndexByte(full, '\n'); i >= 0 {
			last = strings.TrimSpace(full[i+1:])
		}
		msg := fmt.Sprintf("%v (action %v)", err, req["action"])
		if last != "" {
			msg += " : " + last
		}
		return &ScriptError{Msg: msg, Stderr: full}
	}
	if err := json.Unmarshal(bytes.TrimSpace(stdout.Bytes()), out); err != nil {
		return &ScriptError{Msg: fmt.Sprintf("réponse JSON illisible (action %v) : %v", req["action"], err)}
	}
	return nil
}

func (s *ScriptStore) GetItem(ctx context.Context, ref string) (Item, error) {
	var res struct {
		Item Item `json:"item"`
	}
	if err := s.Call(ctx, map[string]any{"action": "get_item", "ref": ref}, &res); err != nil {
		return nil, err
	}
	return normalize(res.Item), nil
}

func (s *ScriptStore) ListItems(ctx context.Context, q ListQuery) ([]Item, string, error) {
	req := map[string]any{"action": "list_items", "q": q.Q, "limit": q.Limit, "cursor": q.Cursor}
	if q.UpdatedSince != nil {
		req["updated_since"] = q.UpdatedSince.UTC().Format(time.RFC3339)
	}
	var res struct {
		Items      []Item  `json:"items"`
		NextCursor *string `json:"next_cursor"`
		Error      string  `json:"error"`
	}
	if err := s.Call(ctx, req, &res); err != nil {
		return nil, "", err
	}
	if res.Error == "not_supported" {
		return nil, "", ErrNotConfigured
	}
	for i := range res.Items {
		res.Items[i] = normalize(res.Items[i])
	}
	next := ""
	if res.NextCursor != nil {
		next = *res.NextCursor
	}
	return res.Items, next, nil
}

func (s *ScriptStore) Image(ctx context.Context, ref string) ([]byte, error) {
	return nil, ErrNotConfigured
}

func (s *ScriptStore) DB() (*sql.DB, string) { return nil, "" }
func (s *ScriptStore) Close() error          { return nil }

func (s *ScriptStore) Begin(ctx context.Context) (Unit, error) { return scriptUnit{s}, nil }

type scriptUnit struct{ s *ScriptStore }

func (u scriptUnit) Tx() *sql.Tx     { return nil }
func (u scriptUnit) Commit() error   { return nil }
func (u scriptUnit) Rollback() error { return nil }

func (u scriptUnit) GetItem(ctx context.Context, ref string) (Item, error) {
	return u.s.GetItem(ctx, ref)
}

func (u scriptUnit) Apply(ctx context.Context, p ApplyParams) (*int64, error) {
	req := map[string]any{"action": "apply_" + p.Kind}
	for k, v := range applyValues(p) {
		if t, ok := v.(time.Time); ok {
			v = t.UTC().Format(time.RFC3339Nano)
		}
		req[k] = v
	}
	delete(req, "client_ts_iso")
	var res struct {
		OK       bool   `json:"ok"`
		Quantity *int64 `json:"quantity"`
		Reason   string `json:"reason"`
		Message  string `json:"message"`
	}
	if err := u.s.Call(ctx, req, &res); err != nil {
		return nil, err
	}
	if !res.OK {
		if res.Reason == "unknown_item" {
			return nil, ErrUnknownItem
		}
		return nil, &RefusedError{Reason: res.Reason, Message: res.Message}
	}
	return res.Quantity, nil
}

// RefusedError : le système de l'entreprise refuse l'opération (droits, article bloqué…).
type RefusedError struct {
	Reason  string
	Message string
}

func (e *RefusedError) Error() string {
	if e.Message != "" {
		return e.Message
	}
	return "opération refusée (" + e.Reason + ")"
}

// IsRefused renvoie le refus métier contenu dans err, s'il y en a un.
func IsRefused(err error) (*RefusedError, bool) {
	var r *RefusedError
	ok := errors.As(err, &r)
	return r, ok
}

// normalize ramène les nombres JSON entiers à int64 (json.Unmarshal donne des float64).
func normalize(it Item) Item {
	for k, v := range it {
		if v == nil {
			delete(it, k)
			continue
		}
		if f, ok := v.(float64); ok {
			it[k] = jsonFloat(f)
		}
	}
	return it
}

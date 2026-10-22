// Package engine traite les opérations reçues de Kura Scan : validation, idempotence, règle de stock,
// écriture dans le système de l'entreprise et journal.
package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/backend"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/journal"
)

// Result : réponse du contrat pour une opération.
type Result struct {
	OpID             string `json:"op_id"`
	Status           string `json:"status"`
	Reason           string `json:"reason,omitempty"`
	Message          string `json:"message,omitempty"`
	Quantity         *int64 `json:"quantity,omitempty"`
	ServerReceivedAt string `json:"server_received_at"`
}

// Unavailable : le système de l'entreprise ne répond pas ; l'appli doit renvoyer plus tard (HTTP 503).
type Unavailable struct{ Err error }

func (u *Unavailable) Error() string { return u.Err.Error() }
func (u *Unavailable) Unwrap() error { return u.Err }

// Engine applique les opérations une par une (un seul Bridge par base : voir docs/guides/bridge.md).
type Engine struct {
	mu       sync.Mutex
	store    backend.Store
	journal  *journal.Journal
	sameDB   bool // journal dans la base de l'entreprise : même transaction
	quantity string
	log      *slog.Logger
	now      func() time.Time
}

// New relie le moteur à la source et au journal.
func New(store backend.Store, j *journal.Journal, quantityColumn string, log *slog.Logger) *Engine {
	db, _ := store.DB()
	return &Engine{
		store:    store,
		journal:  j,
		sameDB:   db != nil && db == j.DB,
		quantity: quantityColumn,
		log:      log,
		now:      time.Now,
	}
}

type operation struct {
	OpID      string
	ItemID    string
	Type      string
	Value     int64
	ClientTs  time.Time
	ClientRaw string
	DeviceID  string
	User      string
	SessionID string
}

var (
	uuidRe  = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	isoTsRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$`)
)

// parse vérifie une opération comme le contrat (spec/api-contract.yaml) ; renvoie l'op_id lu même si invalide.
func parse(raw json.RawMessage) (operation, string, error) {
	var m map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if err := dec.Decode(&m); err != nil || m == nil {
		return operation{}, "", errors.New("opération absente ou non objet")
	}
	str := func(k string) (string, bool) {
		s, ok := m[k].(string)
		return s, ok
	}
	opID, _ := str("op_id")
	var op operation
	if !uuidRe.MatchString(opID) {
		return op, opID, errors.New("op_id doit être un UUID")
	}
	op.OpID = strings.ToLower(opID)
	item, ok := str("item_id")
	if !ok || item == "" || len([]rune(item)) > 128 {
		return op, opID, errors.New("item_id invalide")
	}
	op.ItemID = item
	op.Type, _ = str("type")
	if op.Type != "delta" && op.Type != "set" {
		return op, opID, errors.New("type doit valoir 'delta' ou 'set'")
	}
	n, ok := m["value"].(json.Number)
	v, err := n.Int64()
	if !ok || err != nil {
		return op, opID, errors.New("value doit être un entier")
	}
	if op.Type == "delta" && v == 0 {
		return op, opID, errors.New("un delta ne peut pas être nul")
	}
	if op.Type == "set" && v < 0 {
		return op, opID, errors.New("un set ne peut pas être négatif")
	}
	op.Value = v
	ts, _ := str("client_ts")
	t, err := parseTs(ts)
	if err != nil {
		return op, opID, errors.New("client_ts doit être une date ISO 8601 avec fuseau (ex. 2026-10-22T13:55:12Z)")
	}
	op.ClientTs, op.ClientRaw = t, ts
	op.DeviceID, _ = str("device_id")
	op.User, _ = str("user")
	op.SessionID, _ = str("session_id")
	return op, opID, nil
}

func parseTs(s string) (time.Time, error) {
	if !isoTsRe.MatchString(s) {
		return time.Time{}, errors.New("format")
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t, nil
	}
	return time.Parse("2006-01-02T15:04Z07:00", s)
}

// Apply traite une opération. Une erreur *Unavailable signifie « réessayer plus tard » : rien n'a été enregistré.
func (e *Engine) Apply(ctx context.Context, raw json.RawMessage) (Result, error) {
	received := e.now().UTC()
	res := Result{ServerReceivedAt: received.Format("2006-01-02T15:04:05.000Z")}
	op, opID, err := parse(raw)
	res.OpID = opID
	if err != nil {
		res.Status, res.Reason, res.Message = "rejected", "invalid", err.Error()
		return res, nil
	}

	e.mu.Lock()
	defer e.mu.Unlock()
	out, err := e.apply(ctx, op, received)
	if err != nil {
		attrs := []any{"op_id", op.OpID, "item", op.ItemID, "err", err}
		var se *backend.ScriptError
		if errors.As(err, &se) && se.Stderr != "" {
			attrs = append(attrs, "stderr", se.Stderr)
		}
		e.log.Error("opération non traitée", attrs...)
		return res, &Unavailable{err}
	}
	out.OpID, out.ServerReceivedAt = opID, res.ServerReceivedAt
	attrs := []any{"op_id", op.OpID, "item", op.ItemID, "type", op.Type, "value", op.Value, "status", out.Status}
	if out.Reason != "" {
		attrs = append(attrs, "reason", out.Reason)
	}
	if out.Quantity != nil {
		attrs = append(attrs, "quantity", *out.Quantity)
	}
	if op.User != "" {
		attrs = append(attrs, "user", op.User)
	}
	e.log.Info("opération", attrs...)
	return out, nil
}

func (e *Engine) apply(ctx context.Context, op operation, received time.Time) (res Result, err error) {
	unit, err := e.store.Begin(ctx)
	if err != nil {
		return res, err
	}
	jtx := unit.Tx()
	if !e.sameDB {
		if jtx, err = e.journal.DB.BeginTx(ctx, nil); err != nil {
			unit.Rollback()
			return res, err
		}
	}
	committed := false
	defer func() {
		if !committed {
			unit.Rollback()
			if !e.sameDB {
				jtx.Rollback()
			}
		}
	}()

	if prev, err := e.journal.Find(ctx, jtx, op.OpID); err != nil {
		return res, err
	} else if prev != nil {
		res.Status = "duplicate"
		if prev.ItemID == op.ItemID {
			res.Quantity, err = e.quantityOf(ctx, unit, op.ItemID)
		}
		return res, err
	}

	item, err := unit.GetItem(ctx, op.ItemID)
	if err != nil {
		return res, err
	}
	if item == nil {
		// Pas enregistrée : l'article peut être créé ensuite et l'opération renvoyée.
		return unknown(op), nil
	}
	past, err := e.journal.ForItem(ctx, jtx, op.ItemID)
	if err != nil {
		return res, err
	}
	entry := journal.Entry{
		OpID: op.OpID, ItemID: op.ItemID, Type: op.Type, Value: op.Value,
		TsMs: op.ClientTs.UnixMilli(), ClientTs: op.ClientRaw,
		DeviceID: op.DeviceID, User: op.User, SessionID: op.SessionID,
		Received: received.Format(time.RFC3339Nano),
	}
	d := journal.Decide(past, entry)
	entry.Status = d.Status
	res.Status = d.Status

	quantity := backend.Quantity(item, e.quantity)
	if d.Effect != journal.None {
		kind := "delta"
		if d.Effect == journal.Write {
			kind = "set"
		}
		q, err := unit.Apply(ctx, backend.ApplyParams{
			Ref: op.ItemID, Kind: kind, Value: d.Value, SentValue: op.Value, OpID: op.OpID,
			ClientTs: op.ClientTs, User: op.User, DeviceID: op.DeviceID, SessionID: op.SessionID, Received: received,
		})
		if errors.Is(err, backend.ErrUnknownItem) {
			return unknown(op), nil
		}
		if r, ok := backend.IsRefused(err); ok {
			reason := r.Reason
			switch reason {
			case "invalid", "forbidden", "conflict":
			default:
				reason = "forbidden"
			}
			return Result{Status: "rejected", Reason: reason, Message: r.Error()}, nil
		}
		if err != nil {
			return res, err
		}
		if q != nil {
			quantity = q
		} else if quantity, err = e.quantityOf(ctx, unit, op.ItemID); err != nil {
			return res, err
		}
	}
	if err := e.journal.Insert(ctx, jtx, entry); err != nil {
		return res, fmt.Errorf("journal : %w", err)
	}
	if err := unit.Commit(); err != nil {
		return res, err
	}
	committed = true
	if !e.sameDB {
		if err := jtx.Commit(); err != nil {
			// Le stock est écrit mais l'opération n'est pas journalisée : un renvoi serait compté deux fois.
			e.log.Error("journal non enregistré après écriture du stock", "op_id", op.OpID, "err", err)
			return res, err
		}
	}
	res.Quantity = quantity
	return res, nil
}

func unknown(op operation) Result {
	return Result{Status: "rejected", Reason: "unknown_item", Message: "article inconnu : " + op.ItemID}
}

func (e *Engine) quantityOf(ctx context.Context, unit backend.Unit, ref string) (*int64, error) {
	item, err := unit.GetItem(ctx, ref)
	if err != nil || item == nil {
		return nil, err
	}
	return backend.Quantity(item, e.quantity), nil
}

// Journal renvoie le journal (diagnostic).
func (e *Engine) Journal() *journal.Journal { return e.journal }

// Package backend relie le Bridge au système de l'entreprise : base SQL ou script.
package backend

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

// Item : un article, colonne → valeur (chaîne, nombre ou booléen).
type Item map[string]any

// ErrUnknownItem : l'article n'existe pas dans le système de l'entreprise.
var ErrUnknownItem = errors.New("article inconnu")

// ListQuery : recherche d'articles (pour Kura Studio).
type ListQuery struct {
	Q            string
	UpdatedSince *time.Time
	Limit        int
	Cursor       string
}

// ApplyParams : une écriture décidée par la règle de stock.
type ApplyParams struct {
	Ref       string
	Kind      string // delta (stock = stock + Value) ou set (stock = Value)
	Value     int64  // valeur à écrire
	SentValue int64  // valeur envoyée par l'appareil
	OpID      string
	ClientTs  time.Time
	User      string
	DeviceID  string
	SessionID string
	Received  time.Time
}

// Store : la source de l'entreprise.
type Store interface {
	GetItem(ctx context.Context, ref string) (Item, error)
	ListItems(ctx context.Context, q ListQuery) ([]Item, string, error)
	// Image renvoie l'image d'un article (nil si aucune).
	Image(ctx context.Context, ref string) ([]byte, error)
	// Begin ouvre une unité de travail pour une opération.
	Begin(ctx context.Context) (Unit, error)
	// DB renvoie la base SQL et son dialecte quand le journal peut y vivre (nil pour un script).
	DB() (*sql.DB, string)
	Close() error
}

// Unit : lecture et écriture d'une opération, dans une transaction quand la source le permet.
type Unit interface {
	GetItem(ctx context.Context, ref string) (Item, error)
	// Apply écrit dans la base ; ErrUnknownItem si aucune ligne n'a été modifiée.
	// Renvoie le stock après écriture quand la source le donne (nil : relire l'article).
	Apply(ctx context.Context, p ApplyParams) (*int64, error)
	// Tx : transaction SQL partagée avec le journal (nil si la source n'en a pas).
	Tx() *sql.Tx
	Commit() error
	Rollback() error
}

func applyValues(p ApplyParams) map[string]any {
	return map[string]any{
		"ref":           p.Ref,
		"value":         p.Value,
		"sent_value":    p.SentValue,
		"type":          p.Kind,
		"op_id":         p.OpID,
		"client_ts":     p.ClientTs,
		"client_ts_iso": p.ClientTs.UTC().Format(time.RFC3339Nano),
		"user":          nullable(p.User),
		"device_id":     nullable(p.DeviceID),
		"session_id":    nullable(p.SessionID),
		"received_at":   p.Received,
	}
}

func nullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// ErrNotConfigured : la fonction n'est pas configurée (ex. pas de requête list_items).
var ErrNotConfigured = errors.New("non configuré")

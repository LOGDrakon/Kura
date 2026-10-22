// Package app assemble le Bridge à partir de la config : source, journal, moteur et serveur HTTP.
package app

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/backend"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/engine"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/journal"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/server"
)

// App : un Bridge prêt à servir.
type App struct {
	Config  *config.Config
	Store   backend.Store
	Journal *journal.Journal
	Engine  *engine.Engine
	Server  *server.Server
	closers []func() error
}

// Open ouvre la source et le journal (en créant sa table si besoin) et prépare le serveur.
func Open(ctx context.Context, c *config.Config, log *slog.Logger) (*App, error) {
	a := &App{Config: c}
	var err error
	if c.Database != nil {
		a.Store, err = backend.OpenSQL(c)
	} else {
		a.Store, err = backend.OpenScript(c)
	}
	if err != nil {
		return nil, err
	}
	a.closers = append(a.closers, a.Store.Close)

	if c.Journal.File != "" {
		path := c.Path(c.Journal.File)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			a.Close()
			return nil, err
		}
		db, err := sql.Open("sqlite", "file:"+path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(FULL)")
		if err != nil {
			a.Close()
			return nil, err
		}
		db.SetMaxOpenConns(1)
		a.closers = append(a.closers, db.Close)
		a.Journal = &journal.Journal{DB: db, Dialect: "sqlite", Table: c.Journal.Table}
	} else {
		db, dialect := a.Store.DB()
		a.Journal = &journal.Journal{DB: db, Dialect: dialect, Table: c.Journal.Table}
	}
	if *c.Journal.Create {
		if err := a.Journal.Create(ctx); err != nil {
			a.Close()
			return nil, err
		}
	}
	a.Engine = engine.New(a.Store, a.Journal, c.Items.QuantityColumn, log)
	a.Server, err = server.New(c, a.Store, a.Engine, log)
	if err != nil {
		a.Close()
		return nil, fmt.Errorf("remote_config : %w", err)
	}
	return a, nil
}

// Close libère les connexions.
func (a *App) Close() error {
	for i := len(a.closers) - 1; i >= 0; i-- {
		a.closers[i]()
	}
	return nil
}

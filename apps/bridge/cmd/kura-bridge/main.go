// kura-bridge relie Kura Scan et Kura Studio à la base de stock de l'entreprise.
//
//	kura-bridge [run] [-c kura-bridge.yaml]   démarre le serveur
//	kura-bridge check [-c …] [-ref REF]       vérifie la config, la base et les requêtes, sans rien écrire
//	kura-bridge keygen [-o kura-signing.key]  crée la clé de signature de la config distante
//	kura-bridge version
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/app"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/backend"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/signing"
)

// Remplacé à la compilation : -ldflags "-X main.version=1.0.0".
var version = "dev"

// runAsService est défini sous Windows quand le programme est lancé comme service.
var runAsService func(args []string) error

func main() {
	args := os.Args[1:]
	if runAsService != nil {
		if err := runAsService(args); err != nil {
			os.Exit(1)
		}
		return
	}
	cmd := "run"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	var err error
	switch cmd {
	case "run":
		err = run(args)
	case "check":
		err = check(args)
	case "keygen":
		err = keygen(args)
	case "version":
		fmt.Println("kura-bridge", version)
	case "help", "-h", "--help":
		usage()
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "erreur :", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `Kura Bridge `+version+` : relie Kura Scan et Kura Studio à votre base de stock.

  kura-bridge [run] [-c kura-bridge.yaml]    démarre le serveur
  kura-bridge check [-c …] [-ref REF]        vérifie config, base et requêtes (n'écrit rien dans le stock)
  kura-bridge keygen [-o kura-signing.key]   crée la clé de signature de la config distante
  kura-bridge version

Documentation : https://github.com/LOGDrakon/Kura/blob/main/docs/guides/bridge.md
`)
}

func configFlag(fs *flag.FlagSet) *string {
	def := os.Getenv("KURA_BRIDGE_CONFIG")
	if def == "" {
		def = "kura-bridge.yaml"
	}
	return fs.String("c", def, "fichier de configuration (ou variable KURA_BRIDGE_CONFIG)")
}

func newLogger(c *config.Config) *slog.Logger {
	level := slog.LevelInfo
	if strings.EqualFold(c.Log, "debug") {
		level = slog.LevelDebug
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
}

func run(args []string) error {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	path := configFlag(fs)
	fs.Parse(args)
	c, err := config.Load(*path)
	if err != nil {
		return err
	}
	log := newLogger(c)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return serve(ctx, c, log)
}

// serve démarre le Bridge et s'arrête proprement quand ctx est annulé (Ctrl+C, arrêt du service).
func serve(ctx context.Context, c *config.Config, log *slog.Logger) error {
	a, err := app.Open(ctx, c, log)
	if err != nil {
		return err
	}
	defer a.Close()
	if c.Auth.Type == "none" {
		log.Warn("authentification désactivée (auth.type: none) : à réserver aux essais")
	}
	srv := &http.Server{
		Addr:              c.Listen,
		Handler:           a.Server.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	errc := make(chan error, 1)
	go func() {
		var err error
		if c.TLS.Cert != "" {
			err = srv.ListenAndServeTLS(c.Path(c.TLS.Cert), c.Path(c.TLS.Key))
		} else {
			err = srv.ListenAndServe()
		}
		if !errors.Is(err, http.ErrServerClosed) {
			errc <- err
		}
		close(errc)
	}()
	source := "script"
	if c.Database != nil {
		source = c.Database.Driver
	}
	log.Info("Kura Bridge démarré", "version", version, "listen", c.Listen, "tls", c.TLS.Cert != "", "source", source, "journal", journalWhere(c))
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("arrêt…")
	shutdown, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return srv.Shutdown(shutdown)
}

func journalWhere(c *config.Config) string {
	if c.Journal.File != "" {
		return c.Path(c.Journal.File)
	}
	return "table " + c.Journal.Table
}

func check(args []string) error {
	fs := flag.NewFlagSet("check", flag.ExitOnError)
	path := configFlag(fs)
	ref := fs.String("ref", "", "référence d'un article existant, pour tester get_item")
	fs.Parse(args)
	c, err := config.Load(*path)
	if err != nil {
		return err
	}
	ok := func(format string, a ...any) { fmt.Printf("✓ "+format+"\n", a...) }
	ok("config %s lue", *path)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	a, err := app.Open(ctx, c, log)
	if err != nil {
		return err
	}
	defer a.Close()
	if db, _ := a.Store.DB(); db != nil {
		if err := db.PingContext(ctx); err != nil {
			return fmt.Errorf("connexion à la base : %w", err)
		}
		ok("connexion à la base %s", c.Database.Driver)
		ok("requêtes SQL préparées")
	} else {
		ok("script : %s", strings.Join(c.Script.Command, " "))
	}
	if err := a.Journal.Check(ctx); err != nil {
		return fmt.Errorf("journal (%s) : %w", journalWhere(c), err)
	}
	ok("journal : %s", journalWhere(c))

	items, _, err := a.Store.ListItems(ctx, backend.ListQuery{Limit: 3})
	switch {
	case errors.Is(err, backend.ErrNotConfigured):
		fmt.Println("– list_items non configurée : Kura Studio ne pourra pas lister les articles")
	case err != nil:
		return fmt.Errorf("list_items : %w", err)
	default:
		ok("list_items : %d article(s) lus (3 au plus)", len(items))
		if *ref == "" && len(items) > 0 {
			if v, found := items[0][c.Items.IDColumn]; found {
				*ref = fmt.Sprint(v)
			}
		}
	}
	if *ref == "" {
		fmt.Println("– get_item non testée : indiquez -ref REFERENCE")
		return nil
	}
	it, err := a.Store.GetItem(ctx, *ref)
	if err != nil {
		return fmt.Errorf("get_item : %w", err)
	}
	if it == nil {
		return fmt.Errorf("get_item : article %q introuvable", *ref)
	}
	out, _ := json.MarshalIndent(it, "  ", "  ")
	ok("get_item %q :\n  %s", *ref, out)
	if backend.Quantity(it, c.Items.QuantityColumn) == nil {
		return fmt.Errorf("get_item doit renvoyer le stock dans la colonne %q (items.quantity_column)", c.Items.QuantityColumn)
	}
	ok("stock lu dans la colonne %q", c.Items.QuantityColumn)
	return nil
}

func keygen(args []string) error {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	out := fs.String("o", "kura-signing.key", "fichier de la clé privée (créé, jamais écrasé)")
	fs.Parse(args)
	pub, err := signing.Generate(*out)
	if err != nil {
		return err
	}
	fmt.Printf("Clé privée écrite dans %s (à garder secrète, à côté du Bridge).\n\n", *out)
	fmt.Println("Clé publique, à placer dans kura-config.json :")
	fmt.Printf("  \"remote_config\": { \"signing_public_key\": %q }\n", pub)
	return nil
}

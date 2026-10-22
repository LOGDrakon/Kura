// Package config lit et vérifie kura-bridge.yaml.
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// Config : contenu de kura-bridge.yaml.
type Config struct {
	Listen  string   `yaml:"listen"`
	TLS     TLS      `yaml:"tls"`
	Auth    Auth     `yaml:"auth"`
	CORS    []string `yaml:"cors_origins"`
	Log     string   `yaml:"log"`
	Journal Journal  `yaml:"journal"`

	// Une seule source : base SQL ou script.
	Database *Database `yaml:"database"`
	Script   *Script   `yaml:"script"`

	Queries Queries `yaml:"queries"`
	Items   Items   `yaml:"items"`
	Image   Image   `yaml:"image"`
	Remote  Remote  `yaml:"remote_config"`

	// Dossier du fichier de config : les chemins relatifs s'y rapportent.
	Dir string `yaml:"-"`
}

type TLS struct {
	Cert string `yaml:"cert"`
	Key  string `yaml:"key"`
}

type Auth struct {
	// none ou bearer.
	Type   string   `yaml:"type"`
	Tokens []string `yaml:"tokens"`
}

type Journal struct {
	// Table du journal dans la base de l'entreprise (même transaction que la mise à jour du stock).
	Table string `yaml:"table"`
	// Ou fichier SQLite à part, si la base ne peut pas accueillir de table (lecture seule, droits limités).
	File string `yaml:"file"`
	// Crée la table au démarrage si elle n'existe pas (par défaut : oui).
	Create *bool `yaml:"create"`
}

type Database struct {
	// postgres, mysql, sqlserver ou sqlite.
	Driver      string `yaml:"driver"`
	DSN         string `yaml:"dsn"`
	MaxOpen     int    `yaml:"max_open_conns"`
	TimeoutSecs int    `yaml:"timeout_s"`
}

type Script struct {
	Command     []string          `yaml:"command"`
	Env         map[string]string `yaml:"env"`
	TimeoutSecs int               `yaml:"timeout_s"`
}

// Queries : requêtes SQL avec paramètres nommés (voir docs/guides/bridge.md).
type Queries struct {
	GetItem    string `yaml:"get_item"`
	ListItems  string `yaml:"list_items"`
	ApplyDelta string `yaml:"apply_delta"`
	ApplySet   string `yaml:"apply_set"`
	AfterApply string `yaml:"after_apply"`
	GetImage   string `yaml:"get_image"`
}

type Items struct {
	// Colonne qui identifie l'article (par défaut ref).
	IDColumn string `yaml:"id_column"`
	// Colonne du stock (par défaut quantity).
	QuantityColumn string `yaml:"quantity_column"`
}

type Image struct {
	// Redirection vers une URL (ex. https://intranet/photos/{ref}.jpg).
	URL string `yaml:"url"`
}

type Remote struct {
	File       string `yaml:"file"`
	SigningKey string `yaml:"signing_key_file"`
}

// Paramètres disponibles dans chaque requête.
var (
	ItemParams  = []string{"ref"}
	ListParams  = []string{"q", "q_like", "updated_since", "updated_since_iso", "limit", "offset"}
	ApplyParams = []string{"ref", "value", "sent_value", "type", "op_id", "client_ts", "client_ts_iso", "user", "device_id", "session_id", "received_at"}
)

var envRef = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}`)
var identRe = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_.]{0,127}$`)

// Load lit le fichier, remplace les ${VARIABLE} par l'environnement, complète les valeurs par défaut et vérifie.
func Load(path string) (*Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var missing []string
	text := envRef.ReplaceAllStringFunc(string(raw), func(m string) string {
		name := envRef.FindStringSubmatch(m)[1]
		v, ok := os.LookupEnv(name)
		if !ok {
			missing = append(missing, name)
		}
		return v
	})
	if len(missing) > 0 {
		return nil, fmt.Errorf("variables d'environnement absentes : %s", strings.Join(missing, ", "))
	}
	var c Config
	dec := yaml.NewDecoder(strings.NewReader(text))
	dec.KnownFields(true)
	if err := dec.Decode(&c); err != nil {
		return nil, fmt.Errorf("%s : %w", path, err)
	}
	abs, _ := filepath.Abs(path)
	c.Dir = filepath.Dir(abs)
	c.defaults()
	return &c, c.validate()
}

func (c *Config) defaults() {
	if c.Listen == "" {
		c.Listen = ":8787"
	}
	if c.Auth.Type == "" {
		c.Auth.Type = "bearer"
	}
	if len(c.CORS) == 0 {
		c.CORS = []string{"*"}
	}
	if c.Items.IDColumn == "" {
		c.Items.IDColumn = "ref"
	}
	if c.Items.QuantityColumn == "" {
		c.Items.QuantityColumn = "quantity"
	}
	if c.Journal.Table == "" {
		c.Journal.Table = "kura_operations"
	}
	if c.Journal.Create == nil {
		yes := true
		c.Journal.Create = &yes
	}
	if c.Script != nil && c.Journal.File == "" {
		c.Journal.File = "kura-journal.db"
	}
	if c.Database != nil && c.Database.TimeoutSecs == 0 {
		c.Database.TimeoutSecs = 10
	}
	if c.Script != nil && c.Script.TimeoutSecs == 0 {
		c.Script.TimeoutSecs = 15
	}
}

func (c *Config) validate() error {
	var errs []error
	add := func(format string, a ...any) { errs = append(errs, fmt.Errorf(format, a...)) }

	switch {
	case c.Database == nil && c.Script == nil:
		add("indiquez une source : database (SQL) ou script")
	case c.Database != nil && c.Script != nil:
		add("database et script sont exclusifs")
	}
	if d := c.Database; d != nil {
		switch d.Driver {
		case "postgres", "mysql", "sqlserver", "sqlite":
		default:
			add("database.driver : postgres, mysql, sqlserver ou sqlite (reçu %q)", d.Driver)
		}
		if d.DSN == "" {
			add("database.dsn est obligatoire")
		}
		if c.Queries.GetItem == "" {
			add("queries.get_item est obligatoire")
		}
		if c.Queries.ApplyDelta == "" || c.Queries.ApplySet == "" {
			add("queries.apply_delta et queries.apply_set sont obligatoires")
		}
	}
	if s := c.Script; s != nil && len(s.Command) == 0 {
		add("script.command est obligatoire (ex. [\"python3\", \"adaptateur.py\"])")
	}
	switch c.Auth.Type {
	case "none":
	case "bearer":
		if len(c.Auth.Tokens) == 0 {
			add("auth.tokens : au moins un jeton (ou auth.type: none pour un essai)")
		}
		for _, t := range c.Auth.Tokens {
			if len(t) < 16 {
				add("auth.tokens : chaque jeton doit faire au moins 16 caractères")
				break
			}
		}
	default:
		add("auth.type : none ou bearer (reçu %q)", c.Auth.Type)
	}
	if (c.TLS.Cert == "") != (c.TLS.Key == "") {
		add("tls.cert et tls.key vont ensemble")
	}
	if !identRe.MatchString(c.Journal.Table) {
		add("journal.table : nom de table invalide %q", c.Journal.Table)
	}
	return errors.Join(errs...)
}

// Path résout un chemin relatif au dossier du fichier de config.
func (c *Config) Path(p string) string {
	if p == "" || filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(c.Dir, p)
}

func (d *Database) Timeout() time.Duration { return time.Duration(d.TimeoutSecs) * time.Second }
func (s *Script) Timeout() time.Duration   { return time.Duration(s.TimeoutSecs) * time.Second }

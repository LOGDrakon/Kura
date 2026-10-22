package backend

import (
	"context"
	"database/sql"
	"fmt"
	"math"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/named"

	_ "github.com/go-sql-driver/mysql"
	_ "github.com/jackc/pgx/v5/stdlib"
	_ "github.com/microsoft/go-mssqldb"
	_ "modernc.org/sqlite"
)

// SQLStore : articles et stock dans une base PostgreSQL, MySQL/MariaDB, SQL Server ou SQLite.
type SQLStore struct {
	db      *sql.DB
	dialect string
	timeout time.Duration
	get     named.Query
	list    *named.Query
	delta   named.Query
	set     named.Query
	after   *named.Query
	image   *named.Query
}

var drivers = map[string]struct {
	name  string
	style named.Style
}{
	"postgres":  {"pgx", named.Dollar},
	"mysql":     {"mysql", named.Question},
	"sqlserver": {"sqlserver", named.AtP},
	"sqlite":    {"sqlite", named.Question},
}

// OpenSQL ouvre la base et prépare les requêtes de la config.
func OpenSQL(c *config.Config) (*SQLStore, error) {
	d := c.Database
	drv := drivers[d.Driver]
	dsn := d.DSN
	if d.Driver == "mysql" && !strings.Contains(dsn, "clientFoundRows") {
		// Sinon MySQL compte 0 ligne quand la valeur ne change pas, et l'article passerait pour inconnu.
		dsn += sep(dsn) + "clientFoundRows=true"
	}
	if d.Driver == "mysql" && !strings.Contains(dsn, "parseTime") {
		// Dates DATETIME lues comme des dates (et non du texte « 2026-10-22 08:00:00 »).
		dsn += sep(dsn) + "parseTime=true"
	}
	if d.Driver == "sqlite" {
		// Chemin relatif : par rapport au dossier du fichier de config, comme les autres chemins.
		path, query, _ := strings.Cut(strings.TrimPrefix(dsn, "file:"), "?")
		if path != ":memory:" && !filepath.IsAbs(path) {
			dsn = "file:" + c.Path(path)
			if query != "" {
				dsn += "?" + query
			}
		}
	}
	if d.Driver == "sqlite" && !strings.Contains(dsn, "_pragma=busy_timeout") {
		dsn += sep(dsn) + "_pragma=busy_timeout(5000)"
	}
	db, err := sql.Open(drv.name, dsn)
	if err != nil {
		return nil, err
	}
	if d.MaxOpen > 0 {
		db.SetMaxOpenConns(d.MaxOpen)
	}
	if d.Driver == "sqlite" {
		db.SetMaxOpenConns(1) // un seul écrivain : évite « database is locked »
	}
	s := &SQLStore{db: db, dialect: d.Driver, timeout: d.Timeout()}
	compile := func(name, text string, allowed []string) (named.Query, error) {
		q, err := named.Compile(text, drv.style)
		if err == nil {
			err = q.Check(allowed)
		}
		if err != nil {
			return q, fmt.Errorf("queries.%s : %w", name, err)
		}
		return q, nil
	}
	optional := func(name, text string, allowed []string) (*named.Query, error) {
		if strings.TrimSpace(text) == "" {
			return nil, nil
		}
		q, err := compile(name, text, allowed)
		return &q, err
	}
	qs := c.Queries
	var errs []string
	collect := func(err error) {
		if err != nil {
			errs = append(errs, err.Error())
		}
	}
	s.get, err = compile("get_item", qs.GetItem, config.ItemParams)
	collect(err)
	s.list, err = optional("list_items", qs.ListItems, config.ListParams)
	collect(err)
	s.delta, err = compile("apply_delta", qs.ApplyDelta, config.ApplyParams)
	collect(err)
	s.set, err = compile("apply_set", qs.ApplySet, config.ApplyParams)
	collect(err)
	s.after, err = optional("after_apply", qs.AfterApply, config.ApplyParams)
	collect(err)
	s.image, err = optional("get_image", qs.GetImage, config.ItemParams)
	collect(err)
	if len(errs) > 0 {
		db.Close()
		return nil, fmt.Errorf("%s", strings.Join(errs, "\n"))
	}
	return s, nil
}

func sep(dsn string) string {
	if strings.Contains(dsn, "?") {
		return "&"
	}
	return "?"
}

func (s *SQLStore) DB() (*sql.DB, string) { return s.db, s.dialect }
func (s *SQLStore) Close() error          { return s.db.Close() }

// Ping vérifie la connexion.
func (s *SQLStore) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

func (s *SQLStore) GetItem(ctx context.Context, ref string) (Item, error) {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	return getItem(ctx, s.db, s.get, ref)
}

func getItem(ctx context.Context, q querier, query named.Query, ref string) (Item, error) {
	items, err := queryItems(ctx, q, query, map[string]any{"ref": ref})
	if err != nil || len(items) == 0 {
		return nil, err
	}
	return items[0], nil
}

func (s *SQLStore) ListItems(ctx context.Context, lq ListQuery) ([]Item, string, error) {
	if s.list == nil {
		return nil, "", ErrNotConfigured
	}
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	offset, _ := strconv.Atoi(lq.Cursor)
	if offset < 0 {
		offset = 0
	}
	since := time.Unix(0, 0).UTC()
	if lq.UpdatedSince != nil {
		since = lq.UpdatedSince.UTC()
	}
	q := strings.TrimSpace(lq.Q)
	// Une ligne de plus que demandé : indique s'il reste une page.
	items, err := queryItems(ctx, s.db, *s.list, map[string]any{
		"q":                 q,
		"q_like":            "%" + strings.ToLower(q) + "%",
		"updated_since":     since,
		"updated_since_iso": since.Format(time.RFC3339),
		"limit":             lq.Limit + 1,
		"offset":            offset,
	})
	if err != nil {
		return nil, "", err
	}
	next := ""
	if len(items) > lq.Limit {
		items = items[:lq.Limit]
		next = strconv.Itoa(offset + lq.Limit)
	}
	return items, next, nil
}

func (s *SQLStore) Image(ctx context.Context, ref string) ([]byte, error) {
	if s.image == nil {
		return nil, ErrNotConfigured
	}
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	args, err := s.image.Args(map[string]any{"ref": ref})
	if err != nil {
		return nil, err
	}
	var data []byte
	err = s.db.QueryRowContext(ctx, s.image.Text, args...).Scan(&data)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	return data, err
}

func (s *SQLStore) Begin(ctx context.Context) (Unit, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	return &sqlUnit{s: s, tx: tx}, nil
}

type sqlUnit struct {
	s  *SQLStore
	tx *sql.Tx
}

func (u *sqlUnit) Tx() *sql.Tx     { return u.tx }
func (u *sqlUnit) Commit() error   { return u.tx.Commit() }
func (u *sqlUnit) Rollback() error { return u.tx.Rollback() }

func (u *sqlUnit) GetItem(ctx context.Context, ref string) (Item, error) {
	return getItem(ctx, u.tx, u.s.get, ref)
}

func (u *sqlUnit) Apply(ctx context.Context, p ApplyParams) (*int64, error) {
	values := applyValues(p)
	q := u.s.delta
	if p.Kind == "set" {
		q = u.s.set
	}
	args, err := q.Args(values)
	if err != nil {
		return nil, err
	}
	res, err := u.tx.ExecContext(ctx, q.Text, args...)
	if err != nil {
		return nil, fmt.Errorf("apply_%s : %w", p.Kind, err)
	}
	if n, err := res.RowsAffected(); err == nil && n == 0 {
		return nil, ErrUnknownItem
	}
	if u.s.after != nil {
		args, err := u.s.after.Args(values)
		if err != nil {
			return nil, err
		}
		if _, err := u.tx.ExecContext(ctx, u.s.after.Text, args...); err != nil {
			return nil, fmt.Errorf("after_apply : %w", err)
		}
	}
	return nil, nil
}

func queryItems(ctx context.Context, q querier, query named.Query, values map[string]any) ([]Item, error) {
	args, err := query.Args(values)
	if err != nil {
		return nil, err
	}
	rows, err := q.QueryContext(ctx, query.Text, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	var out []Item
	for rows.Next() {
		raw := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range raw {
			ptrs[i] = &raw[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return nil, err
		}
		it := Item{}
		for i, c := range cols {
			if v := jsonValue(raw[i]); v != nil {
				it[c] = v
			}
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// jsonValue convertit une valeur SQL en valeur JSON simple ; nil = colonne omise.
func jsonValue(v any) any {
	switch x := v.(type) {
	case nil:
		return nil
	case []byte:
		return string(x)
	case string, bool, int64:
		return x
	case int32:
		return int64(x)
	case int:
		return int64(x)
	case float32:
		return jsonFloat(float64(x))
	case float64:
		return jsonFloat(x)
	case time.Time:
		return x.UTC().Format(time.RFC3339)
	case fmt.Stringer:
		return x.String()
	default:
		return fmt.Sprint(x)
	}
}

func jsonFloat(f float64) any {
	if f == math.Trunc(f) && math.Abs(f) < 1<<53 {
		return int64(f)
	}
	return f
}

// Quantity lit le stock d'un article (nil si absent ou illisible).
func Quantity(it Item, column string) *int64 {
	var n int64
	switch v := it[column].(type) {
	case int64:
		n = v
	case float64:
		n = int64(math.Round(v))
	case string:
		f, err := strconv.ParseFloat(strings.Replace(strings.TrimSpace(v), ",", ".", 1), 64)
		if err != nil {
			return nil
		}
		n = int64(math.Round(f))
	default:
		return nil
	}
	return &n
}

package journal

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

// Journal : la table des opérations, dans la base de l'entreprise ou dans un fichier SQLite à part.
type Journal struct {
	DB      *sql.DB
	Dialect string // postgres, mysql, sqlserver, sqlite
	Table   string
}

func (j *Journal) ph(n int) string {
	switch j.Dialect {
	case "postgres":
		return fmt.Sprintf("$%d", n)
	case "sqlserver":
		return fmt.Sprintf("@p%d", n)
	default:
		return "?"
	}
}

// Create crée la table si elle n'existe pas.
func (j *Journal) Create(ctx context.Context) error {
	cols := `op_id VARCHAR(36) NOT NULL PRIMARY KEY,
  item_id VARCHAR(128) NOT NULL,
  op_type VARCHAR(8) NOT NULL,
  op_value BIGINT NOT NULL,
  client_ts_ms BIGINT NOT NULL,
  client_ts VARCHAR(40) NOT NULL,
  device_id VARCHAR(128),
  user_name VARCHAR(128),
  session_id VARCHAR(128),
  status VARCHAR(16) NOT NULL,
  received_at VARCHAR(40) NOT NULL`
	idx := j.Table + "_item"
	var stmts []string
	switch j.Dialect {
	case "sqlserver":
		stmts = []string{fmt.Sprintf("IF OBJECT_ID(N'%s', N'U') IS NULL BEGIN CREATE TABLE %s (%s); CREATE INDEX %s ON %s (item_id); END", j.Table, j.Table, cols, idx, j.Table)}
	case "mysql":
		stmts = []string{fmt.Sprintf("CREATE TABLE IF NOT EXISTS %s (%s, INDEX %s (item_id))", j.Table, cols, idx)}
	default:
		stmts = []string{
			fmt.Sprintf("CREATE TABLE IF NOT EXISTS %s (%s)", j.Table, cols),
			fmt.Sprintf("CREATE INDEX IF NOT EXISTS %s ON %s (item_id)", idx, j.Table),
		}
	}
	for _, s := range stmts {
		if _, err := j.DB.ExecContext(ctx, s); err != nil {
			return fmt.Errorf("création de la table %s : %w", j.Table, err)
		}
	}
	return nil
}

// Find renvoie l'opération déjà reçue sous cet op_id, s'il y en a une.
func (j *Journal) Find(ctx context.Context, tx *sql.Tx, opID string) (*Entry, error) {
	row := tx.QueryRowContext(ctx, fmt.Sprintf("SELECT item_id, status FROM %s WHERE op_id = %s", j.Table, j.ph(1)), opID)
	var e Entry
	if err := row.Scan(&e.ItemID, &e.Status); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	e.OpID = opID
	return &e, nil
}

// ForItem renvoie les opérations enregistrées d'un article (celles utiles à la règle de stock).
func (j *Journal) ForItem(ctx context.Context, tx *sql.Tx, itemID string) ([]Entry, error) {
	rows, err := tx.QueryContext(ctx, fmt.Sprintf("SELECT op_id, op_type, op_value, client_ts_ms FROM %s WHERE item_id = %s", j.Table, j.ph(1)), itemID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Entry
	for rows.Next() {
		e := Entry{ItemID: itemID}
		if err := rows.Scan(&e.OpID, &e.Type, &e.Value, &e.TsMs); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// Insert enregistre une opération traitée.
func (j *Journal) Insert(ctx context.Context, tx *sql.Tx, e Entry) error {
	q := fmt.Sprintf(`INSERT INTO %s (op_id, item_id, op_type, op_value, client_ts_ms, client_ts, device_id, user_name, session_id, status, received_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)`, j.Table,
		j.ph(1), j.ph(2), j.ph(3), j.ph(4), j.ph(5), j.ph(6), j.ph(7), j.ph(8), j.ph(9), j.ph(10), j.ph(11))
	_, err := tx.ExecContext(ctx, q, e.OpID, e.ItemID, e.Type, e.Value, e.TsMs, e.ClientTs,
		nullable(e.DeviceID), nullable(e.User), nullable(e.SessionID), e.Status, e.Received)
	return err
}

func nullable(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// Check vérifie que la table du journal est lisible.
func (j *Journal) Check(ctx context.Context) error {
	rows, err := j.DB.QueryContext(ctx, fmt.Sprintf("SELECT op_id FROM %s WHERE op_id = %s", j.Table, j.ph(1)), "")
	if err != nil {
		return err
	}
	return rows.Close()
}

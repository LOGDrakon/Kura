// Package named convertit les paramètres nommés des requêtes de la config (:ref, :value…)
// en paramètres positionnels du pilote SQL ($1, ?, @p1).
package named

import (
	"fmt"
	"strings"
)

// Style de paramètres positionnels attendu par un pilote.
type Style int

const (
	Question Style = iota // ? (MySQL, SQLite)
	Dollar                // $1 (PostgreSQL)
	AtP                   // @p1 (SQL Server)
)

// Query est une requête prête à exécuter : texte pour le pilote et noms des paramètres dans l'ordre.
type Query struct {
	Text  string
	Names []string
}

// Compile remplace chaque :nom par le paramètre positionnel du style donné. Les chaînes ('…', "…", `…`, […]),
// les commentaires (--, /* */) et les conversions PostgreSQL (::type) ne sont pas touchés.
func Compile(query string, style Style) (Query, error) {
	var b strings.Builder
	var names []string
	index := map[string]int{}
	r := []rune(query)
	for i := 0; i < len(r); i++ {
		c := r[i]
		switch {
		case c == '\'' || c == '"' || c == '`':
			j := closing(r, i, c)
			if j < 0 {
				return Query{}, fmt.Errorf("guillemet %q non fermé", c)
			}
			b.WriteString(string(r[i : j+1]))
			i = j
		case c == '-' && i+1 < len(r) && r[i+1] == '-':
			j := i
			for j < len(r) && r[j] != '\n' {
				j++
			}
			b.WriteString(string(r[i:j]))
			i = j - 1
		case c == '/' && i+1 < len(r) && r[i+1] == '*':
			end := strings.Index(string(r[i+2:]), "*/")
			if end < 0 {
				return Query{}, fmt.Errorf("commentaire /* non fermé")
			}
			n := len([]rune(string(r[i+2:])[:end])) + 4
			b.WriteString(string(r[i : i+n]))
			i += n - 1
		case c == ':' && i+1 < len(r) && r[i+1] == ':':
			b.WriteString("::")
			i++
		case c == ':' && i+1 < len(r) && isStart(r[i+1]) && (i == 0 || !isPart(r[i-1])):
			j := i + 1
			for j < len(r) && isPart(r[j]) {
				j++
			}
			name := string(r[i+1 : j])
			switch style {
			case Question:
				names = append(names, name)
				b.WriteByte('?')
			default:
				n, ok := index[name]
				if !ok {
					names = append(names, name)
					n = len(names)
					index[name] = n
				}
				if style == Dollar {
					fmt.Fprintf(&b, "$%d", n)
				} else {
					fmt.Fprintf(&b, "@p%d", n)
				}
			}
			i = j - 1
		default:
			b.WriteRune(c)
		}
	}
	return Query{Text: b.String(), Names: names}, nil
}

// Args construit les arguments du pilote à partir des valeurs nommées.
func (q Query) Args(values map[string]any) ([]any, error) {
	args := make([]any, len(q.Names))
	for i, n := range q.Names {
		v, ok := values[n]
		if !ok {
			return nil, fmt.Errorf("paramètre :%s inconnu", n)
		}
		args[i] = v
	}
	return args, nil
}

// Check vérifie que la requête n'utilise que des paramètres de la liste autorisée.
func (q Query) Check(allowed []string) error {
	ok := map[string]bool{}
	for _, a := range allowed {
		ok[a] = true
	}
	for _, n := range q.Names {
		if !ok[n] {
			return fmt.Errorf("paramètre :%s non disponible ici (possibles : :%s)", n, strings.Join(allowed, ", :"))
		}
	}
	return nil
}

func closing(r []rune, start int, quote rune) int {
	for j := start + 1; j < len(r); j++ {
		if r[j] == quote {
			if j+1 < len(r) && r[j+1] == quote { // guillemet doublé = échappé
				j++
				continue
			}
			return j
		}
	}
	return -1
}

func isStart(c rune) bool {
	return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

func isPart(c rune) bool {
	return isStart(c) || (c >= '0' && c <= '9')
}

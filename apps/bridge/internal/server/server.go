// Package server expose le contrat d'API Kura (spec/api-contract.yaml) en HTTP.
package server

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/LOGDrakon/Kura/apps/bridge/internal/backend"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/config"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/engine"
	"github.com/LOGDrakon/Kura/apps/bridge/internal/signing"
)

const (
	maxBatch = 500
	maxBody  = 4 << 20
)

// Server : les routes du contrat.
type Server struct {
	cfg    *config.Config
	store  backend.Store
	engine *engine.Engine
	log    *slog.Logger
	tokens [][]byte
	key    ed25519.PrivateKey
}

// New prépare le serveur ; charge la clé de signature de la config distante si elle est configurée.
func New(c *config.Config, store backend.Store, e *engine.Engine, log *slog.Logger) (*Server, error) {
	s := &Server{cfg: c, store: store, engine: e, log: log}
	for _, t := range c.Auth.Tokens {
		s.tokens = append(s.tokens, []byte(t))
	}
	if c.Remote.SigningKey != "" {
		k, err := signing.LoadPrivateKey(c.Path(c.Remote.SigningKey))
		if err != nil {
			return nil, err
		}
		s.key = k
	}
	return s, nil
}

// Handler renvoie le routeur HTTP complet.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.Handle("GET /items", s.auth(s.listItems))
	mux.Handle("GET /items/{ref}", s.auth(s.getItem))
	mux.Handle("GET /items/{ref}/image", s.auth(s.image))
	mux.Handle("POST /operations", s.auth(s.operation))
	mux.Handle("POST /operations/batch", s.auth(s.batch))
	mux.Handle("GET /config", s.auth(s.remoteConfig))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "route inconnue : "+r.Method+" "+r.URL.Path)
	})
	return s.cors(mux)
}

func (s *Server) cors(next http.Handler) http.Handler {
	star := false
	allowed := map[string]bool{}
	for _, o := range s.cfg.CORS {
		if o == "*" {
			star = true
		}
		allowed[strings.TrimRight(o, "/")] = true
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && (star || allowed[origin]) {
			h := w.Header()
			if star {
				h.Set("Access-Control-Allow-Origin", "*")
			} else {
				h.Set("Access-Control-Allow-Origin", origin)
				h.Add("Vary", "Origin")
			}
			h.Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")
			h.Set("Access-Control-Max-Age", "600")
			// Studio ouvert depuis Internet ou un fichier, Bridge sur le réseau local (Chrome, Private Network Access).
			if r.Header.Get("Access-Control-Request-Private-Network") == "true" {
				h.Set("Access-Control-Allow-Private-Network", "true")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		start := time.Now()
		next.ServeHTTP(w, r)
		s.log.Debug("requête", "method", r.Method, "path", r.URL.Path, "duration", time.Since(start).String())
	})
}

func (s *Server) auth(h http.HandlerFunc) http.Handler {
	if s.cfg.Auth.Type == "none" {
		return h
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-Api-Key")
		if a := r.Header.Get("Authorization"); len(a) > 7 && strings.EqualFold(a[:7], "bearer ") {
			got = strings.TrimSpace(a[7:])
		}
		ok := 0
		for _, t := range s.tokens {
			ok |= subtle.ConstantTimeCompare([]byte(got), t)
		}
		if got == "" || ok != 1 {
			w.Header().Set("WWW-Authenticate", `Bearer realm="kura"`)
			writeError(w, http.StatusUnauthorized, "unauthorized", "jeton absent ou invalide")
			return
		}
		h(w, r)
	})
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if db, _ := s.store.DB(); db != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()
		if err := db.PingContext(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "degraded", "message": "base injoignable"})
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) listItems(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Query()
	q := backend.ListQuery{Q: p.Get("q"), Limit: 50, Cursor: p.Get("cursor")}
	if l := p.Get("limit"); l != "" {
		n, err := strconv.Atoi(l)
		if err != nil || n < 1 || n > 500 {
			writeError(w, http.StatusBadRequest, "invalid", "limit : entier de 1 à 500")
			return
		}
		q.Limit = n
	}
	if u := p.Get("updated_since"); u != "" {
		t, err := time.Parse(time.RFC3339Nano, u)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid", "updated_since : date ISO 8601 avec fuseau")
			return
		}
		q.UpdatedSince = &t
	}
	if c := q.Cursor; c != "" && s.cfg.Database != nil {
		if n, err := strconv.Atoi(c); err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "invalid", "cursor invalide")
			return
		}
	}
	items, next, err := s.store.ListItems(r.Context(), q)
	if errors.Is(err, backend.ErrNotConfigured) {
		writeError(w, http.StatusNotFound, "not_supported", "liste des articles non configurée (queries.list_items)")
		return
	}
	if err != nil {
		s.fail(w, "list_items", err)
		return
	}
	if items == nil {
		items = []backend.Item{}
	}
	for _, it := range items {
		s.decorate(it)
	}
	body := map[string]any{"items": items}
	if next != "" {
		body["next_cursor"] = next
	}
	writeJSON(w, http.StatusOK, body)
}

func (s *Server) getItem(w http.ResponseWriter, r *http.Request) {
	ref := r.PathValue("ref")
	it, err := s.store.GetItem(r.Context(), ref)
	if err != nil {
		s.fail(w, "get_item", err)
		return
	}
	if it == nil {
		writeError(w, http.StatusNotFound, "not_found", "article inconnu : "+ref)
		return
	}
	s.decorate(it)
	writeJSON(w, http.StatusOK, it)
}

// decorate complète un article pour le contrat : ref, quantity entière, image_url.
func (s *Server) decorate(it backend.Item) {
	id := s.cfg.Items.IDColumn
	if _, ok := it["ref"]; !ok {
		if v, ok := it[id]; ok {
			it["ref"] = fmt.Sprint(v)
		}
	}
	if q := backend.Quantity(it, s.cfg.Items.QuantityColumn); q != nil {
		it["quantity"] = *q
	}
	if _, ok := it["image_url"]; !ok && (s.cfg.Image.URL != "" || s.cfg.Queries.GetImage != "") {
		if ref, ok := it["ref"].(string); ok {
			it["image_url"] = "/items/" + url.PathEscape(ref) + "/image"
		}
	}
}

func (s *Server) image(w http.ResponseWriter, r *http.Request) {
	ref := r.PathValue("ref")
	if tpl := s.cfg.Image.URL; tpl != "" {
		http.Redirect(w, r, strings.ReplaceAll(tpl, "{ref}", url.PathEscape(ref)), http.StatusFound)
		return
	}
	data, err := s.store.Image(r.Context(), ref)
	if errors.Is(err, backend.ErrNotConfigured) || (err == nil && len(data) == 0) {
		writeError(w, http.StatusNotFound, "not_found", "pas d'image")
		return
	}
	if err != nil {
		s.fail(w, "get_image", err)
		return
	}
	sum := sha256.Sum256(data)
	etag := `"` + hex.EncodeToString(sum[:8]) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "max-age=3600")
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", http.DetectContentType(data))
	w.Write(data)
}

func (s *Server) operation(w http.ResponseWriter, r *http.Request) {
	var raw json.RawMessage
	if !readJSON(w, r, &raw) {
		return
	}
	res, err := s.engine.Apply(r.Context(), raw)
	if err != nil {
		s.unavailable(w, err)
		return
	}
	writeJSON(w, http.StatusOK, res)
}

func (s *Server) batch(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Operations []json.RawMessage `json:"operations"`
	}
	if !readJSON(w, r, &body) {
		return
	}
	if len(body.Operations) == 0 || len(body.Operations) > maxBatch {
		writeError(w, http.StatusBadRequest, "invalid", fmt.Sprintf("'operations' doit être une liste de 1 à %d éléments", maxBatch))
		return
	}
	results := make([]engine.Result, 0, len(body.Operations))
	for _, raw := range body.Operations {
		res, err := s.engine.Apply(r.Context(), raw)
		if err != nil {
			// Les opérations déjà traitées sont journalisées : au renvoi du lot, elles reviendront « duplicate ».
			s.unavailable(w, err)
			return
		}
		results = append(results, res)
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func (s *Server) remoteConfig(w http.ResponseWriter, r *http.Request) {
	if s.cfg.Remote.File == "" {
		writeError(w, http.StatusNotFound, "not_found", "pas de config distante")
		return
	}
	raw, err := os.ReadFile(s.cfg.Path(s.cfg.Remote.File))
	if err != nil {
		s.fail(w, "remote_config", err)
		return
	}
	if !json.Valid(raw) {
		s.fail(w, "remote_config", errors.New("le fichier n'est pas du JSON valide"))
		return
	}
	body := map[string]string{"config": string(raw)}
	if s.key != nil {
		body["signature"] = base64.StdEncoding.EncodeToString(ed25519.Sign(s.key, raw))
	}
	writeJSON(w, http.StatusOK, body)
}

func (s *Server) fail(w http.ResponseWriter, what string, err error) {
	s.log.Error(what, "err", err)
	writeError(w, http.StatusServiceUnavailable, "server_error", what+" : "+err.Error())
}

func (s *Server) unavailable(w http.ResponseWriter, err error) {
	w.Header().Set("Retry-After", "30")
	writeError(w, http.StatusServiceUnavailable, "server_error", "système de l'entreprise indisponible, réessayez plus tard : "+err.Error())
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBody))
	if err != nil {
		writeError(w, http.StatusRequestEntityTooLarge, "invalid", "requête trop volumineuse")
		return false
	}
	if err := json.Unmarshal(data, v); err != nil {
		writeError(w, http.StatusBadRequest, "invalid", "JSON illisible : "+err.Error())
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

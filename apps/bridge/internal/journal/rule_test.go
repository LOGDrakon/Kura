package journal

import "testing"

// Stock simulé : ce que ferait la base de l'entreprise avec les écritures décidées.
type sim struct {
	stock int64
	past  []Entry
}

func (s *sim) apply(op Entry) Decision {
	d := Decide(s.past, op)
	switch d.Effect {
	case Add:
		s.stock += d.Value
	case Write:
		s.stock = d.Value
	}
	op.Status = d.Status
	s.past = append(s.past, op)
	return d
}

func op(id, typ string, value, ts int64) Entry {
	return Entry{OpID: id, Type: typ, Value: value, TsMs: ts}
}

func TestSetEffaceLesDeltaAnterieurs(t *testing.T) {
	s := &sim{stock: 10}
	s.apply(op("a", "delta", -2, 13))
	s.apply(op("b", "set", 30, 12))
	s.apply(op("c", "delta", -5, 11))
	if s.stock != 28 {
		t.Fatalf("stock %d, attendu 28", s.stock)
	}
}

func TestSetPlusAncienSuperseded(t *testing.T) {
	s := &sim{stock: 10}
	s.apply(op("a", "set", 50, 12))
	if d := s.apply(op("b", "set", 20, 11)); d.Status != "superseded" {
		t.Fatalf("statut %s", d.Status)
	}
	if s.stock != 50 {
		t.Fatalf("stock %d", s.stock)
	}
}

// Même jeu que tools/mock-server/test/store.test.js : toutes les permutations donnent 39.
func TestToutesLesPermutations(t *testing.T) {
	ops := []Entry{
		op("1", "delta", -1, 9),
		op("2", "set", 40, 10),
		op("3", "delta", 3, 11),
		op("4", "set", 15, 8),
		op("5", "delta", -4, 12),
	}
	n := 0
	permute(ops, 0, func(order []Entry) {
		n++
		s := &sim{stock: 10}
		for _, o := range order {
			s.apply(o)
		}
		if s.stock != 39 {
			t.Fatalf("ordre %v : stock %d, attendu 39", ids(order), s.stock)
		}
	})
	if n != 120 {
		t.Fatalf("%d permutations", n)
	}
}

func TestEgaliteDeuxSet(t *testing.T) {
	a := op("00000000-0000-4000-8000-000000000001", "set", 1, 10)
	b := op("00000000-0000-4000-8000-000000000002", "set", 2, 10)
	for _, order := range [][]Entry{{a, b}, {b, a}} {
		s := &sim{}
		for _, o := range order {
			s.apply(o)
		}
		if s.stock != 2 {
			t.Fatalf("stock %d", s.stock)
		}
	}
}

func permute(a []Entry, k int, f func([]Entry)) {
	if k == len(a) {
		f(append([]Entry(nil), a...))
		return
	}
	for i := k; i < len(a); i++ {
		a[k], a[i] = a[i], a[k]
		permute(a, k+1, f)
		a[k], a[i] = a[i], a[k]
	}
}

func ids(es []Entry) []string {
	var out []string
	for _, e := range es {
		out = append(out, e.OpID)
	}
	return out
}

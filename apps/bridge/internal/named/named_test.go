package named

import (
	"reflect"
	"testing"
)

func TestCompile(t *testing.T) {
	const q = "SELECT a::text, ':pas_un_param', \"col:x\" FROM t -- :commentaire\nWHERE ref = :ref AND (:q = '' OR nom LIKE :q) /* :non */"
	cases := []struct {
		style Style
		text  string
		names []string
	}{
		{Question, "SELECT a::text, ':pas_un_param', \"col:x\" FROM t -- :commentaire\nWHERE ref = ? AND (? = '' OR nom LIKE ?) /* :non */", []string{"ref", "q", "q"}},
		{Dollar, "SELECT a::text, ':pas_un_param', \"col:x\" FROM t -- :commentaire\nWHERE ref = $1 AND ($2 = '' OR nom LIKE $2) /* :non */", []string{"ref", "q"}},
		{AtP, "SELECT a::text, ':pas_un_param', \"col:x\" FROM t -- :commentaire\nWHERE ref = @p1 AND (@p2 = '' OR nom LIKE @p2) /* :non */", []string{"ref", "q"}},
	}
	for _, c := range cases {
		got, err := Compile(q, c.style)
		if err != nil {
			t.Fatal(err)
		}
		if got.Text != c.text || !reflect.DeepEqual(got.Names, c.names) {
			t.Errorf("style %d :\n%q %v\nattendu\n%q %v", c.style, got.Text, got.Names, c.text, c.names)
		}
	}
}

func TestQuotesAndErrors(t *testing.T) {
	got, err := Compile("UPDATE t SET note = 'l''article :x' WHERE ref = :ref", Question)
	if err != nil || got.Text != "UPDATE t SET note = 'l''article :x' WHERE ref = ?" {
		t.Fatalf("%q %v", got.Text, err)
	}
	if _, err := Compile("SELECT 'non fermé", Question); err == nil {
		t.Fatal("guillemet non fermé accepté")
	}
}

func TestArgsAndCheck(t *testing.T) {
	q, _ := Compile("UPDATE t SET s = s + :value WHERE ref = :ref", Dollar)
	args, err := q.Args(map[string]any{"ref": "A", "value": int64(2)})
	if err != nil || !reflect.DeepEqual(args, []any{int64(2), "A"}) {
		t.Fatalf("%v %v", args, err)
	}
	if err := q.Check([]string{"ref"}); err == nil {
		t.Fatal(":value aurait dû être refusé")
	}
}

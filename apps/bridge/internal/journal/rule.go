// Package journal tient le journal des opérations (table kura_operations) et applique la règle de stock
// du contrat Kura (ADR 0005) sur une base qui ne sait faire que « stock = stock + x » et « stock = x ».
package journal

// Entry : une opération enregistrée.
type Entry struct {
	OpID      string
	ItemID    string
	Type      string // delta ou set
	Value     int64  // valeur envoyée par l'appareil
	TsMs      int64  // client_ts en millisecondes
	ClientTs  string
	DeviceID  string
	User      string
	SessionID string
	Status    string // applied ou superseded
	Received  string
}

// Effect : ce qu'il faut écrire dans la base de l'entreprise pour une opération.
type Effect int

const (
	// Rien à écrire : set dépassé, ou delta déjà « vu » par un inventaire plus récent.
	None Effect = iota
	// stock = stock + Value
	Add
	// stock = Value
	Write
)

// Decision : statut à renvoyer et écriture à faire.
type Decision struct {
	Status string // applied ou superseded
	Effect Effect
	Value  int64
}

// newer : a est-il un set plus récent que b ? Départage à horodatage égal : op_id le plus grand.
func newer(a, b Entry) bool {
	if a.TsMs != b.TsMs {
		return a.TsMs > b.TsMs
	}
	return a.OpID > b.OpID
}

// Decide applique la règle « quantité = set le plus récent + delta postérieurs » de façon incrémentale.
// past contient les opérations déjà enregistrées pour l'article (toutes passées par le Bridge).
//
//   - delta : additionné, sauf s'il est antérieur (ou simultané) au dernier inventaire connu,
//     qui l'a déjà compté ; il est alors enregistré sans effet.
//   - set : ignoré (superseded) s'il existe un set plus récent ; sinon le stock devient
//     sa valeur + les delta postérieurs déjà reçus (arrivés avant lui mais faits après).
//
// Le résultat ne dépend donc pas de l'ordre d'arrivée des opérations.
func Decide(past []Entry, op Entry) Decision {
	var last *Entry
	for i := range past {
		if past[i].Type == "set" && (last == nil || newer(past[i], *last)) {
			last = &past[i]
		}
	}
	if op.Type == "delta" {
		if last != nil && op.TsMs <= last.TsMs {
			return Decision{Status: "applied", Effect: None}
		}
		return Decision{Status: "applied", Effect: Add, Value: op.Value}
	}
	if last != nil && newer(*last, op) {
		return Decision{Status: "superseded", Effect: None}
	}
	target := op.Value
	for _, p := range past {
		if p.Type == "delta" && p.TsMs > op.TsMs {
			target += p.Value
		}
	}
	return Decision{Status: "applied", Effect: Write, Value: target}
}

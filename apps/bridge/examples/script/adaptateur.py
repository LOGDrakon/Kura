#!/usr/bin/env python3
"""Adaptateur Kura Bridge d'exemple : le stock est dans un fichier CSV (export d'un tableur, d'un vieux logiciel…).

Le Bridge lance ce script à chaque demande : une requête JSON arrive sur l'entrée standard,
le script répond en JSON sur la sortie standard. Remplacez les fonctions lire()/ecrire()
par des appels à votre système (API REST, base, fichier…). Protocole : docs/guides/bridge.md#script.

Le Bridge tient lui-même le journal (op_id déjà reçus, règle des inventaires) : ce script n'a qu'à
lire un article et à ajouter ou écrire une quantité.
"""
import csv
import json
import os
import sys
import tempfile

FICHIER = os.environ.get("KURA_STOCK_CSV", "stock.csv")
COLONNES = ["ref", "name", "location", "service", "quantity"]


def lire():
    with open(FICHIER, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter=";"))


def ecrire(lignes):
    # Écriture atomique : fichier temporaire puis remplacement.
    dossier = os.path.dirname(os.path.abspath(FICHIER))
    fd, tmp = tempfile.mkstemp(dir=dossier, suffix=".tmp")
    with os.fdopen(fd, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=COLONNES, delimiter=";")
        w.writeheader()
        w.writerows(lignes)
    os.replace(tmp, FICHIER)


def article(ligne):
    item = {k: v for k, v in ligne.items() if v not in (None, "")}
    item["quantity"] = int(ligne["quantity"])
    return item


def get_item(req):
    for ligne in lire():
        if ligne["ref"] == req["ref"]:
            return {"item": article(ligne)}
    return {"item": None}


def list_items(req):
    q = (req.get("q") or "").lower()
    lignes = [l for l in lire() if not q or q in l["ref"].lower() or q in l["name"].lower()]
    debut = int(req.get("cursor") or 0)
    fin = debut + int(req["limit"])
    page = {"items": [article(l) for l in lignes[debut:fin]]}
    if fin < len(lignes):
        page["next_cursor"] = str(fin)
    return page


def appliquer(req):
    lignes = lire()
    for ligne in lignes:
        if ligne["ref"] == req["ref"]:
            stock = int(ligne["quantity"])
            stock = stock + req["value"] if req["action"] == "apply_delta" else req["value"]
            if stock < 0:
                # Refus métier : l'opération reste en erreur dans l'appli, l'utilisateur décide.
                return {"ok": False, "reason": "forbidden", "message": f"stock négatif interdit ({stock})"}
            ligne["quantity"] = str(stock)
            ecrire(lignes)
            return {"ok": True, "quantity": stock}
    return {"ok": False, "reason": "unknown_item"}


ACTIONS = {"get_item": get_item, "list_items": list_items, "apply_delta": appliquer, "apply_set": appliquer}

if __name__ == "__main__":
    req = json.load(sys.stdin)
    json.dump(ACTIONS[req["action"]](req), sys.stdout, ensure_ascii=False)

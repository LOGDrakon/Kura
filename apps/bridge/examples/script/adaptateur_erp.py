#!/usr/bin/env python3
"""Adaptateur Kura Bridge pour l'API REST d'un ERP ou d'une GMAO (bibliothèque standard uniquement).

API supposée (à adapter) :
  GET  /articles/{code}              → {"code", "libelle", "emplacement", "stock", …}   (404 si inconnu)
  GET  /articles?recherche=&page=&taille=  → {"articles": [ … ], "total": 1234}
  POST /articles/{code}/mouvements   {"quantite": -2, "motif": "…", "reference": "…"} → {"stock": 10}
  PUT  /articles/{code}/stock        {"quantite": 20, "motif": "…", "reference": "…"} → {"stock": 20}
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = os.environ["ERP_URL"].rstrip("/")
JETON = os.environ.get("ERP_TOKEN", "")


def appel(methode, chemin, corps=None):
    """Appelle l'API. Renvoie (code HTTP, JSON). Une panne réseau ou un 5xx fait échouer le script :
    le Bridge répond alors 503 et le téléphone réessaiera plus tard."""
    req = urllib.request.Request(
        API + chemin,
        method=methode,
        data=None if corps is None else json.dumps(corps).encode(),
        headers={"Authorization": f"Bearer {JETON}", "Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        if e.code >= 500:
            raise
        try:
            return e.code, json.load(e)
        except ValueError:
            return e.code, {}


def article(a):
    """Format de l'ERP → format Kura (les autres champs sont transmis tels quels)."""
    item = {k: v for k, v in a.items() if v is not None}
    item["ref"] = a["code"]
    item["name"] = a.get("libelle")
    item["location"] = a.get("emplacement")
    item["quantity"] = a["stock"]
    return {k: v for k, v in item.items() if v is not None}


def chemin_article(ref):
    return "/articles/" + urllib.parse.quote(ref, safe="")


def get_item(req):
    code, data = appel("GET", chemin_article(req["ref"]))
    return {"item": article(data) if code == 200 else None}


def list_items(req):
    taille = int(req["limit"])
    page = int(req.get("cursor") or 1)
    query = urllib.parse.urlencode({"recherche": req.get("q", ""), "page": page, "taille": taille})
    _, data = appel("GET", "/articles?" + query)
    reponse = {"items": [article(a) for a in data["articles"]]}
    if page * taille < data["total"]:
        reponse["next_cursor"] = str(page + 1)
    return reponse


def appliquer(req):
    corps = {
        "quantite": req["value"],
        "motif": f"Kura Scan ({req.get('user') or req.get('device_id') or 'appli'})",
        "reference": req["op_id"],
    }
    if req["action"] == "apply_delta":
        code, data = appel("POST", chemin_article(req["ref"]) + "/mouvements", corps)
    else:
        code, data = appel("PUT", chemin_article(req["ref"]) + "/stock", corps)
    if code == 404:
        return {"ok": False, "reason": "unknown_item"}
    if code in (401, 403, 409, 422):
        # Refus métier de l'ERP : l'opération reste en erreur sur le téléphone, avec ce message.
        return {"ok": False, "reason": "forbidden", "message": data.get("message") or f"refusé par l'ERP ({code})"}
    if code >= 400:
        return {"ok": False, "reason": "invalid", "message": data.get("message") or f"erreur {code}"}
    return {"ok": True, "quantity": data.get("stock")}


ACTIONS = {"get_item": get_item, "list_items": list_items, "apply_delta": appliquer, "apply_set": appliquer}

if __name__ == "__main__":
    requete = json.load(sys.stdin)
    json.dump(ACTIONS[requete["action"]](requete), sys.stdout, ensure_ascii=False)

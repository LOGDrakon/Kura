"""Vérifie que le JSON Schema est valide, que les exemples le respectent et que le contrat se lit."""

import json
import pathlib
import sys

import jsonschema
import yaml

SPEC = pathlib.Path(__file__).parent

schema = json.loads((SPEC / "config.schema.json").read_text(encoding="utf-8"))
jsonschema.Draft202012Validator.check_schema(schema)
validator = jsonschema.Draft202012Validator(schema)

failed = False
for example in sorted((SPEC / "examples").glob("*.json")):
    errors = list(validator.iter_errors(json.loads(example.read_text(encoding="utf-8"))))
    for error in errors:
        print(f"{example.name}: {'/'.join(map(str, error.path))}: {error.message}")
    failed |= bool(errors)
    print(f"{example.name}: {'ERREUR' if errors else 'OK'}")

contract = yaml.safe_load((SPEC / "api-contract.yaml").read_text(encoding="utf-8"))
assert contract["openapi"].startswith("3.1"), "le contrat doit être en OpenAPI 3.1"
print(f"api-contract.yaml: OK ({len(contract['paths'])} routes)")

sys.exit(1 if failed else 0)

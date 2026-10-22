#!/bin/sh
# Construit Kura Studio puis les fichiers de l'essai grandeur nature (voir demo/demo.gen.ts).
set -e
cd "$(dirname "$0")/.."
npm run build
npx vitest run --config demo/vitest.demo.config.ts

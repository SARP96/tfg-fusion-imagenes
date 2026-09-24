#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="${1:-$ROOT/experiments/manifests/image-pairs-example.csv}"
EXPECTED_HEADER="pair_id,ct_path,mri_path,dataset,split"

if [[ ! -f "$MANIFEST" ]]; then
  echo "No existe el manifiesto: $MANIFEST" >&2
  exit 1
fi

header="$(sed -n '1p' "$MANIFEST" | tr -d '\r')"
if [[ "$header" != "$EXPECTED_HEADER" ]]; then
  echo "Encabezado inválido." >&2
  echo "Esperado: $EXPECTED_HEADER" >&2
  echo "Obtenido: $header" >&2
  exit 1
fi

missing=0
while IFS=, read -r pair_id ct_path mri_path dataset split; do
  [[ "$pair_id" == "pair_id" || -z "$pair_id" ]] && continue
  for image_path in "$ct_path" "$mri_path"; do
    if [[ ! -f "$ROOT/$image_path" ]]; then
      echo "Falta $pair_id: $image_path" >&2
      missing=$((missing + 1))
    fi
  done
done < "$MANIFEST"

if (( missing > 0 )); then
  echo "Manifiesto válido, pero faltan $missing archivos locales." >&2
  exit 2
fi

echo "Manifiesto y archivos validados: $MANIFEST"

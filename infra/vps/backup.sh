#!/usr/bin/env bash
# Respaldo diario de Paktay en el VPS (cron, ver docs/vps-deployment.md).
#
# Vuelca la base de negocio y la de Keycloak con pg_dump -Fc, guarda 14 días en
# BACKUP_DIR y, si existe STORAGE_BOX (usuario@host de un Storage Box de Hetzner con
# llave SSH), copia la carpeta del día allí. Nunca imprime secretos.
#
# Variables (en /opt/paktay/backup.env, chmod 600):
#   BUSINESS_DB_CONTAINER  contenedor de la base de negocio (p. ej. paktay-prod-business-db-1)
#   KEYCLOAK_DB_CONTAINER  contenedor de la base de Keycloak
#   KEYCLOAK_DB_USER       usuario de esa base (por defecto keycloak)
#   KEYCLOAK_DB_NAME       nombre de esa base (por defecto keycloak)
#   BACKUP_DIR             por defecto /opt/paktay/backups
#   KEEP_DAYS              por defecto 14
#   STORAGE_BOX            opcional; vacío = sin copia fuera del VPS
set -euo pipefail

ENV_FILE="${ENV_FILE:-/opt/paktay/backup.env}"
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

: "${BUSINESS_DB_CONTAINER:?Define BUSINESS_DB_CONTAINER}"
: "${KEYCLOAK_DB_CONTAINER:?Define KEYCLOAK_DB_CONTAINER}"
KEYCLOAK_DB_USER="${KEYCLOAK_DB_USER:-keycloak}"
KEYCLOAK_DB_NAME="${KEYCLOAK_DB_NAME:-keycloak}"
BACKUP_DIR="${BACKUP_DIR:-/opt/paktay/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"

day="$(date -u +%Y-%m-%d_%H%M)"
target="$BACKUP_DIR/$day"
mkdir -p "$target"
chmod 700 "$BACKUP_DIR" "$target"

docker exec "$BUSINESS_DB_CONTAINER" pg_dump -U paktay -d paktay -Fc > "$target/paktay.dump"
docker exec "$KEYCLOAK_DB_CONTAINER" pg_dump -U "$KEYCLOAK_DB_USER" -d "$KEYCLOAK_DB_NAME" -Fc > "$target/keycloak.dump"

# Un volcado vacío o truncado no cuenta como respaldo.
for f in "$target"/*.dump; do
  if [ ! -s "$f" ] || ! docker exec -i "$BUSINESS_DB_CONTAINER" pg_restore --list > /dev/null < "$f"; then
    echo "backup_failed file=$(basename "$f")" >&2
    exit 1
  fi
done
chmod 600 "$target"/*.dump

if [ -n "${STORAGE_BOX:-}" ]; then
  rsync -a -e "ssh -p 23 -o BatchMode=yes" "$target" "$STORAGE_BOX:paktay-backups/"
fi

find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -mtime +"$KEEP_DAYS" -exec rm -rf {} +

echo "backup_ok dir=$target size=$(du -sh "$target" | cut -f1)"

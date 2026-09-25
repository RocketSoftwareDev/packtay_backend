# Día 2: foto del esquema real (para Codex)

Objetivo: sacar el esquema de la base local y comprobar si los SQL actuales levantan una
base desde cero. **No cambies código ni SQL.** Sólo mira y registra.

## Reglas

- Trabaja en `develop` del backend. **Nunca** toques `main` ni el proyecto `paktay-prod`.
- Sólo proyectos Docker `paktay-local` (ya existe) y `paktay-fresh` (nuevo, desechable).
- Nada de datos: sólo esquema. Ningún `pg_dump` sin `--schema-only`.
- No escribas secretos en los logs.

## Preparación

```bash
cd packtay_backend
git fetch origin && git checkout develop && git pull --ff-only
export RUN=$(date +%Y-%m-%d_%H%M)-dia2
export LOGS=$PWD/IALogs/logs/$RUN; mkdir -p "$LOGS"
C="docker compose -p paktay-local --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.local-keycloak.yml"
```

## Pasos

```bash
# 1. Esquema de la base local actual (la que usan las pruebas)
$C up -d business-db
sleep 10
$C exec -T business-db pg_dump -U paktay -d paktay --schema-only --no-owner --no-privileges \
  > "$LOGS/01-schema-local.sql" 2> "$LOGS/01-schema-local.err"
$C exec -T business-db psql -U paktay -d paktay -At -c \
  "select table_name from information_schema.tables where table_schema='public' order by 1" \
  > "$LOGS/02-tablas-local.txt" 2>&1
$C exec -T business-db psql -U paktay -d paktay -At -c \
  "select relname, n_live_tup from pg_stat_user_tables order by 1" > "$LOGS/03-filas-por-tabla.txt" 2>&1
$C exec -T business-db psql -U paktay -d paktay -At -c \
  "select * from flyway_schema_history order by installed_rank" > "$LOGS/04-flyway-history.txt" 2>&1
$C stop business-db

# 2. Base desde cero con los SQL de database/ tal como los monta el compose
F="docker compose -p paktay-fresh --env-file .env --env-file .env.local.example -f docker-compose.yml -f docker-compose.local.yml"
$F up -d business-db > "$LOGS/05-fresh-up.log" 2>&1
sleep 30
$F logs --no-color business-db > "$LOGS/06-fresh-init.log" 2>&1
grep -nE "ERROR|FATAL|aborting|init process complete" "$LOGS/06-fresh-init.log" > "$LOGS/06-fresh-init-errores.txt"
$F exec -T business-db psql -U paktay -d paktay -At -c \
  "select table_name from information_schema.tables where table_schema='public' order by 1" \
  > "$LOGS/07-tablas-fresh.txt" 2>&1
$F exec -T business-db pg_dump -U paktay -d paktay --schema-only --no-owner --no-privileges \
  > "$LOGS/08-schema-fresh.sql" 2>&1

# 3. Borrar la base desechable (sólo paktay-fresh)
$F down -v > "$LOGS/09-fresh-down.log" 2>&1
```

Si `business-db` de `paktay-fresh` choca por el puerto 25433 con `paktay-local`, asegúrate de
que `paktay-local` esté detenido (`$C stop`) y repite el paso 2.

## Resumen y subida

Escribe `$LOGS/RESUMEN.md`: número de tablas en local y en fresh, las diferencias de nombres
entre `02` y `07`, si la inicialización fresh terminó con error (primeras 20 líneas de
`06-fresh-init-errores.txt`), y el conteo de filas por tabla sin valores.

```bash
git add IALogs/logs/$RUN
git commit -m "chore(ialogs): esquema real para el día 2 ($RUN)"
git push origin develop
```

# Cambios 2026-09-14 · Ciclo de vida de tarjetas (business-svc)

Rama: `feature/card-lifecycle-deactivate-delete`
Servicio afectado: **business-svc** únicamente. `auth-svc` no se toca.

Este documento existe para que quien continúe el trabajo (Codex u otra sesión)
sepa qué cambió, por qué, y **qué hay que probar exactamente** con el entorno
Docker levantado, que es lo que exige `AGENTS.md` para dar por terminada una ruta.

---

## 1. Qué problema se resuelve

El usuario pidió tres cosas sobre tarjetas:

1. **Desactivar** una tarjeta que ya no usa. Condición: que no tenga ningún
   movimiento en los últimos 3 meses. Una tarjeta desactivada no debe aparecer
   en ningún selector de la app; sólo en el apartado de tarjetas, con la opción
   de volver a activarla.
2. **Eliminar** una tarjeta, y sólo si no tiene **ningún** movimiento en ningún
   mes ni año.
3. **No repetir el nombre de la tarjeta dentro del mismo banco.** El mismo
   nombre en dos bancos distintos sí es válido.

El punto 3 estaba mal implementado, no sólo sin validar: el índice
`cards_active_identity_uq` era `(user_id, lower(btrim(name)))`, es decir **único
por usuario**. Con él, un usuario con «Visa Mastercard» en Banco 1 no podía
registrar «Visa Mastercard» en Banco 2, que es justo el caso que el usuario
describió como válido. La regla nueva es por banco.

---

## 2. Cambios en base de datos

Archivo nuevo: `database/paktay_mvp_v0_19_card_lifecycle.sql`

```sql
drop index if exists cards_active_identity_uq;
create unique index cards_active_identity_uq
    on cards (user_id, bank_id, lower(btrim(name))) where status = 'ACTIVE';
```

Notas:

- Sigue siendo **parcial sobre `status = 'ACTIVE'`**. Eso significa que una
  tarjeta desactivada libera su nombre dentro de su banco, y por eso reactivar
  puede fallar: el índice vuelve a exigirlo.
- No se crean columnas. `cards.status` (`card_status`: `ACTIVE` / `INACTIVE`) y
  `cards.deactivated_at` existen desde `paktay_mvp_v0_1_postgres.sql`. Lo que
  cambia es que a partir de ahora una tarjeta `INACTIVE` **sí** puede volver a
  `ACTIVE` desde la app; antes el comentario del esquema decía lo contrario.
- El `check` del esquema exige `deactivated_at is null` cuando `status =
  'ACTIVE'`, así que al activar hay que ponerlo a `null`. El servicio lo hace.
- `business-svc` usa Flyway con `classpath:db/migration`, y esta carpeta
  `database/` es el guion manual de MVP. **Si el entorno destino corre Flyway,
  hay que portar este índice a una migración `V6__...sql`** antes de desplegar.
  Se deja aquí para no inventar un número de versión de Flyway que choque con
  otra rama en curso.

---

## 3. Cambios en el servicio

### `CardService.java`

| Método | Qué hace | Regla que aplica |
|---|---|---|
| `register` (modificado) | Comprueba el nombre libre en el banco **antes** del insert | Nombre único por `(usuario, banco)` entre activas |
| `list` (modificado) | Ordena `ACTIVE` primero y luego por fecha | Devuelve también las inactivas: el móvil necesita listarlas |
| `deactivate` (nuevo) | `status = 'INACTIVE'`, `deactivated_at = now()` | Cero gastos en los últimos `QUIET_MONTHS_BEFORE_DEACTIVATION` (3) meses |
| `activate` (nuevo) | `status = 'ACTIVE'`, `deactivated_at = null` | Falla si el nombre ya lo tiene otra activa del banco |
| `delete` (nuevo) | Borra la tarjeta | Cero gastos en **todo** el historial |

Detalles que no se ven en la tabla:

- `deactivate` cuenta con `occurred_at >= now() - make_interval(months => 3)`,
  es decir por **fecha del consumo**, no por fecha de creación del registro.
- `delete` limpia antes lo que cuelga de la tarjeta y que sí se puede borrar:
  `budget_allocations` y `monthly_incomes` de esa tarjeta, y pone a `null` el
  `suggested_card_id` de los `pending_movements` que la apuntaban. **No toca
  `expenses`**: esa tabla tiene triggers `protect_expense_update` y
  `expenses_no_delete` que prohíben borrar gastos, y por eso la condición de
  borrado es «cero gastos» y no «borro los gastos también».
- El mensaje de nombre duplicado es uno solo (`DUPLICATE_NAME_MESSAGE`) y se usa
  tanto en la comprobación previa como en el `catch` de
  `DataIntegrityViolationException`, para que el usuario lea lo mismo gane quien
  gane la carrera.
- `ensureActiveUser` se llama en todas, igual que en el resto del servicio.

### `CardController.java`

Rutas nuevas, todas bajo `/api/v1/user/cards` y con `bearerAuth`:

| Método | Ruta | Respuesta | Error 400 cuando |
|---|---|---|---|
| `PATCH` | `/{cardId}/deactivate` | `200` + `CardResponse` | No existe, ya inactiva, o tiene consumos en 3 meses |
| `PATCH` | `/{cardId}/activate` | `200` + `CardResponse` | No existe, ya activa, o el nombre está tomado en el banco |
| `DELETE` | `/{cardId}` | `204` sin cuerpo | No existe o tiene consumos |

Las tres llevan `@Operation` y `@ApiResponse` con la explicación de la regla,
como exige `AGENTS.md`. Las descripciones de `POST` y `GET` se actualizaron:
la primera dice la nueva regla de nombre, la segunda avisa de que la lista trae
también las inactivas y de que el móvil usa `status` para ocultarlas.

`CardResponse` ya devolvía `status`; no hubo que tocar el DTO.

---

## 4. Postman

`postman/Paktay-Business.postman_collection.json` gana tres peticiones dentro de
la carpeta **Tarjetas**: desactivar, reactivar y eliminar. Usan `{{cardId}}`,
que ya lo guarda el test de «Registrar tarjeta».

---

## 5. Qué hay que probar exactamente

Nada de esto se pudo ejecutar en la máquina donde se escribió el cambio: no hay
Docker ni Maven disponibles ahí. **El cambio está sin compilar y sin probar.**

### 5.1 Compilación y arranque

```bash
./mvnw -pl business-svc -am clean verify
docker compose up -d
```

Con el entorno arriba, lo que exige `AGENTS.md` para dar por cerrada una ruta:

- `http://localhost:8082/actuator/health` → 200
- `http://localhost:8082/v3/api-docs` → 200 y **contiene** las tres rutas nuevas
- `http://localhost:8082/swagger-ui/index.html` → 200 y carga sin autenticación

### 5.2 Migración

Aplicar `database/paktay_mvp_v0_19_card_lifecycle.sql` sobre una base que ya
tenga datos y comprobar que:

1. El índice viejo desapareció y el nuevo existe:
   ```sql
   select indexdef from pg_indexes where indexname = 'cards_active_identity_uq';
   ```
   Tiene que incluir `bank_id`.
2. Si la base ya tenía dos tarjetas activas del **mismo banco** con el mismo
   nombre (posible sólo si alguien las creó saltándose el índice anterior), la
   creación del índice falla. En ese caso hay que renombrar una a mano antes.

### 5.3 Reglas de negocio, una por una

| Caso | Pasos | Resultado esperado |
|---|---|---|
| Nombre repetido en el mismo banco | `POST /cards` con `name` «Visa Mastercard» y `bankId` B1; repetir igual | Segundo → `400` «Ya tienes una tarjeta activa con ese nombre en este banco» |
| Mismo nombre en otro banco | Igual pero con `bankId` B2 | `201` |
| Desactivar sin consumos | Tarjeta nueva → `PATCH /{id}/deactivate` | `200`, `status = "INACTIVE"` |
| Desactivar con consumo reciente | Crear gasto con `occurredAt` de hace 1 mes → `PATCH /deactivate` | `400` con el número de consumos |
| Desactivar con consumo viejo | Gasto con `occurredAt` de hace 5 meses → `PATCH /deactivate` | `200` |
| Gasto contra tarjeta inactiva | `POST /expenses` con esa tarjeta | `400` «La tarjeta no existe, no pertenece al usuario o está inactiva» (ya lo hacía `ensureCard`) |
| Nombre liberado | Con la tarjeta inactiva, `POST /cards` con su mismo nombre y banco | `201` |
| Reactivar con nombre tomado | Después del caso anterior, `PATCH /{id}/activate` sobre la inactiva | `400` explicando el choque |
| Reactivar limpio | Desactivar y activar sin crear nada en medio | `200`, `status = "ACTIVE"`, `deactivated_at` nulo |
| Eliminar sin consumos | Tarjeta nueva → `DELETE /{id}` | `204`, y `GET /cards` ya no la trae |
| Eliminar con un consumo | Crear gasto → `DELETE /{id}` | `400` «tiene 1 consumo … Desactívala en su lugar» |
| Eliminar con presupuesto | Tarjeta con `initialBudget` y sin gastos → `DELETE` | `204` y la fila de `budget_allocations` desaparece |
| Tarjeta de otro usuario | `DELETE` con el JWT equivocado | `400` «La tarjeta no existe» |

### 5.4 Lo que conviene mirar aunque no se pidió

- El atajo autenticado (`AuthenticatedShortcutPaymentService`) y
  `ShortcutTransactionService` buscan la tarjeta por
  `lower(btrim(name)) = ... and status = 'ACTIVE'` **sin filtrar por banco**. Con
  la regla nueva, dos tarjetas activas de bancos distintos pueden llamarse igual,
  y esa consulta devolvería dos filas. Hay que decidir el desempate antes de que
  ocurra en producción; no se cambió aquí porque el usuario no lo pidió y toca
  el camino del atajo, que es el que más cuesta volver a probar.

---

## 6. Qué NO cambió

- `auth-svc`, Keycloak y todo el flujo de contraseñas.
- El contrato de `CardResponse`, `CreateCardRequest` y `UpdateCardRequest`.
- Los gastos: ni se borran ni se reasignan nunca.

---

## 7. El nombre de Wallet se asocia · IMPLEMENTADO

Migración `database/paktay_mvp_v0_20_wallet_name_association.sql` y dos rutas
nuevas. Diseñado en `pencil-new.pen`, fila 11.

### 7.1 Por qué el modelo actual falla

`cards.name` es hoy dos cosas a la vez: el nombre que el usuario escribe al dar
de alta la tarjeta, y la clave con la que el atajo la busca
(`AuthenticatedShortcutPaymentService` y `ShortcutTransactionService` hacen
`lower(btrim(name)) = lower(btrim(:cardName))`).

El usuario escribe «Visa», Wallet manda «VISA MASTERCARD PLATINUM», y ese
consumo no se asocia nunca. No es un error del usuario: nadie sabe qué texto
manda Wallet hasta que llega el primer consumo.

### 7.2 El modelo propuesto

El nombre deja de escribirse y pasa a **asociarse**. El usuario sólo pone su
apodo, que es `alias` y ya existe. Cuando llega un consumo cuyo nombre no está
asociado a ninguna tarjeta, la app pregunta a cuál pertenece y lo fija.

### 7.3 Lo que se cambió

| # | Cambio | Dónde |
|---|---|---|
| 1 | `cards.name` pasa a `nullable`, con `check (name is null or btrim(name) <> '')` | v0.20 |
| 2 | `cards_active_identity_uq` se sustituye por `cards_active_wallet_name_uq`, que es `(user_id, lower(btrim(name)))` con `where status = 'ACTIVE' and name is not null` | v0.20 |
| 3 | `CreateCardRequest.name` deja de ser `@NotBlank` | `CreateCardRequest` |
| 4 | `PATCH /{cardId}/wallet-name` con `{walletName}` | `CardController`, `CardService.associateWalletName` |
| 5 | `DELETE /{cardId}/wallet-name` | `CardController`, `CardService.clearWalletName` |

Detalles que no se ven en la tabla:

- El `check` original del nombre se creó **sin nombre**, así que el suyo lo
  generó PostgreSQL y depende de la versión. La migración lo busca por su
  definición con un bloque `do $$`, en vez de adivinarlo: una migración que
  falla por el nombre de un constraint es una tarde perdida.
- `associateWalletName` **exige que la tarjeta esté activa**. Una desactivada no
  recibe consumos, así que asociarle un nombre no significa nada y además
  ocuparía ese nombre para las que sí pueden usarlo.
- **Reasignar no le quita el nombre a nadie automáticamente.** Si el nombre ya
  es de otra tarjeta activa, la ruta contesta 400 y el móvil tiene que llamar
  antes a `DELETE` sobre la que lo tenía. Es deliberado: cambiar dos tarjetas no
  puede ser el efecto secundario de un solo toque.
- `register` sigue aceptando `name` para quien ya lo sepa, como Postman. Si
  llega, se valida igual que al asociarlo.

### 7.4 La decisión que se tomó

El nombre de Wallet tiene que ser **único por usuario**, no por banco. La
consulta del atajo no filtra por banco, así que dos tarjetas de bancos distintos
con el mismo nombre asociado devolverían dos filas.

Esto **sustituye al índice que esta misma rama creó en v0.19**, que era
`(user_id, bank_id, lower(name))`. Aquel se hizo porque el usuario pidió poder
repetir el nombre entre bancos, y sigue cumpliéndose: lo que puede repetirse
entre bancos es el **apodo**, que es lo que el usuario lee y escribe. El nombre
de Wallet deja de ser cosa suya y pasa a ser único por usuario. El usuario
confirmó el cambio antes de tocarlo.

Esto también cierra el cabo suelto que quedó anotado en la sección 5.4 de este
mismo documento: el desempate del atajo cuando dos tarjetas comparten nombre. Ya
no puede ocurrir.

### 7.6 Qué hay que probar

| Caso | Pasos | Resultado esperado |
|---|---|---|
| Alta sin nombre | `POST /cards` sin `name` | `201` con `name: null` |
| Alta con nombre libre | `POST /cards` con `name` inédito | `201` |
| Alta con nombre tomado | `POST /cards` con un `name` de otra activa | `400` |
| Asociar | `PATCH /{id}/wallet-name` sobre una sin nombre | `200`, `name` fijado |
| Asociar lo ya tomado | El mismo nombre sobre otra tarjeta | `400` |
| Asociar en otro banco | El mismo nombre en una tarjeta de otro banco | `400`, porque es único por usuario |
| Reasignar sin soltar | `PATCH` sobre la que ya tiene otro nombre, con uno tomado | `400` |
| Reasignar bien | `DELETE` en la vieja y `PATCH` en la nueva | `200` las dos |
| Asociar a una inactiva | Desactivar y `PATCH` | `400` |
| Liberar y reusar | `DELETE /{id}/wallet-name` y asociarlo a otra | `200` |
| El atajo asocia | `POST /shortcut/payments` con ese nombre | El consumo cae en la tarjeta correcta |
| El atajo sin asociar | Mismo `POST` con un nombre inédito | El movimiento queda pendiente sin tarjeta |
| Índice | `select indexdef from pg_indexes where indexname = 'cards_active_wallet_name_uq'` | Sin `bank_id` |
| Migración con datos | Aplicar v0.20 sobre una base con dos tarjetas activas del mismo nombre en bancos distintos | Falla: hay que renombrar una antes |

La última fila es la única que puede doler en una base ya poblada. Conviene
mirarla antes de migrar:

```sql
select user_id, lower(btrim(name)), count(*)
  from cards where status = 'ACTIVE' and name is not null
 group by 1, 2 having count(*) > 1;
```

### 7.5 Wallet no manda los cuatro dígitos

Confirmado con el usuario. El atajo sólo entrega el nombre de la tarjeta. Los
cuatro dígitos siguen siendo un dato válido que el usuario escribe para
reconocer su tarjeta, pero **ya no sirven para asociar** un consumo entrante.
Cualquier lógica futura que los use como respaldo de asociación está muerta
mientras el atajo sea la única automatización.

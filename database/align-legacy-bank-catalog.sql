-- Adaptación de datos heredados ANTES de V3, sólo en una copia respaldada.
-- No cambia migraciones publicadas ni identificadores de usuarios/tarjetas/gastos.
-- Alinea bancos por normalized_name y todas sus FK con el catálogo de V3.
-- Ejecutar con psql -v ON_ERROR_STOP=1 como propietario/superusuario.
BEGIN;
CREATE TEMP TABLE canonical_banks (id uuid PRIMARY KEY, normalized_name text UNIQUE) ON COMMIT DROP;
INSERT INTO canonical_banks VALUES
('5f07266b-24b2-496b-8d39-07b676403f71'::uuid, 'BANCO AMAZONAS'),
('e32c266c-f831-4590-9708-b1d635272e9e'::uuid, 'PRODUBANCO'),
('d8c81baf-95db-4ba6-8f28-520daaa3361a'::uuid, 'BANCO DEL AUSTRO'),
('226d5b9e-5a40-494c-89ff-01caee6f080a'::uuid, 'BANCO SOLIDARIO'),
('90db8f49-e7d8-456f-9750-d2a71ab609f9'::uuid, 'BANCO GUAYAQUIL'),
('cc7686d3-504d-4714-a729-8cc5c8883d05'::uuid, 'BANCO SUDAMERICANO'),
('b9239c91-86ea-40b2-b076-f4efea2fa560'::uuid, 'BANCO BOLIVARIANO'),
('2a403e53-dca2-4d77-9e6e-6c0b0591f3c7'::uuid, 'BANCO COOPNACIONAL'),
('3122bb06-0135-4c43-8322-abe04af02b07'::uuid, 'BANCO COMERCIAL MANABI'),
('0ad9b965-7cfd-452b-ad0a-3c02e9553137'::uuid, 'BANCO PROCREDIT'),
('750e0a42-9574-44c8-a1d5-e7c68179c472'::uuid, 'BANCO DEL LITORAL'),
('91e8aaa5-f9ab-4bdb-86c4-6b44b83709cc'::uuid, 'BANCO CAPITAL'),
('a2b726b4-f0f1-4420-a622-b1ca4aad429f'::uuid, 'BANCO GENERAL RUMINAHUI'),
('4bf5d030-ab0d-46f0-b214-fb54c777efcf'::uuid, 'BANCO DELBANK'),
('eff4dc54-91ec-46c0-9f11-4e34b02690f7'::uuid, 'BANCO INTERNACIONAL'),
('bf758e6b-ae96-4b62-8ce7-8a8502347872'::uuid, 'BANCO ATLANTIDA'),
('9f75c190-36c4-4ec8-8339-c0f19d71cfa2'::uuid, 'BANCO DE LOJA'),
('4c2b10ba-480c-418e-bac7-3079ae55192b'::uuid, 'CODESARROLLO'),
('b2ff0c2e-e4b3-4813-a7e8-e5c1c97cbfcf'::uuid, 'BANCO DE MACHALA'),
('a54e6dec-8b04-44cd-97b8-ab74351f653f'::uuid, 'BANCO PICHINCHA'),
('240e3d6d-302b-4cf9-9427-329a1d2aee17'::uuid, 'BANCO VISIONFUND ECUADOR'),
('61574d31-df44-491e-8b57-48835773781c'::uuid, 'BANCO DEL PACIFICO'),
('816d9306-b914-49af-b8b6-13004d0407c7'::uuid, 'BANCO DINERS CLUB'),
('2298f540-2cb7-4c98-bb42-b379cf79298e'::uuid, 'CITIBANK'),
('604cdb1c-4342-4a21-91d4-6b5cc59ec649'::uuid, 'BANECUADOR'),
('05ba9966-74d4-4935-bbda-6f146d679fe5'::uuid, 'COOPERATIVA JEP'),
('a671663d-235f-42c3-81a1-57fc2b2027a2'::uuid, 'COOPERATIVA JARDIN AZUAYO'),
('b82bd7b3-6381-41c6-93da-c746dc7d8020'::uuid, 'COOPERATIVA 29 DE OCTUBRE'),
('718a0c04-b998-45dc-8c31-0cdb550dbd5d'::uuid, 'COOPERATIVA POLICIA NACIONAL');
LOCK TABLE public.banks IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.banks b JOIN canonical_banks c ON b.id=c.id
             WHERE b.normalized_name<>c.normalized_name) THEN
    RAISE EXCEPTION 'Un UUID canónico pertenece a otro banco; revisar manualmente';
  END IF;
END $$;
CREATE TEMP TABLE bank_id_mapping ON COMMIT DROP AS
SELECT b.id AS old_id, c.id AS new_id FROM public.banks b
JOIN canonical_banks c USING (normalized_name) WHERE b.id<>c.id;
-- Las FK originales no tienen ON UPDATE CASCADE. Se actualizan dentro de una
-- transacción y se comprueban todas las referencias antes de confirmar.
SET LOCAL session_replication_role = replica;
DO $$
DECLARE r record; missing bigint;
BEGIN
  FOR r IN SELECT con.conrelid::regclass AS child_table, a.attname AS column_name
    FROM pg_constraint con JOIN pg_attribute a
      ON a.attrelid=con.conrelid AND a.attnum=con.conkey[1]
    WHERE con.contype='f' AND con.confrelid='public.banks'::regclass
  LOOP
    EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', r.child_table);
    EXECUTE format('UPDATE %s t SET %I=m.new_id FROM bank_id_mapping m WHERE t.%I=m.old_id',
      r.child_table, r.column_name, r.column_name);
  END LOOP;
  UPDATE public.banks b SET id=m.new_id FROM bank_id_mapping m WHERE b.id=m.old_id;
  FOR r IN SELECT con.conrelid::regclass AS child_table, a.attname AS column_name
    FROM pg_constraint con JOIN pg_attribute a
      ON a.attrelid=con.conrelid AND a.attnum=con.conkey[1]
    WHERE con.contype='f' AND con.confrelid='public.banks'::regclass
  LOOP
    EXECUTE format('SELECT count(*) FROM %s t LEFT JOIN public.banks b ON b.id=t.%I WHERE t.%I IS NOT NULL AND b.id IS NULL',
      r.child_table,r.column_name,r.column_name) INTO missing;
    IF missing>0 THEN RAISE EXCEPTION 'Referencias huérfanas en %',r.child_table; END IF;
  END LOOP;
END $$;
SET LOCAL session_replication_role = origin;
SELECT count(*) AS bancos_homologados FROM bank_id_mapping;
COMMIT;

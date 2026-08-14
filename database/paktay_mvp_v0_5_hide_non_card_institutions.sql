begin;

-- No pertenecen al selector de emisores de tarjetas personales: BDE y CFN son
-- banca pública de desarrollo; BIESS es banca de inversión. Se desactivan en
-- instalaciones existentes para preservar cualquier referencia histórica.
update banks
   set active = false
 where normalized_name in (
    'BANCO DE DESARROLLO DEL ECUADOR',
    'CORPORACION FINANCIERA NACIONAL',
    'BIESS'
 );

commit;

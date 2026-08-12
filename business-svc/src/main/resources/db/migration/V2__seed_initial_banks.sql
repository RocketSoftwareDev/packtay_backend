insert into banks (slug, legal_name, short_name, monogram, brand_color, brand_ink_on_light) values
  ('pichincha', 'Banco Pichincha C.A.', 'Pichincha', 'BP', '#FFD100', true),
  ('guayaquil', 'Banco Guayaquil S.A.', 'Guayaquil', 'BG', '#0054A6', false),
  ('pacifico', 'Banco del Pacífico S.A.', 'Pacífico', 'BP', '#0078C8', false)
on conflict (slug) do nothing;

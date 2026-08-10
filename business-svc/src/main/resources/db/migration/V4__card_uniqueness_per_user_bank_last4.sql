-- Una cuenta bancaria ya es única por (user_id, bank_id).
-- Por tanto, los últimos cuatro dígitos no se pueden repetir dentro del mismo banco del mismo usuario,
-- incluso si se intenta registrar otro tipo de tarjeta.
alter table cards drop constraint cards_bank_account_id_kind_last4_key;
alter table cards add constraint cards_bank_account_id_last4_key unique (bank_account_id, last4);

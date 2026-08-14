begin;

drop index if exists cards_active_identity_uq;
create unique index cards_active_identity_uq
    on cards (user_id, bank_id, network, last4) where status = 'ACTIVE';

alter table cards drop constraint if exists cards_card_type_check;
alter table cards add constraint cards_card_type_check check (card_type in ('CREDIT', 'DEBIT'));

commit;

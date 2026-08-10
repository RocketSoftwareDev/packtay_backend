package ec.paktay.business.service;

import java.time.*;
import java.util.UUID;
import ec.paktay.business.dto.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortcutTransactionService {
  private final JdbcClient jdbc;
  public ShortcutTransactionService(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Transactional
  public ShortcutTransactionResponse ingest(UUID userId, String name, String email, ShortcutTransactionRequest r) {
    ensureProfile(userId, name, email);
    UUID prior = jdbc.sql("select id from captured_notifications where user_id=:u and bank_id=:b and parsed_amount_cents=:a and parsed_card_last4=:l and posted_at between :from and :to order by posted_at desc limit 1")
      .param("u",userId).param("b",r.bankId()).param("a",r.amountCents()).param("l",r.cardLast4()).param("from",r.postedAt().minusMinutes(2)).param("to",r.postedAt().plusMinutes(2)).query(UUID.class).optional().orElse(null);
    if (prior != null) { UUID tx = jdbc.sql("select id from transactions where captured_notification_id=:id").param("id",prior).query(UUID.class).optional().orElse(null); return new ShortcutTransactionResponse(prior,tx,null,false,true,true); }
    Integer bank = jdbc.sql("select count(*) from banks where id=:id and active").param("id",r.bankId()).query(Integer.class).single();
    if (bank == 0) throw new IllegalArgumentException("El banco seleccionado no existe o está inactivo");
    UUID account = jdbc.sql("select id from bank_accounts where user_id=:u and bank_id=:b").param("u",userId).param("b",r.bankId()).query(UUID.class).optional().orElseGet(() -> jdbc.sql("insert into bank_accounts(user_id,bank_id) values(:u,:b) returning id").param("u",userId).param("b",r.bankId()).query(UUID.class).single());
    UUID card = jdbc.sql("select id from cards where bank_account_id=:a and last4=:l and active").param("a",account).param("l",r.cardLast4()).query(UUID.class).optional().orElse(null);
    boolean created = card == null;
    if (created) card = jdbc.sql("insert into cards(bank_account_id,brand,kind,last4,auto_detected) values(:a,cast(:brand as card_brand),cast(:kind as card_kind),:l,true) returning id").param("a",account).param("brand",r.cardBrand()).param("kind",r.cardKind()).param("l",r.cardLast4()).query(UUID.class).single();
    UUID capture = jdbc.sql("insert into captured_notifications(user_id,bank_id,source_label,title,body,posted_at,parsed_amount_cents,parsed_currency,parsed_merchant_raw,parsed_card_last4,parsed_card_brand,parsed_card_kind) values(:u,:b,:s,:t,:body,:at,:amount,:currency,:merchant,:last4,cast(:brand as card_brand),cast(:kind as card_kind)) returning id")
      .param("u",userId).param("b",r.bankId()).param("s",r.sourceLabel()).param("t",r.title()).param("body",r.body()).param("at",r.postedAt()).param("amount",r.amountCents()).param("currency",r.currency()).param("merchant",r.merchantRaw()).param("last4",r.cardLast4()).param("brand",r.cardBrand()).param("kind",r.cardKind()).query(UUID.class).single();
    String month = YearMonth.from(r.postedAt().atZoneSameInstant(ZoneId.of("America/Guayaquil"))).toString();
    UUID tx = jdbc.sql("insert into transactions(user_id,amount_cents,currency,occurred_at,month_key,bank_account_id,card_id,merchant_raw,display_label,source,captured_notification_id) values(:u,:amount,:currency,:at,:month,:account,:card,:merchant,:merchant,'shortcut',:capture) returning id")
      .param("u",userId).param("amount",r.amountCents()).param("currency",r.currency()).param("at",r.postedAt()).param("month",month).param("account",account).param("card",card).param("merchant",r.merchantRaw()).param("capture",capture).query(UUID.class).single();
    return new ShortcutTransactionResponse(capture,tx,card,created,false,true);
  }
  private void ensureProfile(UUID id,String name,String email) { if(email==null||email.isBlank()) throw new IllegalArgumentException("El token no incluye el correo del usuario"); jdbc.sql("insert into profiles(id,display_name,email) values(:id,:name,:email) on conflict(id) do nothing").param("id",id).param("name",name==null||name.isBlank()?email:name).param("email",email).update(); jdbc.sql("insert into user_settings(user_id) values(:id) on conflict(user_id) do nothing").param("id",id).update(); }
}

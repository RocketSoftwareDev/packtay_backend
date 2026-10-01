package ec.paktay.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Guarda que la persona aceptó los Términos de uso y la Política de privacidad (día 8c):
 * fecha y versión de cada documento en app_users (V13).
 *
 * Las versiones salen del entorno (LEGAL_TERMS_VERSION, LEGAL_PRIVACY_VERSION). Solo se
 * reescribe la fecha cuando la versión aceptada cambia: volver a entrar con la misma
 * versión no mueve nada, y cuando se publique una versión nueva la fecha refleja cuándo
 * aceptó esa.
 */
@Service
public class LegalAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(LegalAcceptanceService.class);

    private final JdbcTemplate db;
    private final String termsVersion;
    private final String privacyVersion;

    public LegalAcceptanceService(JdbcTemplate db,
                                  @Value("${paktay.legal.terms-version:1}") String termsVersion,
                                  @Value("${paktay.legal.privacy-version:1}") String privacyVersion) {
        this.db = db;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    public void record(String userId) {
        try {
            db.update("""
                    update app_users set
                        terms_accepted_at = case when terms_version is distinct from ? then now() else terms_accepted_at end,
                        terms_version = ?,
                        privacy_accepted_at = case when privacy_version is distinct from ? then now() else privacy_accepted_at end,
                        privacy_version = ?
                     where id = cast(? as uuid)
                    """, termsVersion, termsVersion, privacyVersion, privacyVersion, userId);
        } catch (RuntimeException ex) {
            // No frena el registro ni la entrada; queda en el log para revisarlo.
            log.error("legal_acceptance_failed userId={} reason={}", userId, ex.getMessage());
        }
    }
}

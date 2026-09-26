package ec.paktay.business.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/** Correos de soporte: enlace de confirmación y respuestas del equipo. */
@Service
public class SupportMailService {
    private final JavaMailSender mail;
    private final String from;
    private final String verifyLink;

    /** Remitente: SMTP_FROM si está definido; si no, el usuario SMTP (igual que auth-svc). */
    public SupportMailService(JavaMailSender mail, @Value("${spring.mail.username:}") String username,
                              @Value("${paktay.mail.from:}") String configuredFrom,
                              @Value("${paktay.support.verify-link}") String verifyLink) {
        this.mail = mail;
        this.from = configuredFrom == null || configuredFrom.isBlank() ? username : configuredFrom.trim();
        this.verifyLink = verifyLink;
    }

    public void sendVerification(String email, String code, String token) {
        String link = verifyLink.replace("{token}", token);
        send(email, "Confirma tu solicitud " + code + " — PAKTAY",
                "Recibimos tu solicitud de soporte " + code + ".\n\nConfírmala abriendo este enlace (vence en 48 horas):\n"
                        + link + "\n\nSi no la enviaste tú, ignora este correo.",
                "<p>Recibimos tu solicitud de soporte <b>" + code + "</b>.</p>"
                        + "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">Confirmar mi solicitud</a> (vence en 48 horas).</p>"
                        + "<p style=\"color:#8c837b\">Si no la enviaste tú, ignora este correo.</p>");
    }

    public void sendReply(String email, String code, String body) {
        send(email, "Respuesta a tu solicitud " + code + " — PAKTAY",
                body + "\n\n— Equipo de PAKTAY (" + code + ")",
                "<p style=\"white-space:pre-wrap\">" + HtmlUtils.htmlEscape(body) + "</p>"
                        + "<p style=\"color:#8c837b\">— Equipo de PAKTAY (" + code + ")</p>");
    }

    private void send(String to, String subject, String text, String html) {
        var message = mail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "PAKTAY");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException("No fue posible preparar el correo");
        }
        mail.send(message);
    }
}

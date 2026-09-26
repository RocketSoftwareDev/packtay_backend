package ec.paktay.auth.service;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class PasswordMailService {
    private final JavaMailSender mail;
    private final String from;
    private final String template;
    private final String temporaryTemplate;
    /**
     * Remitente: SMTP_FROM si está definido; si no, el usuario SMTP. Hace falta
     * separarlos porque el usuario de algunos servidores no es una dirección de
     * correo (Mailpit rechaza el remitente "codex" con 553 5.1.3).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PasswordMailService(JavaMailSender mail, @Value("${spring.mail.username}") String username,
                               @Value("${paktay.mail.from:}") String configuredFrom) {
        this(mail, configuredFrom == null || configuredFrom.isBlank() ? username : configuredFrom.trim());
    }

    PasswordMailService(JavaMailSender mail, String from) {
        this.mail = mail; this.from = from;
        this.template = load("templates/password-pin.html");
        this.temporaryTemplate = load("templates/password-temporary.html");
    }

    private static String load(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Plantilla de correo no disponible"); }
    }

    /** Contraseña temporal del admin. Solo letras, números y símbolos sin significado en HTML. */
    public void sendTemporaryPassword(String email, String password, int hours) {
        if (!password.matches("[A-Za-z0-9!#%*+=?@_-]{12,64}")) throw new IllegalArgumentException("Formato de contraseña inválido");
        var message = mail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "PAKTAY");
            helper.setTo(email);
            helper.setSubject("Tu contraseña temporal — PAKTAY");
            helper.setText("Tu contraseña temporal de PAKTAY es: " + password + "\n\nVence en " + hours + " horas. "
                    + "Entra a la app con tu correo y esta contraseña; al entrar te pediremos que elijas una nueva. "
                    + "No la compartas.",
                    temporaryTemplate.replace("{{password}}", password).replace("{{hours}}", String.valueOf(hours)));
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException("No fue posible preparar el correo");
        }
        mail.send(message);
    }
    public void sendPin(String email, String pin, String purpose) {
        if (!pin.matches("[0-9]{6}")) throw new IllegalArgumentException("Formato de PIN inválido");
        String action = purpose.equals("CHANGE") ? "cambiar" : "recuperar";
        String title = purpose.equals("CHANGE") ? "Confirma tu cambio de contraseña" : "Recupera tu contraseña";
        var message = mail.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, "PAKTAY");
            helper.setTo(email);
            helper.setSubject(title + " — PAKTAY");
            helper.setText("Tu PIN de PAKTAY es: " + pin + "\n\nVence en 15 minutos y solo puede usarse una vez. "
                    + "Valídalo en la app y después elige tu nueva contraseña. No lo compartas. "
                    + "Si no solicitaste este cambio, ignora este correo.",
                    template.replace("{{title}}", title).replace("{{action}}", action).replace("{{pin}}", pin));
        } catch (jakarta.mail.MessagingException | java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException("No fue posible preparar el correo");
        }
        mail.send(message);
    }
}

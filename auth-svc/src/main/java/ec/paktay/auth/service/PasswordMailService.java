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
    public PasswordMailService(JavaMailSender mail, @Value("${spring.mail.username}") String from) {
        this.mail = mail; this.from = from;
        try (var input = new ClassPathResource("templates/password-pin.html").getInputStream()) {
            this.template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Plantilla de correo no disponible"); }
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

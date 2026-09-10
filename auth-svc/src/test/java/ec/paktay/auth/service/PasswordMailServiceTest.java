package ec.paktay.auth.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;

class PasswordMailServiceTest {
    @Test void sendsBrandedHtmlAndPlainTextWithoutRealDelivery() throws Exception {
        var mail = mock(JavaMailSenderImpl.class);
        MimeMessage message = new JavaMailSenderImpl().createMimeMessage();
        when(mail.createMimeMessage()).thenReturn(message);
        new PasswordMailService(mail, "sender@example.com").sendPin("user@example.com", "012345", "RESET");
        verify(mail).send(message);
        assertEquals("Recupera tu contraseña — PAKTAY", message.getSubject());
        var bytes = new ByteArrayOutputStream(); message.writeTo(bytes);
        String body = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("multipart/alternative"));
        assertTrue(body.contains("text/plain")); assertTrue(body.contains("text/html"));
        assertTrue(body.contains("012345")); assertFalse(body.contains("{{pin}}"));
    }
}

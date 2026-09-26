package ec.paktay.business.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SupportEmailsTest {
    @Test
    void normalizaAliasPuntosDeGmailYMayusculas() {
        assertEquals("troll@gmail.com", SupportEmails.normalize("troll+1@gmail.com"));
        assertEquals("troll@gmail.com", SupportEmails.normalize("T.Roll@Gmail.com"));
        assertEquals("troll@gmail.com", SupportEmails.normalize(" troll@googlemail.com "));
    }

    @Test
    void fueraDeGmailConservaLosPuntos() {
        assertEquals("ana.maria@outlook.com", SupportEmails.normalize("Ana.Maria+pruebas@Outlook.com"));
    }

    @Test
    void dominioYDominiosPublicos() {
        assertEquals("mailinator.com", SupportEmails.domain("x@Mailinator.com"));
        assertTrue(SupportEmails.isPublicDomain("gmail.com"));
        assertFalse(SupportEmails.isPublicDomain("mailinator.com"));
    }
}

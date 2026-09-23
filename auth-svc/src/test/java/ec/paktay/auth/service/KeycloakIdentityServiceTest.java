package ec.paktay.auth.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class KeycloakIdentityServiceTest {

    @Test
    void nombreYApellidoSeParten() {
        assertArrayEquals(new String[] {"Christopher", "Vera"},
                KeycloakIdentityService.splitDisplayName("Christopher Vera"));
    }

    @Test
    void variosApellidosQuedanJuntos() {
        assertArrayEquals(new String[] {"Ana", "María López Ruiz"},
                KeycloakIdentityService.splitDisplayName("  Ana   María López Ruiz "));
    }

    @Test
    void unaSolaPalabraSeRepiteComoApellido() {
        assertArrayEquals(new String[] {"Codex", "Codex"},
                KeycloakIdentityService.splitDisplayName("Codex"));
    }
}

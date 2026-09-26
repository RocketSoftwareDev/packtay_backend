package ec.paktay.business.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP del visitante. En producción la API escucha solo en 127.0.0.1 y llega por el túnel
 * de Cloudflare, que escribe CF-Connecting-IP con la IP real (el cliente no puede
 * falsificarla). Sin esa cabecera (local) se usa la dirección de la conexión.
 *
 * No se usa X-Forwarded-For: su primer valor lo puede escribir el propio cliente.
 */
public final class ClientIp {
    static final String CLOUDFLARE_HEADER = "CF-Connecting-IP";
    private static final int MAX_LENGTH = 64;

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String cloudflare = request.getHeader(CLOUDFLARE_HEADER);
        String ip = cloudflare != null && !cloudflare.isBlank() ? cloudflare.trim() : request.getRemoteAddr();
        if (ip == null) return "desconocida";
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }
}

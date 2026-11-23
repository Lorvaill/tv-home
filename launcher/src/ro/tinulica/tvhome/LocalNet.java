package ro.tinulica.tvhome;

import java.net.URL;
import java.util.Locale;

/**
 * Where a password or token may be sent. Home servers (Plex, Home Assistant, the downloader) are
 * usually reached over plain HTTP on the home network; that is accepted only for addresses that
 * stay inside it. Anything on the internet must be HTTPS, so a token never crosses it in clear.
 */
final class LocalNet {

    private LocalNet() {
    }

    /** True when credentials may be sent to this address (https anywhere, http only at home). */
    static boolean safeForSecrets(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            URL url = new URL(address);
            String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
            if ("https".equals(scheme)) {
                return true;
            }
            return "http".equals(scheme) && isHome(url.getHost());
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    /** A private IPv4 address, loopback, or a local name (no dot, .local, .lan, .home.arpa). */
    static boolean isHome(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")
                || (!h.contains(".") && !h.contains(":"))) {
            return true;
        }
        String[] p = h.split("\\.");
        if (p.length != 4) {
            return false;
        }
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return false;   // a public name such as example.com
            }
            if (n[i] < 0 || n[i] > 255) {
                return false;
            }
        }
        return n[0] == 10 || n[0] == 127
                || (n[0] == 172 && n[1] >= 16 && n[1] <= 31)
                || (n[0] == 192 && n[1] == 168)
                || (n[0] == 100 && n[1] >= 64 && n[1] <= 127);   // Tailscale / CGNAT
    }
}

package com.porter.qwertestash.manager;

import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The address policy of the Stash Manager server (1.22.0). https is accepted for any host. Plain http is accepted only for a host that is on this
 * machine or on a private network: {@code localhost}, or an address written out as a number in a loopback range (127/8, ::1), a private IPv4 range
 * (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) or a unique local IPv6 address (fc00::/7). A name is never looked up to decide this
 * (a name that merely resolves to a private address could be changed), so other names need https. Requests are signed either way; https also hides them.
 */
public final class ManagerUrl {
    private ManagerUrl() {
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    /**
     * A usable base address without a query, fragment, credentials or trailing slash, else null: http or https, and http only for a local or private host.
     * The first time a plain http address is accepted, one warning is logged (the address itself is not).
     */
    public static @Nullable URI parse(@Nullable String url) {
        if (url == null) return null;
        try {
            String s = url.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null) return null;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) return null;
            if (scheme.equalsIgnoreCase("https")) return uri;
            if (!scheme.equalsIgnoreCase("http") || !isPrivateHost(uri.getHost())) return null;
            warnPlainHttp();
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True for plain http: the address is accepted, but not encrypted. */
    public static boolean isPlain(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme());
    }

    private static void warnPlainHttp() {
        if (QwerteStashPlugin.LOG != null && WARNED.compareAndSet(false, true)) {
            QwerteStashPlugin.LOG.warn("Qwerte Stash: the Stash Manager address uses plain http. That is accepted for this machine and private networks, "
                + "but the traffic is not encrypted (it is signed). Use https for a server that is reached over the internet.");
        }
    }

    /** Whether a host written in a URL (an IPv6 address in brackets) is this machine or a private network address, decided without any lookup. */
    public static boolean isPrivateHost(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost")) return true; // exactly this name: not "a.localhost" or "localhost.example.com"
        byte[] bytes;
        if (h.startsWith("[") && h.endsWith("]")) bytes = ipv6(h.substring(1, h.length() - 1));
        else bytes = ipv4(h);
        if (bytes == null) return false;
        try {
            InetAddress a = InetAddress.getByAddress(bytes); // by number: no lookup
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
            // fc00::/7: unique local addresses
            return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static byte @Nullable [] ipv4(String h) {
        var m = IPV4.matcher(h);
        if (!m.matches()) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = m.group(i + 1);
            if (part.length() > 1 && part.charAt(0) == '0') return null; // 010 is ambiguous (octal in some parsers)
            int v = Integer.parseInt(part);
            if (v > 255) return null;
            out[i] = (byte) v;
        }
        return out;
    }

    private static byte @Nullable [] ipv6(String h) {
        if (h.isEmpty() || h.indexOf('%') >= 0) return null; // a scope id names an interface; not needed here
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!(c == ':' || c == '.' || Character.digit(c, 16) >= 0)) return null; // only a literal: never a name, so no lookup
        }
        if (h.indexOf(':') < 0) return null;
        try {
            return InetAddress.getByName(h).getAddress(); // a literal: parsed, not looked up
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }
}

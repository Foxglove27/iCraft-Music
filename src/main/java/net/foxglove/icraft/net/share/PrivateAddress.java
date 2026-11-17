package net.foxglove.icraft.net.share;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Tells whether a connection comes from a private network: the home or office network of the server, not the
 * internet. File sharing is only allowed for such connections (see ServerConfig.privateNetworkOnly).
 */
public final class PrivateAddress {
    private PrivateAddress() {}

    /** A network address (IPv4 or IPv6). */
    public static boolean isPrivate(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()) return true; // 127/8, ::1, 169.254/16, fe80::/10
        if (address instanceof Inet4Address) return address.isSiteLocalAddress();       // 10/8, 172.16/12, 192.168/16
        if (address instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            if ((b[0] & 0xFE) == 0xFC) return true;                                    // fc00::/7 unique local
            // ::ffff:a.b.c.d (an IPv4 address written as IPv6)
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (java.net.UnknownHostException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * The address of a connection. A connection that is not over the network at all (the local one of a
     * singleplayer world or of the player hosting a LAN game) counts as private; an unresolved address does not.
     */
    public static boolean isPrivate(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return !inet.isUnresolved() && isPrivate(inet.getAddress());
        }
        return address != null;
    }
}

package com.distanceuprav.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** LAN Wake-on-LAN, with explicit physical Wi-Fi routing even when a VPN is active.
 * The WOL packet, UDP port and three-repeat send logic are unchanged.
 */
public final class WolUtils {
    public static final int PORT = 9;
    private WolUtils() {}

    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        String compact = raw.trim().replace("-", "").replace(":", "").replace(".", "")
                .replace(" ", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[0-9A-F]{12}")) return null;
        if ("000000000000".equals(compact) || "FFFFFFFFFFFF".equals(compact)) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (out.length() > 0) out.append(':');
            out.append(compact, i, i + 2);
        }
        return out.toString();
    }

    private static ConnectivityManager manager(Context context) {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Detect the network used by the app. Does not infer VPN from an IP address. */
    public static boolean vpnActive(Context context) {
        ConnectivityManager cm = manager(context);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    /**
     * Android's active network can be VPN even on home Wi-Fi.
     * Prefer a real Wi-Fi network from allNetworks (API 26+ supported here).
     * A VPN may prohibit binding to underlying networks; callers report that error.
     */
    public static NetworkDetails network(Context context) {
        ConnectivityManager cm = manager(context);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        Network[] candidates = cm.getAllNetworks();
        for (Network candidate : candidates) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(candidate);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            NetworkDetails info = networkInfo(cm, candidate, true);
            if (info != null) return info;
        }
        if (active != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                NetworkDetails fallback = networkInfo(cm, active, false);
                if (fallback != null) return fallback;
            }
        }
        return null;
    }

    private static NetworkDetails networkInfo(ConnectivityManager cm, Network candidate, boolean wifi) {
        LinkProperties props = cm.getLinkProperties(candidate);
        if (props == null) return null;
        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            int prefix = link.getPrefixLength();
            if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                    || prefix < 1 || prefix > 32) continue;
            byte[] raw = address.getAddress();
            int ip = pack(raw);
            int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            int broadcast = ip | ~mask;
            String broadcastText = String.format(Locale.ROOT, "%d.%d.%d.%d",
                    (broadcast >>> 24) & 0xFF, (broadcast >>> 16) & 0xFF,
                    (broadcast >>> 8) & 0xFF, broadcast & 0xFF);
            return new NetworkDetails(candidate, address.getHostAddress(), prefix,
                    broadcastText, props.getInterfaceName(), wifi);
        }
        return null;
    }

    private static int pack(byte[] raw) {
        return ((raw[0] & 0xff) << 24) | ((raw[1] & 0xff) << 16)
                | ((raw[2] & 0xff) << 8) | (raw[3] & 0xff);
    }

    public static boolean sameSubnet(NetworkDetails local, String ip) {
        if (local == null || !isValidIpv4(ip)) return false;
        String[] a = local.ip.split("\\."), b = ip.split("\\.");
        int va = 0, vb = 0;
        for (int i = 0; i < 4; i++) {
            va = (va << 8) | Integer.parseInt(a[i]);
            vb = (vb << 8) | Integer.parseInt(b[i]);
        }
        int mask = (int) (0xFFFFFFFFL << (32 - local.prefix));
        return (va & mask) == (vb & mask);
    }

    public static Network wifiForHost(Context context, String ip) {
        NetworkDetails net = network(context);
        return net != null && net.wifi && sameSubnet(net, ip) ? net.handle : null;
    }

    public static String routeDescription(Context context) {
        boolean vpn = vpnActive(context);
        NetworkDetails net = network(context);
        if (net == null) return vpn
                ? "VPN активен • домашний Wi-Fi недоступен. WoL через интернет требует ретранслятор дома."
                : "Нет доступной локальной IPv4-сети.";
        return (vpn ? "VPN активен • " : "VPN выключен • ") +
                (net.wifi ? "прямой Wi-Fi: " : "локальная сеть: ") +
                net.ip + "/" + net.prefix + " • broadcast " + net.broadcast +
                " • UDP 9" + (vpn ? "\nЕсли VPN запрещает обход, отправка может блокироваться." : "");
    }

    public static void send(Context context, String rawMac) throws Exception {
        String mac = normalizeMac(rawMac);
        if (mac == null) throw new IllegalArgumentException("Invalid MAC");
        NetworkDetails net = network(context);
        if (net == null) throw new IllegalStateException(
                "Нет локальной Wi-Fi/IPv4-сети. Для WoL из другой сети нужен домашний ретранслятор.");
        byte[] packet = buildPacket(mac);
        InetAddress destination = InetAddress.getByName(net.broadcast);
        try (DatagramSocket socket = new DatagramSocket()) {
            // Crucial: VPN may be the Android default route. Send to real home LAN.
            net.handle.bindSocket(socket);
            socket.setBroadcast(true);
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, destination, PORT);
            for (int i = 0; i < 3; i++) {
                socket.send(datagram);
                Thread.sleep(120);
            }
        }
    }

    /** Network-scoped ICMP availability; a failed ping is NOT proof PC is off. */
    public static boolean isOnline(Context context, String ip) {
        if (!isValidIpv4(ip)) return false;
        NetworkDetails local = network(context);
        if (local != null && sameSubnet(local, ip) && local.interfaceName != null) {
            if (ping(ip, local.interfaceName)) return true;
            try {
                NetworkInterface intf = NetworkInterface.getByName(local.interfaceName);
                if (intf != null && InetAddress.getByName(ip).isReachable(intf, 0, 1100))
                    return true;
            } catch (Exception ignored) {}
        }
        // Also try the normal system route: for a properly routed HOME VPN this
        // can reach the PC when the phone is outside the house.
        if (ping(ip, null)) return true;
        try {
            return InetAddress.getByName(ip).isReachable(900);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean ping(String ip, String interfaceName) {
        Process process = null;
        try {
            java.util.ArrayList<String> args = new java.util.ArrayList<>();
            args.add("/system/bin/ping");
            if (interfaceName != null && !interfaceName.isEmpty()) {
                args.add("-I");
                args.add(interfaceName);
            }
            args.add("-c");
            args.add("1");
            args.add("-W");
            args.add("1");
            args.add(ip);
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!process.waitFor(2200, TimeUnit.MILLISECONDS)) return false;
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    public static boolean isValidIpv4(String ip) {
        if (ip == null || !ip.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String p : parts) {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String maskMac(String mac) {
        String n = normalizeMac(mac);
        return n == null ? "не задан" : "••:••:••:••:" + n.substring(12);
    }

    private static byte[] buildPacket(String mac) {
        String[] parts = mac.split(":");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] out = new byte[102];
        for (int i = 0; i < 6; i++) out[i] = (byte) 0xff;
        for (int offset = 6; offset < out.length; offset += 6)
            System.arraycopy(macBytes, 0, out, offset, 6);
        return out;
    }

    public static final class NetworkDetails {
        public final Network handle;
        public final String ip;
        public final int prefix;
        public final String broadcast;
        public final String interfaceName;
        public final boolean wifi;
        NetworkDetails(Network handle, String ip, int prefix, String broadcast,
                       String interfaceName, boolean wifi) {
            this.handle = handle;
            this.ip = ip;
            this.prefix = prefix;
            this.broadcast = broadcast;
            this.interfaceName = interfaceName;
            this.wifi = wifi;
        }
    }
}

package com.distanceuprav.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;
// Network, WoL and reachability logic adapted from SofaPower.
import java.util.concurrent.TimeUnit;

public final class WolUtils {
    public static final int PORT = 9;

    private WolUtils() {}

    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        String compact = raw.trim().replace("-", "").replace(":", "").replace(".", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[0-9A-F]{12}")) return null;
        if ("000000000000".equals(compact) || "FFFFFFFFFFFF".equals(compact)) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (out.length() > 0) out.append(':');
            out.append(compact, i, i + 2);
        }
        return out.toString();
    }

    public static NetworkDetails network(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        if (active == null) return null;
        LinkProperties props = cm.getLinkProperties(active);
        if (props == null) return null;

        for (LinkAddress link : props.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            int prefix = link.getPrefixLength();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                byte[] raw = address.getAddress();
                int ip = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
                int broadcast = ip | ~mask;
                String broadcastText = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        (broadcast >>> 24) & 0xFF,
                        (broadcast >>> 16) & 0xFF,
                        (broadcast >>> 8) & 0xFF,
                        broadcast & 0xFF);
                return new NetworkDetails(address.getHostAddress(), prefix, broadcastText);
            }
        }
        return null;
    }

    public static void send(Context context, String rawMac) throws Exception {
        String mac = normalizeMac(rawMac);
        if (mac == null) throw new IllegalArgumentException("Invalid MAC");
        NetworkDetails net = network(context);
        if (net == null) throw new IllegalStateException("No local IPv4 network");

        byte[] packet = buildPacket(mac);
        InetAddress destination = InetAddress.getByName(net.broadcast);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            DatagramPacket datagram = new DatagramPacket(packet, packet.length, destination, PORT);
            for (int i = 0; i < 3; i++) {
                socket.send(datagram);
                Thread.sleep(120);
            }
        }
    }

    public static boolean isOnline(String ip) {
        if (!isValidIpv4(ip)) return false;
        try {
            Process process = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start();
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0) return true;
            process.destroy();
        } catch (Exception ignored) {}
        try {
            return InetAddress.getByName(ip).isReachable(900);
        } catch (Exception ignored) {
            return false;
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
        if (n == null) return "не задан";
        return "••:••:••:••:" + n.substring(12);
    }

    private static byte[] buildPacket(String mac) {
        String[] parts = mac.split(":");
        byte[] macBytes = new byte[6];
        for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] out = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) out[i] = (byte) 0xFF;
        for (int offset = 6; offset < out.length; offset += 6) System.arraycopy(macBytes, 0, out, offset, 6);
        return out;
    }

    public static final class NetworkDetails {
        public final String ip;
        public final int prefix;
        public final String broadcast;
        NetworkDetails(String ip, int prefix, String broadcast) {
            this.ip = ip;
            this.prefix = prefix;
            this.broadcast = broadcast;
        }
    }
}

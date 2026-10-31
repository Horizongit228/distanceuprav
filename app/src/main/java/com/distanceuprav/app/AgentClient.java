package com.distanceuprav.app;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** HTTPS client that pins the complete Windows Agent certificate fingerprint.
 * A QR is transferred in-person from the PC screen; no TOFU, no plain HTTP.
 */
public final class AgentClient {
    private AgentClient() {}

    public static AgentStore.Config parsePairing(String raw) throws Exception {
        if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Неверный код привязки");
        Uri uri = Uri.parse(raw.trim());
        if (!"distanceuprav".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost()))
            throw new IllegalArgumentException("Это не QR-код DistanceUprav Agent");
        String data = uri.getQueryParameter("data");
        if (data == null || data.length() > 2048) throw new IllegalArgumentException("Недопустимые данные QR");
        byte[] decoded = Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        JSONObject obj = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        if (!"distanceuprav-agent".equals(obj.optString("t")) || obj.optInt("v") != 1)
            throw new IllegalArgumentException("Неизвестная версия QR");
        String ip = obj.optString("ip", "").trim();
        int port = obj.optInt("port", 0);
        String fp = obj.optString("fp", "").replace(":", "").toUpperCase(Locale.ROOT);
        String token = obj.optString("token", "");
        if (!WolUtils.isValidIpv4(ip) || port < 1 || port > 65535
                || !fp.matches("[A-F0-9]{64}") || !token.matches("[A-Za-z0-9_-]{32,256}"))
            throw new IllegalArgumentException("Неверный IP, порт, отпечаток или секрет");
        return new AgentStore.Config(ip, port, fp, token);
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[4096];
            int n;
            while ((n = input.read(data)) != -1) {
                if (buffer.size() + n > 1024 * 1024) throw new IllegalStateException("Слишком большой ответ агента");
                buffer.write(data, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    private static byte[] hex(String fp) {
        byte[] output = new byte[fp.length() / 2];
        for (int i = 0; i < output.length; i++)
            output[i] = (byte) Integer.parseInt(fp.substring(i * 2, i * 2 + 2), 16);
        return output;
    }

    public static JSONObject request(AgentStore.Config config, String method, String path, JSONObject payload) throws Exception {
        if (config == null || !path.matches("/v1/[a-z/]+"))
            throw new IllegalArgumentException("Неизвестная команда");
        final byte[] required = hex(config.fingerprint);
        X509TrustManager pin = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client trust not supported");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Нет сертификата агента");
                chain[0].checkValidity();
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(required, digest)) throw new CertificateException("Подпись агента изменилась");
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException("Невозможно проверить TLS", e);
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{pin}, null);
        URL url = new URL("https://" + config.ip + ":" + config.port + path);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setSSLSocketFactory(ssl.getSocketFactory());
        // The exact pinned certificate authenticates the agent; URL hostname must still be our selected IP.
        c.setHostnameVerifier((hostname, session) -> config.ip.equals(hostname));
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + config.token);
        c.setRequestProperty("Accept", "application/json");
        try {
            if ("POST".equals(method)) {
                byte[] bytes = (payload == null ? "{}" : payload.toString()).getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream o = c.getOutputStream()) { o.write(bytes); }
            }
            int code = c.getResponseCode();
            String content = new String(readAll(code >= 400 ? c.getErrorStream() : c.getInputStream()), StandardCharsets.UTF_8);
            JSONObject result = new JSONObject(content);
            if (code >= 400) throw new IllegalStateException(result.optString("error", "HTTP " + code));
            return result;
        } finally {
            c.disconnect();
        }
    }
}

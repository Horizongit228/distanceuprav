package com.distanceuprav.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-local pairing credentials encrypted with AndroidKeyStore AES-GCM. */
public final class AgentStore {
    private static final String NAME = "distanceuprav_agent_v1";
    private static final String KEY_ALIAS = "DistanceUpravAgentKey_v1";
    private final SharedPreferences prefs;

    public static final class Config {
        public final String ip, fingerprint, token;
        public final int port;
        public Config(String ip, int port, String fingerprint, String token) {
            this.ip=ip; this.port=port; this.fingerprint=fingerprint; this.token=token;
        }
    }

    public AgentStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS))
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }

    public void save(Config config) throws Exception {
        String serialized = config.ip + "|" + config.port + "|" + config.fingerprint + "|" + config.token;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] payload = cipher.doFinal(serialized.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] packed = new byte[iv.length + payload.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(payload, 0, packed, iv.length, payload.length);
        prefs.edit().putString("pair", Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
    }

    public Config read() {
        String b64 = prefs.getString("pair", null);
        if (b64 == null) return null;
        try {
            byte[] packed = Base64.decode(b64, Base64.NO_WRAP);
            if (packed.length < 29) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Arrays.copyOfRange(packed, 0, 12)));
            String[] s = new String(cipher.doFinal(packed, 12, packed.length - 12),
                    StandardCharsets.UTF_8).split("\\|", -1);
            if (s.length != 4 || !WolUtils.isValidIpv4(s[0])) return null;
            return new Config(s[0], Integer.parseInt(s[1]), s[2], s[3]);
        } catch(Exception e) {
            return null;
        }
    }

    public void clear() {
        prefs.edit().remove("pair").apply();
    }
}

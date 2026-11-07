package com.tj.skyrim.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Steam refresh token encrypted with an AES-GCM key in the Android
 * Keystore. Only the ciphertext and the account name go to SharedPreferences;
 * the token never appears in plain text or in logs.
 */
public final class SteamTokenStore {
    public static final class Token {
        public final String refreshToken;
        public final String accountName;

        Token(String refreshToken, String accountName) {
            this.refreshToken = refreshToken;
            this.accountName = accountName;
        }
    }

    private static final String PREFS = "steam";
    private static final String ALIAS = "steam-refresh-token";
    private static final int IV_LENGTH = 12;

    private final SharedPreferences prefs;

    public SteamTokenStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean has() {
        return prefs.contains("token");
    }

    public String accountName() {
        return prefs.getString("account", "");
    }

    public void save(String refreshToken, String accountName) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(encrypted, 0, blob, iv.length, encrypted.length);
            prefs.edit()
                    .putString("token", Base64.encodeToString(blob, Base64.NO_WRAP))
                    .putString("account", accountName == null ? "" : accountName)
                    .apply();
        } catch (Exception e) {
            clear();
        }
    }

    public Token load() {
        String encoded = prefs.getString("token", null);
        if (encoded == null) {
            return null;
        }
        try {
            byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, blob, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
            return new Token(new String(plain, StandardCharsets.UTF_8),
                    prefs.getString("account", ""));
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    public void clear() {
        prefs.edit().remove("token").remove("account").apply();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}

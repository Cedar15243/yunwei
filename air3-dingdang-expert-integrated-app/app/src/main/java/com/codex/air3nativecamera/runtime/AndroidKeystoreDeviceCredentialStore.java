package com.codex.air3nativecamera.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts the release activation record with a non-exportable Android Keystore key. */
public final class AndroidKeystoreDeviceCredentialStore implements DeviceCredentialStore {
    interface Clock {
        long nowMillis();
    }

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREFERENCES_NAME = "v9-device-activation";
    private static final String FIELD_SCHEMA = "schema";
    private static final String FIELD_IV = "iv";
    private static final String FIELD_CIPHERTEXT = "ciphertext";

    private final Context context;
    private final SharedPreferences preferences;
    private final String keyAlias;
    private final byte[] associatedData;
    private final Clock clock;

    public AndroidKeystoreDeviceCredentialStore(Context context) {
        this(context, new Clock() {
            @Override
            public long nowMillis() {
                return System.currentTimeMillis();
            }
        });
    }

    AndroidKeystoreDeviceCredentialStore(Context context, Clock clock) {
        if (context == null || clock == null) {
            throw new IllegalArgumentException("device_credential_store_invalid");
        }
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        this.keyAlias = this.context.getPackageName() + ".v9.device.activation.v1";
        this.associatedData = (this.context.getPackageName() + "|device-activation|1")
                .getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public synchronized DeviceActivationRecord load() throws IOException {
        String ivBase64 = preferences.getString(FIELD_IV, "");
        String ciphertextBase64 = preferences.getString(FIELD_CIPHERTEXT, "");
        int schema = preferences.getInt(FIELD_SCHEMA, -1);
        if (ivBase64.length() == 0 && ciphertextBase64.length() == 0) return null;
        if (schema != DeviceActivationRecord.CURRENT_SCHEMA_VERSION
                || ivBase64.length() == 0 || ciphertextBase64.length() == 0) {
            clear();
            return null;
        }
        try {
            KeyStore keyStore = loadKeyStore();
            SecretKey secretKey = (SecretKey) keyStore.getKey(keyAlias, null);
            if (secretKey == null) {
                clear();
                return null;
            }
            byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(associatedData);
            DeviceActivationRecord record = DeviceActivationRecord.parse(
                    new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            if (!record.isValidAt(clock.nowMillis())) {
                clear();
                return null;
            }
            return record;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            try {
                clear();
            } catch (IOException clearError) {
                error.addSuppressed(clearError);
            }
            throw new IOException("device_credential_decryption_failed", error);
        }
    }

    @Override
    public synchronized void save(DeviceActivationRecord record) throws IOException {
        if (record == null || !record.isValidAt(clock.nowMillis())) {
            throw new IOException("device_credential_record_invalid");
        }
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(
                    record.toJson().getBytes(StandardCharsets.UTF_8));
            boolean stored = preferences.edit()
                    .clear()
                    .putInt(FIELD_SCHEMA, DeviceActivationRecord.CURRENT_SCHEMA_VERSION)
                    .putString(FIELD_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(FIELD_CIPHERTEXT,
                            Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .commit();
            if (!stored) throw new IOException("device_credential_write_failed");
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("device_credential_encryption_failed", error);
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        boolean cleared = preferences.edit().clear().commit();
        try {
            KeyStore keyStore = loadKeyStore();
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias);
        } catch (Exception error) {
            throw new IOException("device_credential_key_delete_failed", error);
        }
        if (!cleared) throw new IOException("device_credential_clear_failed");
    }

    @Override
    public synchronized boolean clearIfCurrent(String bootstrapCredential) throws IOException {
        DeviceActivationRecord current = load();
        String candidate = bootstrapCredential == null ? "" : bootstrapCredential.trim();
        if (current == null || !current.bootstrapCredential().equals(candidate)) return false;
        clear();
        return true;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = loadKeyStore();
        SecretKey existing = (SecretKey) keyStore.getKey(keyAlias, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return keyStore;
    }
}

package pl.rafik.ssiptv.data;

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

/** Hasło i login serwera są szyfrowane kluczem prywatnym Android Keystore. */
public final class SecureSettings {
    private static final String KEY_ALIAS = "ss_iptv_server_v1";
    private final SharedPreferences prefs;

    public SecureSettings(Context context) {
        prefs = context.getSharedPreferences("ss_settings", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String clear) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] payload = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + payload.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(payload, 0, combined, iv.length, payload.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String saved) throws Exception {
        byte[] payload = Base64.decode(saved, Base64.DEFAULT);
        if (payload.length <= 12) throw new IllegalArgumentException("Uszkodzony zapis");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, payload, 0, 12));
        return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
    }

    public void saveServer(String server, String username, String password) throws Exception {
        String secureUser = encrypt(username);
        String securePass = encrypt(password);
        prefs.edit().putString("server", server)
                .putString("username", secureUser)
                .putString("password", securePass).apply();
    }

    public XtreamClient.Credentials credentials() throws Exception {
        String server = prefs.getString("server", "");
        String user = prefs.getString("username", "");
        String pass = prefs.getString("password", "");
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) return null;
        return new XtreamClient.Credentials(server, decrypt(user), decrypt(pass));
    }

    public String serverName() {
        return prefs.getString("server", "");
    }

    public void clearServer() {
        prefs.edit().remove("server").remove("username").remove("password").apply();
    }

    /** Pusta wartość oznacza jednoczesny widok wszystkich lokalnych list. */
    public String activeSource() {
        return prefs.getString("active_source", "");
    }

    public void setActiveSource(String source) {
        prefs.edit().putString("active_source", source == null ? "" : source).apply();
    }

    public long lastSync() {
        return prefs.getLong("epg_last_sync", 0);
    }

    /** Po zmianie numerów rekordów M3U następne wejście odświeża mapowanie XMLTV. */
    public void invalidateEpgCache() {
        prefs.edit().putLong("epg_last_sync", 0).putLong("epg_last_attempt", 0).apply();
    }

    public long lastEpgAttempt() {
        return prefs.getLong("epg_last_attempt", 0);
    }

    public void saveEpgAttempt(long timestamp) {
        prefs.edit().putLong("epg_last_attempt", timestamp).apply();
    }

    public String epgStatus() {
        return prefs.getString("epg_status", "EPG oczekuje na synchronizację");
    }

    public void saveEpgStatus(long timestamp, String status) {
        SharedPreferences.Editor editor = prefs.edit().putString("epg_status", status);
        if (timestamp > 0) editor.putLong("epg_last_sync", timestamp);
        editor.apply();
    }

    public void saveEpgSourceStatus(int index, String status) {
        prefs.edit().putString("epg_source_" + index, status).apply();
    }

    public String epgSourceStatus(int index) {
        return prefs.getString("epg_source_" + index, "Jeszcze nie sprawdzano");
    }
}

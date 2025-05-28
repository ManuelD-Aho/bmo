package com.bmo.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.prefs.Preferences;

/**
 * Utilitaires de sécurité pour l'application BMO
 */
public class SecurityUtils {
    private static final String SALT = "BMO_SALT_2025";
    private static final String CREDENTIALS_KEY = "bmo.credentials";
    private static final String REMEMBER_ME_KEY = "bmo.remember";

    /**
     * Chiffre une chaîne
     */
    public static String encrypt(String property) throws Exception {
        SecretKey key = generateSecretKey();
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encryptedBytes = cipher.doFinal(property.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * Déchiffre une chaîne
     */
    public static String decrypt(String encryptedProperty) throws Exception {
        SecretKey key = generateSecretKey();
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedProperty));
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    /**
     * Génère une clé secrète basée sur l'identifiant unique de la machine
     */
    private static SecretKey generateSecretKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        String machineUniqueId = getMachineUniqueId();
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(machineUniqueId.toCharArray(), SALT.getBytes(), 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /**
     * Obtient un identifiant unique pour la machine
     */
    private static String getMachineUniqueId() {
        try {
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            String username = System.getProperty("user.name");
            String home = System.getProperty("user.home");

            String combined = osName + osVersion + username + home;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            return "fallback-id";
        }
    }

    /**
     * Sauvegarde les identifiants de manière sécurisée
     */
    public static void saveCredentials(String username, String password, boolean rememberMe) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(SecurityUtils.class);

            if (rememberMe) {
                String credentials = username + ":" + password;
                String encrypted = encrypt(credentials);
                prefs.put(CREDENTIALS_KEY, encrypted);
                prefs.putBoolean(REMEMBER_ME_KEY, true);
            } else {
                prefs.remove(CREDENTIALS_KEY);
                prefs.putBoolean(REMEMBER_ME_KEY, false);
            }

            prefs.flush();
        } catch (Exception e) {
            // Log l'erreur
        }
    }

    /**
     * Charge les identifiants sauvegardés
     * @return Un tableau avec le nom d'utilisateur et le mot de passe, ou null si pas sauvegardés
     */
    public static String[] loadCredentials() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(SecurityUtils.class);
            boolean rememberMe = prefs.getBoolean(REMEMBER_ME_KEY, false);

            if (!rememberMe) {
                return null;
            }

            String encrypted = prefs.get(CREDENTIALS_KEY, null);
            if (encrypted == null) {
                return null;
            }

            String decrypted = decrypt(encrypted);
            return decrypted.split(":", 2);
        } catch (Exception e) {
            // Log l'erreur
            return null;
        }
    }
}
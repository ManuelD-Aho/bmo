package com.bmo.client;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Utilitaire pour la gestion des configurations client
 */
public class ConfigUtils {
    private static final Logger LOGGER = Logger.getLogger(ConfigUtils.class.getName());

    private static final String SERVER_HOST_KEY = "server.host";
    private static final String SERVER_PORT_KEY = "server.port";
    private static final String SERVER_TLS_KEY = "server.tls";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 12345;

    /**
     * Récupère l'adresse du serveur depuis les préférences
     * @return L'adresse du serveur, ou localhost par défaut
     */
    public static String retrieveServerHost() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            return prefs.get(SERVER_HOST_KEY, DEFAULT_HOST);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la récupération de l'adresse du serveur", e);
            return DEFAULT_HOST;
        }
    }

    /**
     * Récupère le port du serveur depuis les préférences
     * @return Le port du serveur, ou 12345 par défaut
     */
    public static int retrieveServerPort() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            return prefs.getInt(SERVER_PORT_KEY, DEFAULT_PORT);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la récupération du port du serveur", e);
            return DEFAULT_PORT;
        }
    }

    /**
     * Enregistre l'adresse du serveur dans les préférences
     * @param host L'adresse du serveur
     */
    public static void saveServerHost(String host) {
        try {
            if (host == null || host.trim().isEmpty()) {
                host = DEFAULT_HOST;
            }

            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            prefs.put(SERVER_HOST_KEY, host);
            prefs.flush();
            LOGGER.info("Adresse du serveur enregistrée: " + host);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de l'enregistrement de l'adresse du serveur", e);
        }
    }

    /**
     * Enregistre le port du serveur dans les préférences
     * @param port Le port du serveur
     */
    public static void saveServerPort(int port) {
        try {
            if (port <= 0 || port > 65535) {
                port = DEFAULT_PORT;
            }

            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            prefs.putInt(SERVER_PORT_KEY, port);
            prefs.flush();
            LOGGER.info("Port du serveur enregistré: " + port);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de l'enregistrement du port du serveur", e);
        }
    }

    /**
     * Récupère l'état du chiffrement TLS depuis les préférences
     * @return true si le TLS est activé, false sinon
     */
    public static boolean retrieveTlsEnabled() {
        try {
            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            return prefs.getBoolean(SERVER_TLS_KEY, false);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la récupération du paramètre TLS", e);
            return false;
        }
    }

    /**
     * Enregistre l'état du chiffrement TLS dans les préférences
     * @param enabled true pour activer TLS, false sinon
     */
    public static void saveTlsEnabled(boolean enabled) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(ConfigUtils.class);
            prefs.putBoolean(SERVER_TLS_KEY, enabled);
            prefs.flush();
            LOGGER.info("Paramètre TLS enregistré: " + enabled);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de l'enregistrement du paramètre TLS", e);
        }
    }
}
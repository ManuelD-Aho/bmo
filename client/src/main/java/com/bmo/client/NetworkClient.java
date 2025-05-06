package com.bmo.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client réseau pour la communication avec le serveur BMO.
 * Cette classe gère la connexion socket, l'envoi et la réception de messages.
 */
public class NetworkClient {
    private static final Logger LOGGER = Logger.getLogger(NetworkClient.class.getName());

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Thread listenerThread;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    // Callbacks pour les événements réseau
    private final Map<String, List<Consumer<String>>> messageHandlers = new HashMap<>();
    private Consumer<Void> disconnectionHandler;

    /**
     * Établit une connexion au serveur
     *
     * @param host L'adresse du serveur
     * @param port Le port d'écoute du serveur
     * @return true si la connexion est établie, false sinon
     * @throws IOException En cas d'erreur d'E/S lors de la connexion
     */
    public boolean connect(String host, int port) throws IOException {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            connected.set(true);

            // Démarrer le thread d'écoute
            startListeningThread();

            LOGGER.info("Connexion établie avec le serveur " + host + ":" + port);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de la connexion au serveur", e);
            throw e;
        }
    }

    /**
     * Ferme la connexion au serveur
     */
    public void disconnect() {
        if (!connected.get()) return;

        try {
            connected.set(false);

            if (listenerThread != null) {
                listenerThread.interrupt();
            }

            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();

            LOGGER.info("Déconnexion du serveur");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la déconnexion", e);
        }
    }

    /**
     * Indique si le client est connecté au serveur
     */
    public boolean isConnected() {
        return connected.get() && socket != null && !socket.isClosed();
    }

    /**
     * Envoie un message au serveur
     *
     * @param message Le message à envoyer
     * @return true si le message a été envoyé sans erreur, false sinon
     */
    public boolean sendMessage(String message) {
        if (!isConnected() || out == null) return false;

        LOGGER.fine("Envoi au serveur: " + message);
        out.println(message);
        return !out.checkError();
    }

    /**
     * Démarre le thread d'écoute pour les messages du serveur
     */
    private void startListeningThread() {
        listenerThread = new Thread(() -> {
            try {
                String message;
                while (connected.get() && (message = in.readLine()) != null) {
                    final String finalMessage = message;
                    LOGGER.fine("Message reçu du serveur: " + finalMessage);

                    // Traiter le message
                    String command = extractCommand(finalMessage);
                    String payload = extractPayload(finalMessage);

                    if (command != null && !command.isEmpty()) {
                        notifyMessageHandlers(command, payload);
                    }
                }
            } catch (IOException e) {
                if (connected.get()) {
                    LOGGER.log(Level.SEVERE, "Erreur lors de la lecture des messages du serveur", e);
                    handleDisconnection();
                }
            }
        });

        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Gère une déconnexion inattendue
     */
    private void handleDisconnection() {
        connected.set(false);

        if (disconnectionHandler != null) {
            disconnectionHandler.accept(null);
        }
    }

    /**
     * Enregistre un gestionnaire pour les messages d'un type spécifique
     *
     * @param messageType Le type de message à gérer
     * @param handler Le gestionnaire à appeler lorsque ce type de message est reçu
     */
    public void registerMessageHandler(String messageType, Consumer<String> handler) {
        messageHandlers.computeIfAbsent(messageType, k -> new ArrayList<>()).add(handler);
    }

    /**
     * Supprime un gestionnaire pour les messages d'un type spécifique
     *
     * @param messageType Le type de message
     * @param handler Le gestionnaire à supprimer
     */
    public void unregisterMessageHandler(String messageType, Consumer<String> handler) {
        if (messageHandlers.containsKey(messageType)) {
            messageHandlers.get(messageType).remove(handler);
        }
    }

    /**
     * Définit le gestionnaire pour les déconnexions
     *
     * @param handler Le gestionnaire à appeler en cas de déconnexion
     */
    public void setDisconnectionHandler(Consumer<Void> handler) {
        this.disconnectionHandler = handler;
    }

    /**
     * Notifie les gestionnaires enregistrés pour un type de message
     *
     * @param messageType Le type de message
     * @param payload La charge utile du message
     */
    private void notifyMessageHandlers(String messageType, String payload) {
        executorService.submit(() -> {
            if (messageHandlers.containsKey(messageType)) {
                for (Consumer<String> handler : messageHandlers.get(messageType)) {
                    try {
                        handler.accept(payload);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Erreur dans le gestionnaire de message pour " + messageType, e);
                    }
                }
            }
        });
    }

    /**
     * Extrait la commande d'un message
     */
    private String extractCommand(String message) {
        int separatorIndex = message.indexOf('|');
        return separatorIndex == -1 ? message : message.substring(0, separatorIndex);
    }

    /**
     * Extrait la charge utile d'un message
     */
    private String extractPayload(String message) {
        int separatorIndex = message.indexOf('|');
        return separatorIndex == -1 ? "" : message.substring(separatorIndex + 1);
    }

    /**
     * Ferme proprement toutes les ressources du client
     */
    public void shutdown() {
        disconnect();
        executorService.shutdown();
    }
}
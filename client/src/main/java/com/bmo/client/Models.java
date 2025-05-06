package com.bmo.client;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Classe principale des modèles côté client pour l'application BMO.
 * Gère les données et la communication avec le serveur.
 */
public class Models {
    private static final Logger LOGGER = Logger.getLogger(Models.class.getName());
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // Ajouter ces attributs pour la reconnexion
    private String lastConnectedHost;
    private int lastConnectedPort;
    private int reconnectionAttempts = 0;
    private static final int MAX_RECONNECTION_ATTEMPTS = 3;
    private static final int RECONNECTION_DELAY_MS = 2000;
    // Instance singleton du modèle
    private static Models instance;
    // Cache des messages envoyés pendant une déconnexion
    private final List<String> pendingMessages = new ArrayList<>();
    private boolean useOfflineCache = true;
    private static final long HEARTBEAT_INTERVAL_MS = 30000; // 30 secondes
    private static final long SERVER_TIMEOUT_MS = 60000; // 60 secondes
    private Timer heartbeatTimer;
    private long lastServerResponseTime;
    // État de la connexion
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private Thread listenerThread;

    // Callbacks pour les différents événements
    private final Map<String, List<Consumer<String>>> eventHandlers = new HashMap<>();

    // État de l'utilisateur
    private final ObjectProperty<User> currentUser = new SimpleObjectProperty<>();
    private final BandwidthMonitor bandwidthMonitor = new BandwidthMonitor(1000);

    // Collections observables pour l'interface utilisateur
    private final ObservableList<Meeting> meetings = FXCollections.observableArrayList();
    private final ObservableList<User> users = FXCollections.observableArrayList();
    private final ObservableList<Message> currentMeetingMessages = FXCollections.observableArrayList();
    private final ObservableList<User> currentMeetingParticipants = FXCollections.observableArrayList();
    private final ObservableList<Poll> currentMeetingPolls = FXCollections.observableArrayList();

    // État de la réunion actuelle
    private final ObjectProperty<Meeting> currentMeeting = new SimpleObjectProperty<>();

    // Ajouter cette méthode publique pour faciliter les tests
    /**
     * Déclenche un événement manuellement.
     * Cette méthode est principalement destinée aux tests.
     *
     * @param event Le type d'événement à déclencher
     * @param payload La charge utile associée à l'événement
     */
    public void triggerEvent(String event, String payload) {
        processEvent(event, payload);
    }
    /**
     * Constructeur privé pour le singleton
     */
    // Modifier le constructeur pour configurer le moniteur
    private Models() {
        registerDefaultEventHandlers();

        // Configurer le moniteur de bande passante
        bandwidthMonitor.setSpeedUpdateCallback((downloadSpeed, uploadSpeed) -> {
            // Notifier l'interface utilisateur des statistiques de bande passante
            // On pourrait créer un événement spécifique
            // ou utiliser des propriétés observables
            if (currentMeeting.get() != null) {
                // Envoyer les statistiques au serveur périodiquement pour les réunions actives
                int meetingId = currentMeeting.get().getId();
                int userId = currentUser.get().getId();
                long bytesReceived = bandwidthMonitor.getTotalBytesReceived();
                long bytesSent = bandwidthMonitor.getTotalBytesSent();

                sendMessage("BANDWIDTH_STATS|" + meetingId + "|" + userId + "|" +
                        bytesReceived + "|" + bytesSent);
            }
        });
    }
    /**
     * Active ou désactive le mode cache hors-ligne
     */
    public void setUseOfflineCache(boolean useOfflineCache) {
        this.useOfflineCache = useOfflineCache;
    }
    /**
     * Obtenir l'instance unique du modèle
     */
    public static synchronized Models getInstance() {
        if (instance == null) {
            instance = new Models();
        }
        return instance;
    }
    public boolean loginWithRememberOption(String username, String password, boolean rememberMe) {
        if (rememberMe) {
            SecurityUtils.saveCredentials(username, password, true);
        }

        return login(username, password);
    }

    // Ajouter une méthode pour charger les identifiants sauvegardés
    public String[] loadSavedCredentials() {
        return SecurityUtils.loadCredentials();
    }

    /**
     * Enregistre les gestionnaires d'événements par défaut pour les messages du serveur
     */
    private void registerDefaultEventHandlers() {
        // Authentification
        registerEventHandler("AUTH_OK", this::handleAuthOk);
        registerEventHandler("AUTH_FAILED", this::handleAuthFailed);
        registerEventHandler("HEARTBEAT_RESPONSE", this::handleHeartbeatResponse);

        // Meetings
        registerEventHandler("MEETINGS_LIST", this::handleMeetingsList);
        registerEventHandler("MEETING_CREATED", this::handleMeetingCreated);
        registerEventHandler("MEETING_UPDATED", this::handleMeetingUpdated);
        registerEventHandler("MEETING_DELETED", this::handleMeetingDeleted);
        registerEventHandler("JOIN_OK", this::handleJoinOk);
        registerEventHandler("JOIN_FAILED", this::handleJoinFailed);
        registerEventHandler("MEETING_CLOSED", this::handleMeetingClosed);

        // Participants
        registerEventHandler("USER_JOINED", this::handleUserJoined);
        registerEventHandler("USER_LEFT", this::handleUserLeft);
        registerEventHandler("USERS_LIST", this::handleUsersList);

        // Chat et interactions
        registerEventHandler("CHAT_MSG", this::handleChatMessage);
        registerEventHandler("SPEAK_REQUEST", this::handleSpeakRequest);
        registerEventHandler("SPEAK_GRANTED", this::handleSpeakGranted);
        registerEventHandler("SPEAK_DENIED", this::handleSpeakDenied);

        // Sondages
        registerEventHandler("POLL", this::handleNewPoll);
        registerEventHandler("POLL_RESULTS", this::handlePollResults);

        // Divers
        registerEventHandler("ERROR", this::handleError);
    }

    /**
     * Établit la connexion au serveur BMO
     *
     * @param host L'adresse du serveur
     * @param port Le port d'écoute du serveur
     * @return true si la connexion est établie, false sinon
     */
    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            connected.set(true);
            reconnectionAttempts = 0; // Réinitialiser les tentatives en cas de succès

            // Enregistrer les informations pour la reconnexion
            lastConnectedHost = host;
            lastConnectedPort = port;


            // Démarrer le thread d'écoute des messages du serveur
            listenerThread = new Thread(this::listenForMessages);
            listenerThread.setDaemon(true);
            listenerThread.start();

            if (connected.get()) {
                // Démarrer le heartbeat
                startHeartbeatTimer();
            }
            LOGGER.info("Connexion établie avec le serveur " + host + ":" + port);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de la connexion au serveur", e);
            return false;
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

            if (heartbeatTimer != null) {
                heartbeatTimer.cancel();
                heartbeatTimer = null;
            }

            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null) socket.close();

            // Réinitialiser l'état
            currentUser.set(null);
            meetings.clear();
            currentMeeting.set(null);
            currentMeetingMessages.clear();
            currentMeetingParticipants.clear();

            LOGGER.info("Déconnexion du serveur");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Erreur lors de la déconnexion", e);
        }
    }

    /**
     * Thread d'écoute des messages du serveur
     */
    private void listenForMessages() {
        try {
            String message;
            while (connected.get() && (message = in.readLine()) != null) {
                // Mettre à jour le timestamp de la dernière réponse
                lastServerResponseTime = System.currentTimeMillis();

                // Compter les octets reçus (+ 2 pour le \r\n)
                bandwidthMonitor.addBytesReceived(message.getBytes().length + 2);

                final String finalMessage = message;
                LOGGER.fine("Message reçu du serveur: " + finalMessage);

                // Traiter le message dans le thread JavaFX
                String command = extractCommand(finalMessage);
                String payload = extractPayload(finalMessage);

                if (command != null && !command.isEmpty()) {
                    processEvent(command, payload);
                }
            }
        } catch (IOException e) {
            if (connected.get()) {
                LOGGER.log(Level.SEVERE, "Erreur lors de la lecture des messages du serveur", e);
                // Tenter de se reconnecter ou informer l'utilisateur
                executorService.submit(this::handleDisconnection);
            }
        }
    }
    /**
     * Gère la réponse de heartbeat du serveur
     *
     * @param payload La charge utile de la réponse
     */
    private void handleHeartbeatResponse(String payload) {
        // Mettre à jour le timestamp de la dernière réponse
        lastServerResponseTime = System.currentTimeMillis();
    }
    /**
     * Gère une déconnexion inattendue
     */
    private void handleDisconnection() {
        connected.set(false);
        // Notifier l'interface utilisateur qu'il y a eu déconnexion
        processEvent("DISCONNECTED", "La connexion au serveur a été perdue");
        // Tenter de se reconnecter automatiquement
        attemptReconnection();
    }
    // Ajouter une méthode de nettoyage pour la fermeture de l'application
    public void shutdown() {
        disconnect();
        executorService.shutdown();
        bandwidthMonitor.shutdown();
    }

    // Ajouter des accesseurs pour les statistiques de bande passante
    public double getCurrentDownloadSpeed() {
        return bandwidthMonitor.getDownloadSpeed();
    }

    public double getCurrentUploadSpeed() {
        return bandwidthMonitor.getUploadSpeed();
    }

    public long getTotalBytesReceived() {
        return bandwidthMonitor.getTotalBytesReceived();
    }

    public long getTotalBytesSent() {
        return bandwidthMonitor.getTotalBytesSent();
    }
    /**
     * Obtient le moniteur de bande passante
     * @return Le moniteur de bande passante
     */
    public BandwidthMonitor getBandwidthMonitor() {
        return bandwidthMonitor;
    }
    /**
     * Envoie un message au serveur
     *
     * @param message Le message à envoyer
     * @return true si le message a été envoyé, false sinon
     */
    public boolean sendMessage(String message) {
        if (!connected.get() || out == null) {
            if (useOfflineCache) {
                LOGGER.info("Ajout du message au cache pour envoi ultérieur: " + message);
                pendingMessages.add(message);
                return true;
            }
            return false;
        }

        LOGGER.fine("Envoi au serveur: " + message);
        out.println(message);

        // Compter les octets envoyés (+ 2 pour le \r\n)
        bandwidthMonitor.addBytesSent(message.getBytes().length + 2);

        return !out.checkError();
    }
    /**
     * Envoie les messages en attente après une reconnexion
     */
    private void sendPendingMessages() {
        if (pendingMessages.isEmpty()) return;

        LOGGER.info("Envoi des " + pendingMessages.size() + " messages en attente");

        List<String> messagesToSend = new ArrayList<>(pendingMessages);
        pendingMessages.clear();

        for (String message : messagesToSend) {
            sendMessage(message);
        }
    }
    /**
     * Démarre le timer de heartbeat
     */
    private void startHeartbeatTimer() {
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
        }

        lastServerResponseTime = System.currentTimeMillis();

        heartbeatTimer = new Timer("HeartbeatTimer", true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // Vérifier si on a reçu une réponse récemment
                if (System.currentTimeMillis() - lastServerResponseTime > SERVER_TIMEOUT_MS) {
                    LOGGER.warning("Le serveur ne répond pas depuis " +
                            (System.currentTimeMillis() - lastServerResponseTime) / 1000 + " secondes");

                    // Considérer comme déconnecté et tenter une reconnexion
                    handleDisconnection();
                    return;
                }

                // Envoyer un heartbeat
                if (connected.get()) {
                    sendMessage("HEARTBEAT");
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
    }
    /**
     * Tente d'authentifier l'utilisateur auprès du serveur
     *
     * @param username Le nom d'utilisateur
     * @param password Le mot de passe
     * @return true si la demande d'authentification a été envoyée, false sinon
     */
    public boolean login(String username, String password) {
        return sendMessage("LOGIN|" + username + "|" + password);
    }

    /**
     * Déconnecte l'utilisateur
     */
    public boolean logout() {
        boolean result = sendMessage("LOGOUT");
        disconnect();
        return result;
    }

    /**
     * Demande la liste des réunions
     */
    public boolean requestMeetings() {
        return sendMessage("GET_MEETINGS");
    }

    /**
     * Demande la liste des utilisateurs (admin uniquement)
     */
    public boolean requestUsers() {
        return sendMessage("GET_USERS");
    }

    /**
     * Crée une nouvelle réunion
     */
    public boolean createMeeting(Meeting meeting) {
        if (currentUser.get() == null) return false;

        String command = String.format("NEWMEETING|%s|%s|%s|%d|%s",
                meeting.getTitle(),
                meeting.getAgenda() != null ? meeting.getAgenda() : "",
                meeting.getDatetime().format(DATE_TIME_FORMATTER),
                meeting.getDuration(),
                meeting.getType());

        return sendMessage(command);
    }

    /**
     * Rejoint une réunion
     */
    public boolean joinMeeting(int meetingId) {
        return sendMessage("JOIN|" + meetingId);
    }

    /**
     * Quitte la réunion actuelle
     */
    public boolean leaveMeeting() {
        if (currentMeeting.get() == null) return false;
        return sendMessage("LEAVE|" + currentMeeting.get().getId());
    }

    /**
     * Envoie un message dans la réunion actuelle
     */
    public boolean sendChatMessage(String content) {
        if (currentMeeting.get() == null) return false;
        return sendMessage("CHAT_MSG|" + currentMeeting.get().getId() + "|" + content);
    }

    /**
     * Demande la parole dans une réunion
     */
    public boolean requestToSpeak() {
        if (currentMeeting.get() == null) return false;
        return sendMessage("REQUEST_SPEAK|" + currentMeeting.get().getId());
    }

    /**
     * Accorde la parole à un utilisateur (organisateur uniquement)
     */
    public boolean allowUserToSpeak(int userId) {
        if (currentMeeting.get() == null) return false;
        if (currentUser.get().getId() != currentMeeting.get().getOrganizerId()) return false;

        return sendMessage("ALLOW_SPEAK|" + userId);
    }

    /**
     * Refuse la parole à un utilisateur (organisateur uniquement)
     */
    public boolean denyUserToSpeak(int userId) {
        if (currentMeeting.get() == null) return false;
        if (currentUser.get().getId() != currentMeeting.get().getOrganizerId()) return false;

        return sendMessage("DENY_SPEAK|" + userId);
    }

    /**
     * Crée un sondage dans la réunion actuelle (organisateur uniquement)
     */
    public boolean createPoll(String question, List<String> options) {
        if (currentMeeting.get() == null) return false;
        if (currentUser.get().getId() != currentMeeting.get().getOrganizerId()) return false;

        StringBuilder command = new StringBuilder("CREATE_POLL|")
                .append(currentMeeting.get().getId())
                .append("|")
                .append(question);

        for (String option : options) {
            command.append("|").append(option);
        }

        return sendMessage(command.toString());
    }

    /**
     * Vote dans un sondage
     */
    public boolean vote(int pollId, int optionId) {
        if (currentMeeting.get() == null) return false;
        return sendMessage("VOTE|" + pollId + "|" + optionId);
    }

    /**
     * Clôture une réunion (organisateur uniquement)
     */
    public boolean closeMeeting() {
        if (currentMeeting.get() == null) return false;
        if (currentUser.get().getId() != currentMeeting.get().getOrganizerId()) return false;

        return sendMessage("CLOSE_MEETING|" + currentMeeting.get().getId());
    }

    /**
     * Crée un nouvel utilisateur (admin uniquement)
     */
    public boolean createUser(User user) {
        if (currentUser.get() == null || !currentUser.get().isAdmin()) return false;

        String command = String.format("ADD_USER|%s|%s|%s|%s",
                user.getLogin(),
                user.getPassword(),
                user.getName(),
                user.isAdmin() ? "ADMIN" : "USER");

        return sendMessage(command);
    }

    /**
     * Supprime un utilisateur (admin uniquement)
     * @param userId L'ID de l'utilisateur à supprimer
     * @return true si la requête a été envoyée avec succès, false sinon
     */
    public boolean deleteUser(int userId) {
        if (currentUser.get() == null || !currentUser.get().isAdmin()) return false;

        return sendMessage("DELETE_USER|" + userId);
    }

    // Gestionnaires d'événements pour les messages reçus du serveur

    private void handleAuthOk(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 4) {
            int id = Integer.parseInt(parts[0]);
            String login = parts[1];
            String name = parts[2];
            String role = parts[3];

            User user = new User(id, login, "", name, "ADMIN".equals(role));
            currentUser.set(user);

            // Le serveur peut envoyer la liste des réunions juste après
            if (parts.length > 4) {
                handleMeetingsList(extractPayloadAfterIndex(payload, 4));
            }
        }
        // Envoyer les messages en attente après une reconnexion réussie
        sendPendingMessages();
    }
    /**
     * Tente de rétablir la connexion en cas de déconnexion
     */
    private void attemptReconnection() {
        if (lastConnectedHost == null || reconnectionAttempts >= MAX_RECONNECTION_ATTEMPTS) {
            LOGGER.warning("Impossible de se reconnecter après " + reconnectionAttempts + " tentatives");
            return;
        }

        reconnectionAttempts++;
        LOGGER.info("Tentative de reconnexion " + reconnectionAttempts + "/" + MAX_RECONNECTION_ATTEMPTS);

        executorService.submit(() -> {
            try {
                Thread.sleep(RECONNECTION_DELAY_MS);
                boolean reconnected = connect(lastConnectedHost, lastConnectedPort);

                if (reconnected && currentUser.get() != null) {
                    // Si l'utilisateur était connecté, tenter de récupérer la session
                    LOGGER.info("Reconnecté au serveur, tentative de récupération de session");
                    // Option 1: demander au serveur de récupérer la session avec un token
                    // sendMessage("RECOVER_SESSION|" + sessionToken);

                    // Option 2: se reconnecter simplement
                    login(currentUser.get().getLogin(), currentUser.get().getPassword());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void handleAuthFailed(String payload) {
        // Juste propager l'événement, la vue s'occupera d'afficher le message
    }

    private void handleMeetingsList(String payload) {
        meetings.clear();

        String[] meetingData = payload.split("\\|");

        // Parcourir par groupe de 7 attributs (id, title, agenda, datetime, duration, type, status)
        for (int i = 0; i + 6 < meetingData.length; i += 7) {
            int id = Integer.parseInt(meetingData[i]);
            String title = meetingData[i + 1];
            String agenda = meetingData[i + 2];
            LocalDateTime dateTime = LocalDateTime.parse(meetingData[i + 3], DATE_TIME_FORMATTER);
            int duration = Integer.parseInt(meetingData[i + 4]);
            String type = meetingData[i + 5];
            String status = meetingData[i + 6];

            // L'ID de l'organisateur est déjà connu du serveur
            int organizerId = 0;
            if (i + 7 < meetingData.length) {
                organizerId = Integer.parseInt(meetingData[i + 7]);
            }

            Meeting meeting = new Meeting(id, title, agenda, dateTime, duration, type, status, organizerId);
            meetings.add(meeting);
        }
    }

    private void handleMeetingCreated(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 7) {
            int id = Integer.parseInt(parts[0]);
            String title = parts[1];
            String agenda = parts[2];
            LocalDateTime dateTime = LocalDateTime.parse(parts[3], DATE_TIME_FORMATTER);
            int duration = Integer.parseInt(parts[4]);
            String type = parts[5];
            String status = parts[6];
            int organizerId = Integer.parseInt(parts[7]);

            Meeting meeting = new Meeting(id, title, agenda, dateTime, duration, type, status, organizerId);
            meetings.add(meeting);
        }
    }

    private void handleMeetingUpdated(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 7) {
            int id = Integer.parseInt(parts[0]);
            String title = parts[1];
            String agenda = parts[2];
            LocalDateTime dateTime = LocalDateTime.parse(parts[3], DATE_TIME_FORMATTER);
            int duration = Integer.parseInt(parts[4]);
            String type = parts[5];
            String status = parts[6];
            int organizerId = Integer.parseInt(parts[7]);

            // Chercher et mettre à jour la réunion existante
            for (int i = 0; i < meetings.size(); i++) {
                if (meetings.get(i).getId() == id) {
                    Meeting updatedMeeting = new Meeting(id, title, agenda, dateTime, duration, type, status, organizerId);
                    meetings.set(i, updatedMeeting);

                    // Si c'est la réunion actuelle, la mettre à jour aussi
                    if (currentMeeting.get() != null && currentMeeting.get().getId() == id) {
                        currentMeeting.set(updatedMeeting);
                    }

                    break;
                }
            }
        }
    }

    private void handleMeetingDeleted(String payload) {
        try {
            int meetingId = Integer.parseInt(payload);
            meetings.removeIf(m -> m.getId() == meetingId);

            // Si c'est la réunion actuelle, la quitter
            if (currentMeeting.get() != null && currentMeeting.get().getId() == meetingId) {
                currentMeeting.set(null);
                currentMeetingMessages.clear();
                currentMeetingParticipants.clear();
                currentMeetingPolls.clear();
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("Format de l'ID de réunion incorrect: " + payload);
        }
    }

    private void handleJoinOk(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 1) {
            int meetingId = Integer.parseInt(parts[0]);

            // Trouver la réunion dans la liste
            for (Meeting meeting : meetings) {
                if (meeting.getId() == meetingId) {
                    currentMeeting.set(meeting);
                    break;
                }
            }

            // Traiter la liste des participants si fournie
            if (parts.length > 1) {
                String participantsData = extractPayloadAfterIndex(payload, 1);
                handleUsersList(participantsData);
            }

            // Traiter l'historique des messages si fourni
            if (parts.length > 1) {
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("CHAT_HISTORY:")) {
                        String chatHistory = extractPayloadAfterPrefix(parts[i], "CHAT_HISTORY:");
                        handleChatHistory(chatHistory);
                        break;
                    }
                }
            }
        }
    }

    private void handleJoinFailed(String payload) {
        // Géré par la vue
    }

    private void handleMeetingClosed(String payload) {
        try {
            int meetingId = Integer.parseInt(payload);

            // Mettre à jour le statut de la réunion
            for (int i = 0; i < meetings.size(); i++) {
                if (meetings.get(i).getId() == meetingId) {
                    Meeting meeting = meetings.get(i);
                    meeting.setStatus("Terminée");
                    meetings.set(i, meeting);
                    break;
                }
            }

            // Si c'est la réunion actuelle, la quitter
            if (currentMeeting.get() != null && currentMeeting.get().getId() == meetingId) {
                currentMeeting.set(null);
                currentMeetingMessages.clear();
                currentMeetingParticipants.clear();
                currentMeetingPolls.clear();
            }
        } catch (NumberFormatException e) {
            LOGGER.warning("Format de l'ID de réunion incorrect: " + payload);
        }
    }

    private void handleUserJoined(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 3) {
            int userId = Integer.parseInt(parts[0]);
            String login = parts[1];
            String name = parts[2];
            boolean isAdmin = parts.length > 3 && "ADMIN".equals(parts[3]);

            User user = new User(userId, login, "", name, isAdmin);

            // Ajouter à la liste des participants de la réunion actuelle
            boolean alreadyInList = false;
            for (User participant : currentMeetingParticipants) {
                if (participant.getId() == userId) {
                    alreadyInList = true;
                    break;
                }
            }

            if (!alreadyInList) {
                currentMeetingParticipants.add(user);
            }
        }
    }

    private void handleUserLeft(String payload) {
        try {
            int userId = Integer.parseInt(payload);
            currentMeetingParticipants.removeIf(u -> u.getId() == userId);
        } catch (NumberFormatException e) {
            LOGGER.warning("Format de l'ID utilisateur incorrect: " + payload);
        }
    }

    private void handleUsersList(String payload) {
        String[] userParts = payload.split("\\|");

        // Déterminer si c'est pour la liste générale ou la liste des participants
        boolean isForCurrentMeeting = currentMeeting.get() != null;

        ObservableList<User> targetList = isForCurrentMeeting ?
                currentMeetingParticipants : users;

        targetList.clear();

        // Parcourir par groupe de 3 ou 4 attributs (id, login, name, [role])
        for (int i = 0; i + 2 < userParts.length; i += 3) {
            int id = Integer.parseInt(userParts[i]);
            String login = userParts[i + 1];
            String name = userParts[i + 2];
            boolean isAdmin = (i + 3 < userParts.length) && "ADMIN".equals(userParts[i + 3]);

            User user = new User(id, login, "", name, isAdmin);
            targetList.add(user);
        }
    }

    private void handleChatMessage(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 3) {
            int userId = Integer.parseInt(parts[0]);
            String timestamp = parts[1];
            String content = parts[2];

            // Trouver l'utilisateur correspondant
            String userName = "Inconnu";
            for (User user : currentMeetingParticipants) {
                if (user.getId() == userId) {
                    userName = user.getName();
                    break;
                }
            }

            LocalDateTime messageTime = LocalDateTime.parse(timestamp, DATE_TIME_FORMATTER);
            Message message = new Message(0, currentMeeting.get().getId(), userId, userName, messageTime, content);

            currentMeetingMessages.add(message);
        }
    }

    private void handleChatHistory(String payload) {
        String[] messages = payload.split(";;");

        for (String msgData : messages) {
            String[] parts = msgData.split("\\|");
            if (parts.length >= 4) {
                int messageId = Integer.parseInt(parts[0]);
                int userId = Integer.parseInt(parts[1]);
                String timestamp = parts[2];
                String content = parts[3];

                // Trouver l'utilisateur correspondant
                String userName = "Inconnu";
                for (User user : currentMeetingParticipants) {
                    if (user.getId() == userId) {
                        userName = user.getName();
                        break;
                    }
                }

                LocalDateTime messageTime = LocalDateTime.parse(timestamp, DATE_TIME_FORMATTER);
                Message message = new Message(messageId, currentMeeting.get().getId(), userId, userName, messageTime, content);

                currentMeetingMessages.add(message);
            }
        }
    }

    private void handleSpeakRequest(String payload) {
        // Géré par la vue (affichage de notification pour l'organisateur)
        // Format: userId|userName
    }

    private void handleSpeakGranted(String payload) {
        // Géré par la vue (activation du microphone)
    }

    private void handleSpeakDenied(String payload) {
        // Géré par la vue (notification)
    }

    private void handleNewPoll(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 3) {
            int pollId = Integer.parseInt(parts[0]);
            int meetingId = Integer.parseInt(parts[1]);
            String question = parts[2];

            List<PollOption> options = new ArrayList<>();
            for (int i = 3; i < parts.length; i += 2) {
                int optionId = Integer.parseInt(parts[i]);
                String optionText = parts[i + 1];
                options.add(new PollOption(optionId, pollId, optionText));
            }

            Poll poll = new Poll(pollId, meetingId, question, LocalDateTime.now(), options);
            currentMeetingPolls.add(poll);
        }
    }

    private void handlePollResults(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length >= 2) {
            int pollId = Integer.parseInt(parts[0]);

            // Chercher le sondage correspondant
            for (Poll poll : currentMeetingPolls) {
                if (poll.getId() == pollId) {
                    // Mettre à jour les résultats
                    for (int i = 1; i < parts.length; i += 2) {
                        int optionId = Integer.parseInt(parts[i]);
                        int votes = Integer.parseInt(parts[i + 1]);

                        // Trouver et mettre à jour l'option
                        for (PollOption option : poll.getOptions()) {
                            if (option.getId() == optionId) {
                                option.setVotes(votes);
                                break;
                            }
                        }
                    }

                    // Notifier la vue pour rafraîchir l'affichage
                    int index = currentMeetingPolls.indexOf(poll);
                    currentMeetingPolls.set(index, poll);
                    break;
                }
            }
        }
    }

    private void handleError(String payload) {
        // L'erreur est affichée par la vue
        LOGGER.warning("Erreur reçue du serveur: " + payload);
    }

    // Utilitaires pour extraire les commandes et charges utiles

    private String extractCommand(String message) {
        int separatorIndex = message.indexOf('|');
        return separatorIndex == -1 ? message : message.substring(0, separatorIndex);
    }

    private String extractPayload(String message) {
        int separatorIndex = message.indexOf('|');
        return separatorIndex == -1 ? "" : message.substring(separatorIndex + 1);
    }

    private String extractPayloadAfterIndex(String payload, int startIndex) {
        String[] parts = payload.split("\\|");
        if (parts.length <= startIndex) return "";

        StringBuilder result = new StringBuilder();
        for (int i = startIndex; i < parts.length; i++) {
            if (i > startIndex) result.append("|");
            result.append(parts[i]);
        }

        return result.toString();
    }

    private String extractPayloadAfterPrefix(String payload, String prefix) {
        if (!payload.startsWith(prefix)) return payload;
        return payload.substring(prefix.length());
    }

    // Gestion des événements

    public void registerEventHandler(String event, Consumer<String> handler) {
        eventHandlers.computeIfAbsent(event, k -> new ArrayList<>()).add(handler);
    }

    public void unregisterEventHandler(String event, Consumer<String> handler) {
        if (eventHandlers.containsKey(event)) {
            eventHandlers.get(event).remove(handler);
        }
    }

    private void processEvent(String event, String payload) {
        executorService.submit(() -> {
            if (eventHandlers.containsKey(event)) {
                for (Consumer<String> handler : eventHandlers.get(event)) {
                    try {
                        handler.accept(payload);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Erreur dans le gestionnaire d'événement pour " + event, e);
                    }
                }
            }
        });
    }

    // Accesseurs pour les propriétés

    public ObjectProperty<User> currentUserProperty() {
        return currentUser;
    }

    public User getCurrentUser() {
        return currentUser.get();
    }

    public ObservableList<Meeting> getMeetings() {
        return meetings;
    }

    public ObservableList<User> getUsers() {
        return users;
    }

    public ObjectProperty<Meeting> currentMeetingProperty() {
        return currentMeeting;
    }

    public Meeting getCurrentMeeting() {
        return currentMeeting.get();
    }

    public ObservableList<Message> getCurrentMeetingMessages() {
        return currentMeetingMessages;
    }

    public ObservableList<User> getCurrentMeetingParticipants() {
        return currentMeetingParticipants;
    }

    public ObservableList<Poll> getCurrentMeetingPolls() {
        return currentMeetingPolls;
    }

    /**
     * Classe représentant un utilisateur
     */
    public static class User {
        private final int id;
        private final String login;
        private final String password;
        private final String name;
        private final boolean admin;

        public User(int id, String login, String password, String name, boolean admin) {
            this.id = id;
            this.login = login;
            this.password = password;
            this.name = name;
            this.admin = admin;
        }

        public int getId() {
            return id;
        }

        public String getLogin() {
            return login;
        }

        public String getPassword() {
            return password;
        }

        public String getName() {
            return name;
        }

        public boolean isAdmin() {
            return admin;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Classe représentant une réunion
     */
    public static class Meeting {
        private final int id;
        private final String title;
        private final String agenda;
        private final LocalDateTime datetime;
        private final int duration;
        private final String type;
        private String status;
        private final int organizerId;
        private String organizerName;

        public Meeting(int id, String title, String agenda, LocalDateTime datetime, int duration,
                       String type, String status, int organizerId) {
            this.id = id;
            this.title = title;
            this.agenda = agenda;
            this.datetime = datetime;
            this.duration = duration;
            this.type = type;
            this.status = status;
            this.organizerId = organizerId;
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getAgenda() {
            return agenda;
        }

        public LocalDateTime getDatetime() {
            return datetime;
        }

        public int getDuration() {
            return duration;
        }

        public String getType() {
            return type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getOrganizerId() {
            return organizerId;
        }

        public String getOrganizerName() {
            return organizerName;
        }

        public void setOrganizerName(String organizerName) {
            this.organizerName = organizerName;
        }

        @Override
        public String toString() {
            return title;
        }
    }
// Ajout de ces méthodes pour gérer les paramètres du serveur
    /**
     * Récupère l'adresse du serveur depuis les préférences utilisateur
     * @return L'adresse du serveur, ou localhost par défaut
     */
    public String retrieveServerHost() {
        try {
            // Tenter de lire depuis les préférences
            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            return prefs.get("server.host", "localhost");
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de la lecture de l'adresse du serveur: " + e.getMessage());
            return "localhost";
        }
    }

    /**
     * Définit l'adresse du serveur dans les préférences utilisateur
     * @param host L'adresse du serveur à enregistrer
     */
    public void saveServerHost(String host) {
        try {
            if (host == null || host.trim().isEmpty()) {
                host = "localhost";
            }

            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            prefs.put("server.host", host);
            prefs.flush();
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de l'enregistrement de l'adresse du serveur: " + e.getMessage());
        }
    }

    /**
     * Récupère le port du serveur depuis les préférences utilisateur
     * @return Le port du serveur, ou 12345 par défaut
     */
    public int retrieveServerPort() {
        try {
            // Tenter de lire depuis les préférences
            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            return prefs.getInt("server.port", 12345);
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de la lecture du port du serveur: " + e.getMessage());
            return 12345;
        }
    }


    /**
     * Définit le port du serveur dans les préférences utilisateur
     * @param port Le port du serveur à enregistrer
     */
    public void saveServerPort(int port) {
        try {
            if (port <= 0 || port > 65535) {
                port = 12345; // Port par défaut si invalide
            }

            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            prefs.putInt("server.port", port);
            prefs.flush();
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de l'enregistrement du port du serveur: " + e.getMessage());
        }
    }

    /**
     * Récupère l'état du chiffrement TLS depuis les préférences utilisateur
     * @return true si le TLS est activé, false sinon
     */
    public boolean retrieveTlsEnabled() {
        try {
            // Tenter de lire depuis les préférences
            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            return prefs.getBoolean("server.tls", false);
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de la lecture du paramètre TLS: " + e.getMessage());
            return false;
        }
    }

    /**
     * Définit l'état du chiffrement TLS dans les préférences utilisateur
     * @param enabled true pour activer TLS, false sinon
     */
    public void saveTlsEnabled(boolean enabled) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(Models.class);
            prefs.putBoolean("server.tls", enabled);
            prefs.flush();
        } catch (Exception e) {
            LOGGER.warning("Erreur lors de l'enregistrement du paramètre TLS: " + e.getMessage());
        }
    }
    /**
     * Classe représentant un message dans le chat
     */
    public static class Message {
        private final int id;
        private final int meetingId;
        private final int userId;
        private final String userName;
        private final LocalDateTime timestamp;
        private final String content;

        public Message(int id, int meetingId, int userId, String userName, LocalDateTime timestamp, String content) {
            this.id = id;
            this.meetingId = meetingId;
            this.userId = userId;
            this.userName = userName;
            this.timestamp = timestamp;
            this.content = content;
        }

        public int getId() {
            return id;
        }

        public int getMeetingId() {
            return meetingId;
        }

        public int getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * Classe représentant un sondage
     */
    public static class Poll {
        private final int id;
        private final int meetingId;
        private final String question;
        private final LocalDateTime createdAt;
        private final List<PollOption> options;

        public Poll(int id, int meetingId, String question, LocalDateTime createdAt, List<PollOption> options) {
            this.id = id;
            this.meetingId = meetingId;
            this.question = question;
            this.createdAt = createdAt;
            this.options = options;
        }

        public int getId() {
            return id;
        }

        public int getMeetingId() {
            return meetingId;
        }

        public String getQuestion() {
            return question;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public List<PollOption> getOptions() {
            return options;
        }
    }

    /**
     * Classe représentant une option dans un sondage
     */
    public static class PollOption {
        private final int id;
        private final int pollId;
        private final String text;
        private int votes;

        public PollOption(int id, int pollId, String text) {
            this.id = id;
            this.pollId = pollId;
            this.text = text;
            this.votes = 0;
        }

        public int getId() {
            return id;
        }

        public int getPollId() {
            return pollId;
        }

        public String getText() {
            return text;
        }

        public int getVotes() {
            return votes;
        }

        public void setVotes(int votes) {
            this.votes = votes;
        }
    }

    /**
     * Classe représentant une réaction
     */
    public static class Reaction {
        private final int meetingId;
        private final int userId;
        private final String type;
        private final LocalDateTime timestamp;

        public Reaction(int meetingId, int userId, String type, LocalDateTime timestamp) {
            this.meetingId = meetingId;
            this.userId = userId;
            this.type = type;
            this.timestamp = timestamp;
        }

        public int getMeetingId() {
            return meetingId;
        }

        public int getUserId() {
            return userId;
        }

        public String getType() {
            return type;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }
    }
    /**
     * Vérifie si le client est actuellement connecté au serveur
     * @return true si la connexion est établie, false sinon
     */
    public boolean isConnected() {
        return connected.get();
    }
}
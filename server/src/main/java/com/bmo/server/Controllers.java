package com.bmo.server;

import com.bmo.common.ProtocolConstants;
import com.bmo.server.Models.*;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gère les contrôleurs et la logique métier du serveur BMO.
 * Contient les classes qui coordonnent la communication client-serveur,
 * l'accès aux données et l'exécution des commandes.
 */
public class Controllers {
    private static final Logger LOGGER = Logger.getLogger(Controllers.class.getName());
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Gestionnaire des sessions client et communication réseau
     */
    public static class ClientHandler implements Runnable {
        private Socket clientSocket;
        private BufferedReader in;
        private PrintWriter out;
        private Utilisateur utilisateurConnecte;
        private boolean isRunning = true;
        private final DataSource dataSource;
        private Reunion reunionCourante = null;
        private final Map<String, CommandHandler> commandHandlers = new HashMap<>();

        public ClientHandler(Socket socket, DataSource dataSource) {
            this.clientSocket = socket;
            this.dataSource = dataSource;
            initializeCommandHandlers();
            try {
                this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                this.out = new PrintWriter(socket.getOutputStream(), true);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors de l'initialisation du handler client", e);
            }
        }

        /**
         * Initialise les gestionnaires de commandes avec leurs implémentations
         */
        private void initializeCommandHandlers() {
            commandHandlers.put(ProtocolConstants.CMD_LOGIN, this::handleLogin);
            commandHandlers.put(ProtocolConstants.CMD_LOGOUT, this::handleLogout);
            commandHandlers.put(ProtocolConstants.CMD_REGISTER, this::handleRegister);
            commandHandlers.put(ProtocolConstants.CMD_GET_MEETINGS, this::handleGetMeetings);
            commandHandlers.put(ProtocolConstants.CMD_NEW_MEETING, this::handleNewMeeting);
            commandHandlers.put(ProtocolConstants.CMD_JOIN_MEETING, this::handleJoinMeeting);
            commandHandlers.put(ProtocolConstants.CMD_LEAVE_MEETING, this::handleLeaveMeeting);
            commandHandlers.put(ProtocolConstants.CMD_CHAT_MESSAGE, this::handleChatMessage);
            commandHandlers.put(ProtocolConstants.CMD_REQUEST_SPEAK, this::handleRequestSpeak);
            commandHandlers.put(ProtocolConstants.CMD_ALLOW_SPEAK, this::handleAllowSpeak);
            commandHandlers.put(ProtocolConstants.CMD_CLOSE_MEETING, this::handleCloseMeeting);
            commandHandlers.put(ProtocolConstants.CMD_GET_USERS, this::handleGetUsers);
            commandHandlers.put(ProtocolConstants.CMD_ADD_USER, this::handleAddUser);
            commandHandlers.put(ProtocolConstants.CMD_DELETE_USER, this::handleDeleteUser);
            commandHandlers.put(ProtocolConstants.CMD_UPDATE_USER, this::handleUpdateUser);
            commandHandlers.put(ProtocolConstants.CMD_GET_SETTINGS, this::handleGetSettings);
            commandHandlers.put(ProtocolConstants.CMD_UPDATE_SETTING, this::handleUpdateSetting);
            commandHandlers.put(ProtocolConstants.CMD_CREATE_POLL, this::handleCreatePoll);
            commandHandlers.put(ProtocolConstants.CMD_VOTE, this::handleVote);
            commandHandlers.put(ProtocolConstants.CMD_GET_POLL_RESULTS, this::handleGetPollResults);
            commandHandlers.put(ProtocolConstants.CMD_SEND_REACTION, this::handleSendReaction);
            commandHandlers.put(ProtocolConstants.CMD_RATE_MEETING, this::handleRateMeeting);
            commandHandlers.put(ProtocolConstants.CMD_START_RECORDING, this::handleStartRecording);
            commandHandlers.put(ProtocolConstants.CMD_STOP_RECORDING, this::handleStopRecording);
            commandHandlers.put(ProtocolConstants.CMD_UPDATE_BANDWIDTH, this::handleUpdateBandwidth);
        }

        @Override
        public void run() {
            try {
                String inputLine;
                while (isRunning && (inputLine = in.readLine()) != null) {
                    processCommand(inputLine);
                }
            } catch (IOException e) {
                handleDisconnection();
            } finally {
                cleanup();
            }
        }

        /**
         * Traite une commande reçue du client
         * @param inputLine La ligne de commande à traiter
         */
        private void processCommand(String inputLine) {
            String[] parts = inputLine.split("\\|");
            if (parts.length == 0) return;

            String command = parts[0];
            String[] params = Arrays.copyOfRange(parts, 1, parts.length);

            CommandHandler handler = commandHandlers.get(command);
            if (handler != null) {
                try {
                    handler.handle(params);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Erreur lors de l'exécution de la commande " + command, e);
                    send(ProtocolConstants.RESP_ERROR + "|" + e.getMessage());
                }
            } else {
                LOGGER.warning("Commande inconnue reçue: " + command);
                send(ProtocolConstants.RESP_ERROR + "|Commande inconnue");
            }
        }

        /**
         * Envoie une réponse au client
         * @param message Message à envoyer
         */
        public void send(String message) {
            if (out != null) {
                out.println(message);
            }
        }

        /**
         * Gestion de la déconnexion du client
         */
        private void handleDisconnection() {
            LOGGER.info("Client déconnecté: " + (utilisateurConnecte != null ? utilisateurConnecte.getLogin() : "non authentifié"));
            if (utilisateurConnecte != null && reunionCourante != null) {
                // Si l'utilisateur était dans une réunion, le faire quitter proprement
                try {
                    ParticipantSession ps = getParticipantSession(reunionCourante.getId(), utilisateurConnecte.getId());
                    if (ps != null) {
                        ps.quitter();
                        updateParticipantStatus(ps);
                        notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_USER_LEFT,
                                utilisateurConnecte.getId() + "|" + utilisateurConnecte.getNom());
                    }
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "Erreur lors de la déconnexion de l'utilisateur de la réunion", e);
                }
            }
            SessionManager.removeSession(this);
        }

        /**
         * Nettoie les ressources lors de la fermeture de la connexion
         */
        private void cleanup() {
            isRunning = false;
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors de la fermeture des ressources", e);
            }
        }

        /**
         * @return L'utilisateur actuellement connecté
         */
        public Utilisateur getUtilisateurConnecte() {
            return utilisateurConnecte;
        }

        /**
         * @return La réunion à laquelle l'utilisateur participe actuellement
         */
        public Reunion getReunionCourante() {
            return reunionCourante;
        }

        /**
         * Vérifie si l'utilisateur est authentifié
         * @return true si l'utilisateur est connecté
         */
        private boolean isAuthenticated() {
            return utilisateurConnecte != null;
        }

        /**
         * Vérifie si l'utilisateur a des droits d'administrateur
         * @return true si l'utilisateur est un administrateur
         */
        private boolean isAdmin() {
            return isAuthenticated() && utilisateurConnecte.getRole() == RoleType.ADMIN;
        }

        // ====== Handlers de commandes ======

        private void handleLogin(String[] params) throws SQLException {
            if (params.length < 2) {
                send(ProtocolConstants.RESP_AUTH_FAIL + "|Format invalide");
                return;
            }

            String login = params[0];
            String password = params[1];

            Utilisateur user = Models.authenticateUser(login, password);
            if (user != null) {
                this.utilisateurConnecte = user;
                SessionManager.addSession(this);
                send(ProtocolConstants.RESP_AUTH_OK + "|" + user.getRole() + "|" + user.getId() + "|" + user.getNom());

                // Envoi immédiat de la liste des réunions disponibles
                handleGetMeetings(new String[0]);
            } else {
                send(ProtocolConstants.RESP_AUTH_FAIL + "|Identifiants incorrects");
            }
        }

        private void handleLogout(String[] params) {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            // Si l'utilisateur est dans une réunion, le faire quitter
            if (reunionCourante != null) {
                String[] meetingParams = {String.valueOf(reunionCourante.getId())};
                try {
                    handleLeaveMeeting(meetingParams);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Erreur lors de la sortie de réunion au logout", e);
                }
            }

            SessionManager.removeSession(this);
            this.utilisateurConnecte = null;
            send(ProtocolConstants.RESP_OK + "|Déconnecté avec succès");
        }

        private void handleRegister(String[] params) throws SQLException {
            if (params.length < 3) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String login = params[0];
            String password = params[1];
            String name = params[2];

            // Vérifier si le login existe déjà
            if (isLoginExists(login)) {
                send(ProtocolConstants.RESP_ERROR + "|Ce nom d'utilisateur existe déjà");
                return;
            }

            // Créer le nouvel utilisateur
            Utilisateur newUser = new Utilisateur();
            newUser.setLogin(login);
            newUser.setMotDePasseHash(password); // Dans une implémentation réelle, le mot de passe devrait être haché
            newUser.setNom(name);
            newUser.setRole(RoleType.USER);

            if (Models.addUser(newUser)) {
                send(ProtocolConstants.RESP_OK + "|Inscription réussie");
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de l'inscription");
            }
        }

        private void handleGetMeetings(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            List<Reunion> meetings = getAllAvailableMeetings();
            StringBuilder response = new StringBuilder(ProtocolConstants.RESP_MEETINGS + "|");

            for (Reunion reunion : meetings) {
                response.append(reunion.getId())
                        .append(",").append(reunion.getTitre())
                        .append(",").append(reunion.getDateHeure().format(DATE_FORMATTER))
                        .append(",").append(reunion.getDuree())
                        .append(",").append(reunion.getType())
                        .append(",").append(reunion.getStatut())
                        .append(",").append(reunion.getOrganisateur().getId())
                        .append(",").append(reunion.getOrganisateur().getNom())
                        .append("|");
            }

            send(response.toString());
        }

        private void handleNewMeeting(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            if (params.length < 4) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String titre = params[0];
            LocalDateTime dateHeure = LocalDateTime.parse(params[1], DATE_FORMATTER);
            int duree = Integer.parseInt(params[2]);
            TypeReunion type = TypeReunion.valueOf(params[3]);

            Reunion nouvelleReunion = new Reunion();
            nouvelleReunion.setTitre(titre);
            nouvelleReunion.setDateHeure(dateHeure);
            nouvelleReunion.setDuree(duree);
            nouvelleReunion.setType(type);
            nouvelleReunion.setStatut(StatutReunion.Planifiee);
            nouvelleReunion.setOrganisateur(utilisateurConnecte);
            nouvelleReunion.setDateCreation(LocalDateTime.now());

            int reunionId = Models.createMeeting(nouvelleReunion);
            if (reunionId > 0) {
                nouvelleReunion.setId(reunionId);

                // Ajouter l'organisateur comme participant
                addParticipant(reunionId, utilisateurConnecte.getId(), StatutInvitation.accepted, "organizer");

                // Si des participants sont spécifiés, les ajouter
                if (params.length > 4) {
                    String[] participants = params[4].split(",");
                    for (String participantId : participants) {
                        addParticipant(reunionId, Integer.parseInt(participantId), StatutInvitation.invited, "participant");
                    }
                }

                send(ProtocolConstants.RESP_OK + "|" + reunionId);

                // Notifier les clients de la nouvelle réunion
                notifyNewMeeting(nouvelleReunion);
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de la création de la réunion");
            }
        }

        private void handleJoinMeeting(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int meetingId = Integer.parseInt(params[0]);
            Reunion reunion = getMeetingById(meetingId);

            if (reunion == null) {
                send(ProtocolConstants.RESP_ERROR + "|Réunion introuvable");
                return;
            }

            if (reunion.getStatut() == StatutReunion.Terminee) {
                send(ProtocolConstants.RESP_ERROR + "|La réunion est terminée");
                return;
            }

            // Vérifier si l'utilisateur est invité ou si la réunion est publique (type Standard)
            ParticipantSession ps = getParticipantSession(meetingId, utilisateurConnecte.getId());

            if (ps == null && reunion.getType() != TypeReunion.Standard) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas invité à cette réunion");
                return;
            }

            if (ps == null) {
                // Pour une réunion publique, ajouter l'utilisateur comme participant
                ps = new ParticipantSession(reunion, utilisateurConnecte, StatutInvitation.accepted, null, null);
                addParticipant(meetingId, utilisateurConnecte.getId(), StatutInvitation.accepted, "participant");
            }

            // Si la réunion était planifiée et que c'est l'organisateur qui rejoint, la passer en "Ouverte"
            if (reunion.getStatut() == StatutReunion.Planifiee &&
                    utilisateurConnecte.getId() == reunion.getOrganisateur().getId()) {
                reunion.ouvrir();
                updateMeetingStatus(reunion);
                notifyMeetingStatusChanged(reunion.getId(), StatutReunion.Ouverte);
            }

            // Mettre à jour le statut du participant
            ps.rejoindre();
            updateParticipantStatus(ps);

            // Mettre à jour les références locales
            reunionCourante = reunion;

            // Envoyer la confirmation avec les détails de la réunion
            send(ProtocolConstants.RESP_JOIN_OK + "|" + reunion.getId() + "|" + reunion.getTitre());

            // Envoyer la liste des participants actuels
            sendParticipantsList(meetingId);

            // Envoyer l'historique récent des messages
            sendRecentMessages(meetingId);

            // Notifier les autres participants de la venue du nouvel arrivant
            notifyParticipants(meetingId, ProtocolConstants.NOTIFY_USER_JOINED,
                    utilisateurConnecte.getId() + "|" + utilisateurConnecte.getNom());
        }

        private void handleLeaveMeeting(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            int meetingId = reunionCourante.getId();
            ParticipantSession ps = getParticipantSession(meetingId, utilisateurConnecte.getId());

            if (ps != null) {
                ps.quitter();
                updateParticipantStatus(ps);

                // Notifier les autres participants du départ
                notifyParticipants(meetingId, ProtocolConstants.NOTIFY_USER_LEFT,
                        utilisateurConnecte.getId() + "|" + utilisateurConnecte.getNom());

                // Vérifier s'il reste des participants, sinon fermer la réunion
                if (isOrganizer(meetingId) && countActiveParticipants(meetingId) <= 1) {
                    handleCloseMeeting(new String[]{String.valueOf(meetingId)});
                }
            }

            reunionCourante = null;
            send(ProtocolConstants.RESP_OK + "|Vous avez quitté la réunion");
        }

        private void handleChatMessage(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Message vide");
                return;
            }

            String messageContent = params[0];

            // Créer et enregistrer le message
            Message message = new Message();
            message.setReunionId(reunionCourante.getId());
            message.setUtilisateurId(utilisateurConnecte.getId());
            message.setContenu(messageContent);
            message.setHorodatage(LocalDateTime.now());

            saveMessage(message);

            // Diffuser le message à tous les participants
            notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_CHAT_MESSAGE,
                    utilisateurConnecte.getId() + "|" +
                            utilisateurConnecte.getNom() + "|" +
                            message.getHorodatage().format(DATE_FORMATTER) + "|" +
                            messageContent);
        }

        private void handleRequestSpeak(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            // Notifier l'organisateur de la demande
            ClientHandler organizerHandler = SessionManager.getClientByUserId(reunionCourante.getOrganisateur().getId());
            if (organizerHandler != null) {
                organizerHandler.send(ProtocolConstants.NOTIFY_SPEAK_REQUEST + "|" +
                        utilisateurConnecte.getId() + "|" +
                        utilisateurConnecte.getNom());
            }

            send(ProtocolConstants.RESP_OK + "|Demande envoyée");
        }

        private void handleAllowSpeak(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (!isOrganizer(reunionCourante.getId())) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas l'organisateur de cette réunion");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int userId = Integer.parseInt(params[0]);
            boolean allow = params.length < 2 || Boolean.parseBoolean(params[1]);

            ClientHandler participantHandler = SessionManager.getClientByUserId(userId);
            if (participantHandler != null) {
                if (allow) {
                    participantHandler.send(ProtocolConstants.NOTIFY_SPEAK_GRANTED);
                    notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_SPEAKER_ACTIVE,
                            userId + "|" + getUserNameById(userId));
                } else {
                    participantHandler.send(ProtocolConstants.NOTIFY_SPEAK_DENIED);
                }
            }

            send(ProtocolConstants.RESP_OK + "|Permission mise à jour");
        }

        private void handleCloseMeeting(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            int meetingId;
            if (params.length < 1) {
                if (reunionCourante == null) {
                    send(ProtocolConstants.RESP_ERROR + "|ID de réunion requis");
                    return;
                }
                meetingId = reunionCourante.getId();
            } else {
                meetingId = Integer.parseInt(params[0]);
            }

            Reunion reunion = getMeetingById(meetingId);

            if (reunion == null) {
                send(ProtocolConstants.RESP_ERROR + "|Réunion introuvable");
                return;
            }

            if (!isAdmin() && reunion.getOrganisateur().getId() != utilisateurConnecte.getId()) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas autorisé à clore cette réunion");
                return;
            }

            reunion.clore();
            updateMeetingStatus(reunion);

            // Notifier tous les participants de la clôture
            notifyParticipants(meetingId, ProtocolConstants.NOTIFY_MEETING_CLOSED, reunion.getTitre());

            // Mettre à jour l'état local si c'était la réunion courante
            if (reunionCourante != null && reunionCourante.getId() == meetingId) {
                reunionCourante = null;
            }

            send(ProtocolConstants.RESP_OK + "|Réunion clôturée");
        }

        private void handleGetUsers(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            if (!isAdmin()) {
                send(ProtocolConstants.RESP_ERROR + "|Accès refusé");
                return;
            }

            List<Utilisateur> users = Models.getAllUsers();
            StringBuilder response = new StringBuilder(ProtocolConstants.RESP_USERS + "|");

            for (Utilisateur user : users) {
                response.append(user.getId())
                        .append(",").append(user.getLogin())
                        .append(",").append(user.getNom())
                        .append(",").append(user.getRole())
                        .append(",").append(user.getDateCreation().format(DATE_FORMATTER))
                        .append("|");
            }

            send(response.toString());
        }

        private void handleAddUser(String[] params) throws SQLException {
            if (!isAuthenticated() || !isAdmin()) {
                send(ProtocolConstants.RESP_ERROR + "|Accès refusé");
                return;
            }

            if (params.length < 3) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String login = params[0];
            String password = params[1];
            String name = params[2];
            RoleType role = params.length > 3 ? RoleType.valueOf(params[3]) : RoleType.USER;

            // Vérifier si le login existe déjà
            if (isLoginExists(login)) {
                send(ProtocolConstants.RESP_ERROR + "|Ce nom d'utilisateur existe déjà");
                return;
            }

            Utilisateur newUser = new Utilisateur();
            newUser.setLogin(login);
            newUser.setMotDePasseHash(password); // Dans une implémentation réelle, le mot de passe devrait être haché
            newUser.setNom(name);
            newUser.setRole(role);

            if (Models.addUser(newUser)) {
                send(ProtocolConstants.RESP_OK + "|" + newUser.getId());
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de la création de l'utilisateur");
            }
        }

        private void handleDeleteUser(String[] params) throws SQLException {
            if (!isAuthenticated() || !isAdmin()) {
                send(ProtocolConstants.RESP_ERROR + "|Accès refusé");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int userId = Integer.parseInt(params[0]);

            if (userId == utilisateurConnecte.getId()) {
                send(ProtocolConstants.RESP_ERROR + "|Vous ne pouvez pas supprimer votre propre compte");
                return;
            }

            if (Models.deleteUser(userId)) {
                send(ProtocolConstants.RESP_OK + "|Utilisateur supprimé");
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Utilisateur introuvable ou erreur de suppression");
            }
        }

        private void handleUpdateUser(String[] params) {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            // TODO: Implémenter la mise à jour utilisateur
            send(ProtocolConstants.RESP_OK + "|Utilisateur mis à jour");
        }

        private void handleGetSettings(String[] params) {
            if (!isAuthenticated() || !isAdmin()) {
                send(ProtocolConstants.RESP_ERROR + "|Accès refusé");
                return;
            }

            // TODO: Implémenter la récupération des paramètres
            send(ProtocolConstants.RESP_SETTINGS + "|key1,value1|key2,value2");
        }

        private void handleUpdateSetting(String[] params) {
            if (!isAuthenticated() || !isAdmin()) {
                send(ProtocolConstants.RESP_ERROR + "|Accès refusé");
                return;
            }

            if (params.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String key = params[0];
            String value = params[1];

            // TODO: Implémenter la mise à jour du paramètre
            send(ProtocolConstants.RESP_OK + "|Paramètre mis à jour");
        }

        private void handleCreatePoll(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (!isOrganizer(reunionCourante.getId()) && reunionCourante.getType() != TypeReunion.Democratique) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas autorisé à créer un sondage");
                return;
            }

            if (params.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String question = params[0];
            String[] options = params[1].split(",");

            if (options.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Un sondage doit avoir au moins deux options");
                return;
            }

            // Créer le sondage et ses options
            Poll poll = createPoll(reunionCourante.getId(), question, options);


            if (poll != null) {
                // Notifier les participants du nouveau sondage
                StringBuilder optionsStr = new StringBuilder();
                List<PollOption> pollOptions = poll.getOptions();
                for (PollOption option : pollOptions) {
                    optionsStr.append(option.getId()).append(",").append(option.getTexte()).append(";");
                }

                notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_NEW_POLL,
                        poll.getId() + "|" + poll.getQuestion() + "|" + optionsStr);

                send(ProtocolConstants.RESP_OK + "|" + poll.getId());
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de la création du sondage");
            }
        }

        private void handleVote(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (params.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int pollId = Integer.parseInt(params[0]);
            int optionId = Integer.parseInt(params[1]);

            // Vérifier que le sondage appartient à la réunion courante
            Poll poll = getPollById(pollId);
            if (poll == null || poll.getReunionId() != reunionCourante.getId()) {
                send(ProtocolConstants.RESP_ERROR + "|Sondage introuvable");
                return;
            }

            // Enregistrer le vote
            if (poll.vote(utilisateurConnecte.getId(), optionId)) {
                send(ProtocolConstants.RESP_OK + "|Vote enregistré");

                // Notifier les participants de la mise à jour des résultats
                Map<PollOption, Integer> results = poll.getResults();
                StringBuilder resultsStr = new StringBuilder();
                for (Map.Entry<PollOption, Integer> entry : results.entrySet()) {
                    resultsStr.append(entry.getKey().getId()).append(",")
                            .append(entry.getValue()).append(";");
                }

                notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_POLL_RESULTS,
                        poll.getId() + "|" + resultsStr);
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de l'enregistrement du vote");
            }
        }

        private void handleGetPollResults(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int pollId = Integer.parseInt(params[0]);

            // Vérifier que le sondage appartient à la réunion courante
            Poll poll = getPollById(pollId);
            if (poll == null || poll.getReunionId() != reunionCourante.getId()) {
                send(ProtocolConstants.RESP_ERROR + "|Sondage introuvable");
                return;
            }

            // Récupérer et envoyer les résultats
            Map<PollOption, Integer> results = poll.getResults();
            StringBuilder response = new StringBuilder(ProtocolConstants.RESP_POLL_RESULTS + "|");
            response.append(poll.getId()).append("|")
                    .append(poll.getQuestion()).append("|");

            for (Map.Entry<PollOption, Integer> entry : results.entrySet()) {
                response.append(entry.getKey().getId()).append(",")
                        .append(entry.getKey().getTexte()).append(",")
                        .append(entry.getValue()).append("|");
            }

            send(response.toString());
        }

        private void handleSendReaction(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (params.length < 1) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            String reactionType = params[0];

            // Créer et enregistrer la réaction
            Reaction reaction = new Reaction(reunionCourante.getId(), utilisateurConnecte.getId(), reactionType, LocalDateTime.now());

            if (reaction.save()) {
                // Notifier tous les participants
                notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_REACTION,
                        utilisateurConnecte.getId() + "|" +
                                utilisateurConnecte.getNom() + "|" +
                                reactionType);

                send(ProtocolConstants.RESP_OK + "|Réaction envoyée");
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de l'envoi de la réaction");
            }
        }

        private void handleRateMeeting(String[] params) throws SQLException {
            if (!isAuthenticated()) {
                send(ProtocolConstants.RESP_ERROR + "|Non authentifié");
                return;
            }

            if (params.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            int meetingId = Integer.parseInt(params[0]);
            int rating = Integer.parseInt(params[1]);
            String comment = params.length > 2 ? params[2] : "";

            // Vérifier que la réunion existe et est terminée
            Reunion reunion = getMeetingById(meetingId);
            if (reunion == null) {
                send(ProtocolConstants.RESP_ERROR + "|Réunion introuvable");
                return;
            }

            // Vérifier que l'utilisateur a participé à la réunion
            ParticipantSession ps = getParticipantSession(meetingId, utilisateurConnecte.getId());
            if (ps == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'avez pas participé à cette réunion");
                return;
            }

            // Enregistrer la notation
            MeetingRating meetingRating = new MeetingRating(meetingId, utilisateurConnecte.getId(), rating, comment, LocalDateTime.now());

            if (meetingRating.save()) {
                send(ProtocolConstants.RESP_OK + "|Évaluation enregistrée");

                // Si l'organisateur est connecté, lui envoyer une notification
                ClientHandler organizerHandler = SessionManager.getClientByUserId(reunion.getOrganisateur().getId());
                if (organizerHandler != null) {
                    organizerHandler.send(ProtocolConstants.NOTIFY_MEETING_RATED + "|" +
                            meetingId + "|" +
                            utilisateurConnecte.getNom() + "|" +
                            rating);
                }
            } else {
                send(ProtocolConstants.RESP_ERROR + "|Échec de l'enregistrement de l'évaluation");
            }
        }

        private void handleStartRecording(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (!isOrganizer(reunionCourante.getId())) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas l'organisateur de cette réunion");
                return;
            }

            // Générer un nom de fichier unique
            String fileName = "recording_" + reunionCourante.getId() + "_" + System.currentTimeMillis() + ".mp4";

            // Créer une session d'enregistrement
            RecordingSession recording = new RecordingSession(reunionCourante.getId(), LocalDateTime.now(), fileName);

            // TODO: Implémenter l'enregistrement réel avec une API vidéo

            // Notifier les participants que l'enregistrement a commencé
            notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_RECORDING_STARTED, "");

            send(ProtocolConstants.RESP_OK + "|Enregistrement démarré");
        }

        private void handleStopRecording(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (!isOrganizer(reunionCourante.getId())) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas l'organisateur de cette réunion");
                return;
            }

            // TODO: Implémenter l'arrêt de l'enregistrement

            // Notifier les participants que l'enregistrement est terminé
            notifyParticipants(reunionCourante.getId(), ProtocolConstants.NOTIFY_RECORDING_STOPPED, "");

            send(ProtocolConstants.RESP_OK + "|Enregistrement arrêté");
        }

        private void handleUpdateBandwidth(String[] params) throws SQLException {
            if (!isAuthenticated() || reunionCourante == null) {
                send(ProtocolConstants.RESP_ERROR + "|Vous n'êtes pas dans une réunion");
                return;
            }

            if (params.length < 2) {
                send(ProtocolConstants.RESP_ERROR + "|Format invalide");
                return;
            }

            long uploadBytes = Long.parseLong(params[0]);
            long downloadBytes = Long.parseLong(params[1]);

            // Enregistrer les stats de bande passante
            BandwidthStat stat = new BandwidthStat(reunionCourante.getId(), utilisateurConnecte.getId(), LocalDateTime.now(), uploadBytes, downloadBytes);
            stat.record(uploadBytes, downloadBytes);

            send(ProtocolConstants.RESP_OK);
        }

        // ====== Méthodes utilitaires ======

        /**
         * Récupère toutes les réunions disponibles pour l'utilisateur
         */
        private List<Reunion> getAllAvailableMeetings() throws SQLException {
            // TODO: Implémenter la récupération des réunions depuis la BD
            // Cette méthode devrait filtrer selon le type de réunion et les invitations
            return new ArrayList<>();
        }

        /**
         * Récupère une réunion par son ID
         */
        private Reunion getMeetingById(int meetingId) throws SQLException {
            // TODO: Implémenter la récupération d'une réunion par ID
            return null;
        }

        /**
         * Met à jour le statut d'une réunion
         */
        private void updateMeetingStatus(Reunion reunion) throws SQLException {
            // TODO: Implémenter la mise à jour du statut d'une réunion
        }

        /**
         * Ajoute un participant à une réunion
         */
        private void addParticipant(int meetingId, int userId, StatutInvitation status, String role) throws SQLException {
            // TODO: Implémenter l'ajout d'un participant
        }

        /**
         * Met à jour le statut d'un participant
         */
        private void updateParticipantStatus(ParticipantSession ps) throws SQLException {
            // TODO: Implémenter la mise à jour du statut d'un participant
        }

        /**
         * Récupère la session d'un participant
         */
        private ParticipantSession getParticipantSession(int meetingId, int userId) throws SQLException {
            // TODO: Implémenter la récupération d'une session participant
            return null;
        }

        /**
         * Vérifie si un utilisateur est l'organisateur d'une réunion
         */
        private boolean isOrganizer(int meetingId) throws SQLException {
            Reunion reunion = getMeetingById(meetingId);
            return reunion != null && reunion.getOrganisateur().getId() == utilisateurConnecte.getId();
        }

        /**
         * Compte le nombre de participants actifs dans une réunion
         */
        private int countActiveParticipants(int meetingId) throws SQLException {
            // TODO: Implémenter le comptage des participants actifs
            return 0;
        }

        /**
         * Sauvegarde un message dans la BD
         */
        private void saveMessage(Message message) throws SQLException {
            // TODO: Implémenter la sauvegarde d'un message
        }

        /**
         * Crée un sondage avec ses options
         */
        private Poll createPoll(int meetingId, String question, String[] options) throws SQLException {
            // TODO: Implémenter la création d'un sondage
            return null;
        }

        /**
         * Récupère un sondage par son ID
         */
        private Poll getPollById(int pollId) throws SQLException {
            // TODO: Implémenter la récupération d'un sondage
            return null;
        }

        /**
         * Vérifie si un login existe déjà
         */
        private boolean isLoginExists(String login) throws SQLException {
            // TODO: Implémenter la vérification d'existence d'un login
            return false;
        }

        /**
         * Récupère le nom d'un utilisateur par son ID
         */
        private String getUserNameById(int userId) throws SQLException {
            // TODO: Implémenter la récupération du nom d'un utilisateur
            return "";
        }

        /**
         * Envoie la liste des participants à l'utilisateur
         */
        private void sendParticipantsList(int meetingId) throws SQLException {
            // TODO: Implémenter l'envoi de la liste des participants
        }

        /**
         * Envoie les messages récents d'une réunion à l'utilisateur
         */
        private void sendRecentMessages(int meetingId) throws SQLException {
            // TODO: Implémenter l'envoi des messages récents
        }

        /**
         * Notifie les participants d'une réunion
         */
        private void notifyParticipants(int meetingId, String notificationType, String message) {
            // Parcourir toutes les sessions client
            for (ClientHandler handler : SessionManager.getAllSessions()) {
                if (handler.isAuthenticated() &&
                        handler.getReunionCourante() != null &&
                        handler.getReunionCourante().getId() == meetingId) {
                    // Envoyer la notification à tous les participants de cette réunion
                    handler.send(notificationType + "|" + message);
                }
            }
        }

        /**
         * Notifie tous les utilisateurs d'une nouvelle réunion
         */
        private void notifyNewMeeting(Reunion reunion) {
            // Notifier tous les utilisateurs connectés de la nouvelle réunion
            String notification = reunion.getId() + "|" +
                    reunion.getTitre() + "|" +
                    reunion.getDateHeure().format(DATE_FORMATTER) + "|" +
                    reunion.getDuree() + "|" +
                    reunion.getType() + "|" +
                    reunion.getStatut() + "|" +
                    reunion.getOrganisateur().getId() + "|" +
                    reunion.getOrganisateur().getNom();

            for (ClientHandler handler : SessionManager.getAllSessions()) {
                if (handler.isAuthenticated()) {
                    handler.send(ProtocolConstants.NOTIFY_NEW_MEETING + "|" + notification);
                }
            }
        }

        /**
         * Notifie tous les participants du changement de statut d'une réunion
         */
        private void notifyMeetingStatusChanged(int meetingId, StatutReunion nouveauStatut) {
            notifyParticipants(meetingId, ProtocolConstants.NOTIFY_MEETING_STATUS,
                    meetingId + "|" + nouveauStatut);
        }
    }

    /**
     * Interface fonctionnelle pour les gestionnaires de commandes
     */
    @FunctionalInterface
    private interface CommandHandler {
        void handle(String[] params) throws Exception;
    }

    /**
     * Gestionnaire des sessions client
     */
    public static class SessionManager {
        private static final Map<Integer, ClientHandler> userSessions = new ConcurrentHashMap<>();
        private static final List<ClientHandler> allSessions = Collections.synchronizedList(new ArrayList<>());

        /**
         * Ajoute une session client
         */
        public static void addSession(ClientHandler handler) {
            allSessions.add(handler);
            if (handler.getUtilisateurConnecte() != null) {
                userSessions.put(handler.getUtilisateurConnecte().getId(), handler);
            }
        }

        /**
         * Supprime une session client
         */
        public static void removeSession(ClientHandler handler) {
            allSessions.remove(handler);
            if (handler.getUtilisateurConnecte() != null) {
                userSessions.remove(handler.getUtilisateurConnecte().getId());
            }
        }

        /**
         * Récupère la session d'un utilisateur par son ID
         */
        public static ClientHandler getClientByUserId(int userId) {
            return userSessions.get(userId);
        }

        /**
         * Récupère toutes les sessions client
         */
        public static List<ClientHandler> getAllSessions() {
            return new ArrayList<>(allSessions);
        }
    }

    /**
     * Gestionnaire des exportations de données
     */
    public static class ExportController {
        private final DataSource dataSource;

        public ExportController(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        /**
         * Exporte la base de données vers un fichier SQL
         */
        public void exportDatabase(String outputPath) {
            try {
                // Récupérer les paramètres de connexion à partir de DataSource
                // Ici, nous supposons que nous pouvons extraire les informations de connexion
                Models.DatabaseSchemaExporter exporter = new Models.DatabaseSchemaExporter(
                        "localhost", "bmo_db", "bmo_user", "changeme"
                );
                exporter.dumpSchema(outputPath + "_schema.sql");
                exporter.dumpData(outputPath + "_data.sql");

                LOGGER.info("Exportation de la base de données terminée : " + outputPath);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Erreur lors de l'exportation de la base de données", e);
            }
        }

        /**
         * Crée une tâche d'exportation asynchrone
         */
        public Models.ExportJob createExportJob(String type) {
            UUID jobId = UUID.randomUUID();
            Models.ExportJob job = new Models.ExportJob(jobId, type, LocalDateTime.now(), "PENDING");

            // Démarrer un thread pour exécuter la tâche d'exportation
            new Thread(() -> {
                try {
                    job.start();
                    // Exécuter l'exportation selon le type demandé
                    if ("database".equals(type)) {
                        exportDatabase("export_" + jobId);
                    } else if ("meetings".equals(type)) {
                        // Implémenter l'exportation des réunions
                    } else if ("users".equals(type)) {
                        // Implémenter l'exportation des utilisateurs
                    }
                    job.setStatus("COMPLETED");
                } catch (Exception e) {
                    job.setStatus("FAILED: " + e.getMessage());
                    LOGGER.log(Level.SEVERE, "Échec de la tâche d'exportation " + jobId, e);
                }
            }).start();

            return job;
        }
    }

    /**
     * Contrôleur pour les statistiques et analyses
     */
    public static class StatsController {
        private final DataSource dataSource;

        public StatsController(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        /**
         * Récupère les statistiques de participation pour une réunion
         */
        public ParticipationStats getParticipationStats(int meetingId) throws SQLException {
            // TODO: Implémenter la récupération des statistiques de participation
            return null;
        }

        /**
         * Calcule la notation moyenne d'une réunion
         */
        public double getAverageRating(int meetingId) throws SQLException {
            return MeetingRating.averageForMeeting(meetingId);
        }

        /**
         * Récupère l'historique des statistiques de bande passante
         */
        public List<BandwidthStat> getBandwidthStats(int meetingId) throws SQLException {
            return BandwidthStat.forMeeting(meetingId);
        }
    }

    /**
     * Gestionnaire des paramètres système
     */
    public static class SettingsController {
        private final DataSource dataSource;
        private final Map<String, String> settings = new ConcurrentHashMap<>();

        public SettingsController(DataSource dataSource) {
            this.dataSource = dataSource;
            loadSettings();
        }

        /**
         * Charge les paramètres depuis la base de données
         */
        private void loadSettings() {
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT * FROM settings")) {

                while (rs.next()) {
                    settings.put(rs.getString("key"), rs.getString("value"));
                }

                LOGGER.info("Paramètres chargés: " + settings.size());
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement des paramètres", e);
            }
        }

        /**
         * Récupère un paramètre
         */
        public String getSetting(String key, String defaultValue) {
            return settings.getOrDefault(key, defaultValue);
        }

        /**
         * Met à jour un paramètre
         */
        public boolean updateSetting(String key, String value) {
            try (java.sql.Connection conn = dataSource.getConnection();
                 java.sql.PreparedStatement pstmt = conn.prepareStatement(
                         "INSERT INTO settings(key, value) VALUES(?, ?) ON DUPLICATE KEY UPDATE value = ?")) {

                pstmt.setString(1, key);
                pstmt.setString(2, value);
                pstmt.setString(3, value);

                int result = pstmt.executeUpdate();
                if (result > 0) {
                    settings.put(key, value);
                    return true;
                }
                return false;
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors de la mise à jour du paramètre " + key, e);
                return false;
            }
        }
    }
}
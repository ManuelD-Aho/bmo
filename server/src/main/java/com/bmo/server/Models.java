package com.bmo.server;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Classe Models - Couche d'accès aux données pour l'application BMO
 * Gère la persistance et la logique métier des entités
 */
public final class Models {
    private static final Logger LOGGER = Logger.getLogger(Models.class.getName());
    private static DataSource dataSource;

    // Cache pour les données fréquemment utilisées
    private static final Map<Integer, Utilisateur> userCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Reunion> meetingCache = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService cacheCleaner = Executors.newSingleThreadScheduledExecutor();

    // Constantes pour la sécurité et la configuration
    private static final int CACHE_TTL_MINUTES = 15;
    private static final String DEFAULT_ADMIN_LOGIN = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "123";
    private static final String DEFAULT_ADMIN_NAME = "Administrateur";

    // Énumérations pour les types et statuts
    public enum RoleType { ADMIN, USER }
    public enum TypeReunion { Standard, Privee, Democratique }
    public enum StatutReunion { Planifiee, Ouverte, Terminee }
    public enum StatutInvitation { invited, accepted, declined, joined }

    // Constructeur privé pour empêcher l'instanciation
    private Models() {}

    /**
     * Initialise la source de données pour les opérations de base de données
     * @param ds DataSource à utiliser pour les connexions
     */
    public static void initDataSource(DataSource ds) {
        dataSource = ds;

        // Démarrer le nettoyage périodique du cache
        cacheCleaner.scheduleAtFixedRate(() -> {
            try {
                cleanCaches();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors du nettoyage du cache", e);
            }
        }, CACHE_TTL_MINUTES, CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        // Vérifier et créer le compte administrateur par défaut
        try {
            if (!adminExists()) {
                createDefaultAdminAccount(DEFAULT_ADMIN_LOGIN, DEFAULT_ADMIN_PASSWORD, DEFAULT_ADMIN_NAME);
                LOGGER.info("Compte administrateur par défaut créé avec succès.");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de la vérification/création du compte admin", e);
        }
    }

    /**
     * Arrête proprement les services associés
     */
    public static void shutdown() {
        if (cacheCleaner != null && !cacheCleaner.isShutdown()) {
            cacheCleaner.shutdown();
            try {
                if (!cacheCleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                    cacheCleaner.shutdownNow();
                }
            } catch (InterruptedException e) {
                cacheCleaner.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Nettoie les caches pour éviter les fuites mémoire
     */
    private static void cleanCaches() {
        LOGGER.fine("Nettoyage périodique des caches de données");
        userCache.clear();
        meetingCache.clear();
    }

    // ===== GESTION DU COMPTE ADMINISTRATEUR =====

    /**
     * Vérifie si un compte administrateur existe déjà dans la base
     * @return true si au moins un admin existe, false sinon
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean adminExists() throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE role = 'ADMIN'";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    /**
     * Crée un compte administrateur par défaut
     * @param login Login de l'administrateur
     * @param password Mot de passe de l'administrateur (sera stocké tel quel)
     * @param name Nom complet de l'administrateur
     * @return true si la création a réussi, false sinon
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean createDefaultAdminAccount(String login, String password, String name) throws SQLException {
        String sql = "INSERT INTO users (login, password, name, role) VALUES (?, ?, ?, 'ADMIN')";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, login);
            ps.setString(2, password);
            ps.setString(3, name);

            int rowsAffected = ps.executeUpdate();
            return rowsAffected > 0;
        }
    }

    /**
     * Authentifie un utilisateur et retourne ses données
     * @param login Identifiant de l'utilisateur
     * @param motDePasse Mot de passe en clair
     * @return L'utilisateur si authentifié, null sinon
     * @throws SQLException en cas d'erreur de base de données
     */
    public static Utilisateur authenticateUser(String login, String motDePasse) throws SQLException {
        String sql = "SELECT * FROM users WHERE login = ?";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, login);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Utilisateur u = Utilisateur.fromResultSet(rs);
                    if (u.verifierMotDePasse(motDePasse)) {
                        return u;
                    }
                }
            }
        }
        return null;
    }

    // ===== CLASSES D'ENTITÉS =====

    /**
     * Entité représentant un utilisateur du système
     */
    public static class Utilisateur {
        private int id;
        private String login;
        private String motDePasseHash;
        private String nom;
        private RoleType role;
        private byte[] photo;
        private String photoMimeType;
        private LocalDateTime dateCreation;

        // Constructeurs, getters et setters similaires à l'original
        public Utilisateur() {}
        public Utilisateur(int id, String login, String motDePasseHash, String nom, RoleType role,
                           byte[] photo, String photoMimeType, LocalDateTime dateCreation) {
            this.id = id;
            this.login = login;
            this.motDePasseHash = motDePasseHash;
            this.nom = nom;
            this.role = role;
            this.photo = photo;
            this.photoMimeType = photoMimeType;
            this.dateCreation = dateCreation;
        }

        // Getters et Setters avec documentation JavaDoc
        /**
         * @return L'identifiant unique de l'utilisateur
         */
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getLogin() { return login; }
        public void setLogin(String login) { this.login = login; }
        public String getMotDePasseHash() { return motDePasseHash; }
        public void setMotDePasseHash(String motDePasseHash) { this.motDePasseHash = motDePasseHash; }
        public String getNom() { return nom; }
        public void setNom(String nom) { this.nom = nom; }
        public RoleType getRole() { return role; }
        public void setRole(RoleType role) { this.role = role; }
        public byte[] getPhoto() { return photo; }
        public void setPhoto(byte[] photo) { this.photo = photo; }
        public String getPhotoMimeType() { return photoMimeType; }
        public void setPhotoMimeType(String photoMimeType) { this.photoMimeType = photoMimeType; }
        public LocalDateTime getDateCreation() { return dateCreation; }
        public void setDateCreation(LocalDateTime dateCreation) { this.dateCreation = dateCreation; }

        /**
         * Vérifie si le mot de passe fourni correspond à celui de l'utilisateur
         * @param motDePasse Mot de passe à vérifier
         * @return true si le mot de passe est valide
         */
        public boolean verifierMotDePasse(String motDePasse) {
            return Objects.equals(this.motDePasseHash, motDePasse);
        }

        /**
         * Crée un objet Utilisateur à partir d'un ResultSet
         * @param rs ResultSet contenant les données
         * @return Un nouvel objet Utilisateur
         * @throws SQLException En cas d'erreur d'accès aux données
         */
        public static Utilisateur fromResultSet(ResultSet rs) throws SQLException {
            return new Utilisateur(
                    rs.getInt("id"),
                    rs.getString("login"),
                    rs.getString("password"),
                    rs.getString("name"),
                    RoleType.valueOf(rs.getString("role")),
                    rs.getBytes("photo"),
                    rs.getString("photo_mimetype"),
                    rs.getTimestamp("date_created") != null ?
                            rs.getTimestamp("date_created").toLocalDateTime() : null
            );
        }
    }

    /**
     * Entité représentant une réunion
     */
    public static class Reunion {
        private int id;
        private String titre;
        private String agenda;
        private LocalDateTime dateHeure;
        private int duree;
        private TypeReunion type;
        private StatutReunion statut;
        private Utilisateur organisateur;
        private LocalDateTime dateCreation;

        // Constructeurs améliorés pour inclure l'agenda
        public Reunion() {}
        public Reunion(int id, String titre, String agenda, LocalDateTime dateHeure, int duree,
                       TypeReunion type, StatutReunion statut,
                       Utilisateur organisateur, LocalDateTime dateCreation) {
            this.id = id;
            this.titre = titre;
            this.agenda = agenda;
            this.dateHeure = dateHeure;
            this.duree = duree;
            this.type = type;
            this.statut = statut;
            this.organisateur = organisateur;
            this.dateCreation = dateCreation;
        }

        // Getters et Setters avec documentation JavaDoc
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getTitre() { return titre; }
        public void setTitre(String titre) { this.titre = titre; }
        public String getAgenda() { return agenda; }
        public void setAgenda(String agenda) { this.agenda = agenda; }
        public LocalDateTime getDateHeure() { return dateHeure; }
        public void setDateHeure(LocalDateTime dateHeure) { this.dateHeure = dateHeure; }
        public int getDuree() { return duree; }
        public void setDuree(int duree) { this.duree = duree; }
        public TypeReunion getType() { return type; }
        public void setType(TypeReunion type) { this.type = type; }
        public StatutReunion getStatut() { return statut; }
        public void setStatut(StatutReunion statut) { this.statut = statut; }
        public Utilisateur getOrganisateur() { return organisateur; }
        public void setOrganisateur(Utilisateur organisateur) { this.organisateur = organisateur; }
        public LocalDateTime getDateCreation() { return dateCreation; }
        public void setDateCreation(LocalDateTime dateCreation) { this.dateCreation = dateCreation; }

        /**
         * Ouvre la réunion (change son statut à Ouverte)
         * @throws SQLException en cas d'erreur de base de données lors de la mise à jour
         */
        public void ouvrir() throws SQLException {
            this.statut = StatutReunion.Ouverte;
            updateMeetingStatus(this.id, this.statut);
        }

        /**
         * Clôture la réunion (change son statut à Terminée)
         * @throws SQLException en cas d'erreur de base de données lors de la mise à jour
         */
        public void clore() throws SQLException {
            this.statut = StatutReunion.Terminee;
            updateMeetingStatus(this.id, this.statut);
        }

        /**
         * Crée un objet Reunion à partir d'un ResultSet
         * @param rs ResultSet contenant les données
         * @param org Utilisateur organisateur de la réunion
         * @return Un nouvel objet Reunion
         * @throws SQLException En cas d'erreur d'accès aux données
         */
        public static Reunion fromResultSet(ResultSet rs, Utilisateur org) throws SQLException {
            return new Reunion(
                    rs.getInt("id"),
                    rs.getString("title"),
                    rs.getString("agenda"),
                    rs.getTimestamp("datetime").toLocalDateTime(),
                    rs.getInt("duration"),
                    TypeReunion.valueOf(rs.getString("type")),
                    StatutReunion.valueOf(rs.getString("status")),
                    org,
                    rs.getTimestamp("created_at").toLocalDateTime()
            );
        }

        /**
         * Récupère les participants de la réunion
         * @return Liste des participants
         * @throws SQLException en cas d'erreur de base de données
         */
        public List<ParticipantSession> getParticipants() throws SQLException {
            return getParticipantsForMeeting(this.id);
        }

        /**
         * Vérifie si la réunion est en retard (a dépassé son heure de début)
         * @return true si la réunion est en retard
         */
        public boolean isLate() {
            return LocalDateTime.now().isAfter(this.dateHeure) &&
                    this.statut == StatutReunion.Planifiee;
        }

        /**
         * Vérifie si la réunion est en cours
         * @return true si la réunion est actuellement en cours
         */
        public boolean isOngoing() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime endTime = this.dateHeure.plusMinutes(this.duree);
            return this.statut == StatutReunion.Ouverte &&
                    !now.isBefore(this.dateHeure) && now.isBefore(endTime);
        }
    }

    public static class ParticipantSession {
        private Reunion reunion;
        private Utilisateur utilisateur;
        private StatutInvitation statutInvitation;
        private LocalDateTime dateJointure;
        private LocalDateTime dateDepart;

        // Constructeurs, getters, setters comme l'original
        public ParticipantSession() {}
        public ParticipantSession(Reunion reunion, Utilisateur utilisateur,
                                  StatutInvitation statutInvitation,
                                  LocalDateTime dateJointure, LocalDateTime dateDepart) {
            this.reunion = reunion;
            this.utilisateur = utilisateur;
            this.statutInvitation = statutInvitation;
            this.dateJointure = dateJointure;
            this.dateDepart = dateDepart;
        }
        public Reunion getReunion() { return reunion; }
        public void setReunion(Reunion reunion) { this.reunion = reunion; }
        public Utilisateur getUtilisateur() { return utilisateur; }
        public void setUtilisateur(Utilisateur utilisateur) { this.utilisateur = utilisateur; }
        public StatutInvitation getStatutInvitation() { return statutInvitation; }
        public void setStatutInvitation(StatutInvitation statutInvitation) {
            this.statutInvitation = statutInvitation;
        }
        public LocalDateTime getDateJointure() { return dateJointure; }
        public void setDateJointure(LocalDateTime dateJointure) { this.dateJointure = dateJointure; }
        public LocalDateTime getDateDepart() { return dateDepart; }
        public void setDateDepart(LocalDateTime dateDepart) { this.dateDepart = dateDepart; }

        /**
         * L'utilisateur rejoint la réunion
         * @return true si l'action a réussi
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean rejoindre() throws SQLException {
            this.statutInvitation = StatutInvitation.joined;
            this.dateJointure = LocalDateTime.now();
            return recordJoin(this.reunion.getId(), this.utilisateur.getId());
        }

        /**
         * L'utilisateur quitte la réunion
         * @return true si l'action a réussi
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean quitter() throws SQLException {
            this.statutInvitation = StatutInvitation.accepted;
            this.dateDepart = LocalDateTime.now();
            return recordLeave(this.reunion.getId(), this.utilisateur.getId());
        }

        /**
         * Crée un objet ParticipantSession à partir d'un ResultSet
         * @param rs ResultSet contenant les données
         * @param reunion Réunion concernée
         * @param utilisateur Utilisateur concerné
         * @return Un nouvel objet ParticipantSession
         * @throws SQLException en cas d'erreur d'accès aux données
         */
        public static ParticipantSession fromResultSet(ResultSet rs, Reunion reunion,
                                                       Utilisateur utilisateur) throws SQLException {
            return new ParticipantSession(
                    reunion,
                    utilisateur,
                    StatutInvitation.valueOf(rs.getString("status")),
                    rs.getTimestamp("join_time") != null
                            ? rs.getTimestamp("join_time").toLocalDateTime() : null,
                    rs.getTimestamp("leave_time") != null
                            ? rs.getTimestamp("leave_time").toLocalDateTime() : null
            );
        }
    }

    public static class Message {
        private int id;
        private int reunionId;
        private int utilisateurId;
        private LocalDateTime horodatage;
        private String contenu;
        private String userName; // Pour stockage temporaire du nom de l'utilisateur

        // Constructeurs, getters et setters
        public Message() {}
        public Message(int id, int reunionId, int utilisateurId,
                       LocalDateTime horodatage, String contenu) {
            this.id = id;
            this.reunionId = reunionId;
            this.utilisateurId = utilisateurId;
            this.horodatage = horodatage;
            this.contenu = contenu;
        }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public LocalDateTime getHorodatage() { return horodatage; }
        public void setHorodatage(LocalDateTime horodatage) { this.horodatage = horodatage; }
        public String getContenu() { return contenu; }
        public void setContenu(String contenu) { this.contenu = contenu; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }

        /**
         * Crée un objet Message à partir d'un ResultSet
         * @param rs ResultSet contenant les données
         * @return Un nouvel objet Message
         * @throws SQLException en cas d'erreur d'accès aux données
         */
        public static Message fromResultSet(ResultSet rs) throws SQLException {
            Message msg = new Message(
                    rs.getInt("id"),
                    rs.getInt("meeting_id"),
                    rs.getInt("user_id"),
                    rs.getTimestamp("timestamp").toLocalDateTime(),
                    rs.getString("content")
            );

            // Récupérer le nom de l'utilisateur si disponible
            try {
                msg.setUserName(rs.getString("name"));
            } catch (SQLException e) {
                // Ignorer si la colonne n'est pas dans le ResultSet
            }

            return msg;
        }
    }

    public static class Poll {
        private int id;
        private int reunionId;
        private String question;
        private LocalDateTime dateCreation;
        private List<PollOption> options; // Pour stockage temporaire des options

        // Constructeurs, getters et setters similaires à l'original
        public Poll() {}
        public Poll(int id, int reunionId, String question, LocalDateTime dateCreation) {
            this.id = id;
            this.reunionId = reunionId;
            this.question = question;
            this.dateCreation = dateCreation;
        }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public LocalDateTime getDateCreation() { return dateCreation; }
        public void setDateCreation(LocalDateTime dateCreation) { this.dateCreation = dateCreation; }
        public void setOptions(List<PollOption> options) { this.options = options; }

        /**
         * Crée un objet Poll à partir d'un ResultSet
         * @param rs ResultSet contenant les données
         * @return Un nouvel objet Poll
         * @throws SQLException en cas d'erreur d'accès aux données
         */
        public static Poll fromResultSet(ResultSet rs) throws SQLException {
            return new Poll(
                    rs.getInt("id"),
                    rs.getInt("meeting_id"),
                    rs.getString("question"),
                    rs.getTimestamp("created_at").toLocalDateTime()
            );
        }

        /**
         * Récupère les options du sondage
         * @return Liste des options
         * @throws SQLException en cas d'erreur de base de données
         */
        public List<PollOption> getOptions() throws SQLException {
            if (this.options == null) {
                this.options = new ArrayList<>();

                String sql = "SELECT * FROM poll_options WHERE poll_id = ? ORDER BY id";
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, id);

                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            this.options.add(PollOption.fromResultSet(rs));
                        }
                    }
                }
            }
            return this.options;
        }

        /**
         * Récupère les résultats du sondage
         * @return Carte associant chaque option au nombre de votes
         * @throws SQLException en cas d'erreur de base de données
         */
        public Map<PollOption, Integer> getResults() throws SQLException {
            Map<PollOption, Integer> results = new HashMap<>();

            String sql = "SELECT po.*, COUNT(v.user_id) AS votes FROM poll_options po " +
                    "LEFT JOIN votes v ON po.id = v.poll_option_id " +
                    "WHERE po.poll_id = ? GROUP BY po.id";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, id);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PollOption option = PollOption.fromResultSet(rs);
                        results.put(option, rs.getInt("votes"));
                    }
                }
            }
            return results;
        }

        /**
         * Enregistre un vote d'un utilisateur pour une option
         * @param userId ID de l'utilisateur
         * @param optionId ID de l'option choisie
         * @return true si le vote a été enregistré avec succès
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean vote(int userId, int optionId) throws SQLException {
            // Vérifier que l'option appartient bien à ce sondage
            String checkSql = "SELECT COUNT(*) FROM poll_options WHERE id = ? AND poll_id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement checkPs = c.prepareStatement(checkSql)) {
                checkPs.setInt(1, optionId);
                checkPs.setInt(2, id);
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) == 0) {
                        return false; // L'option n'appartient pas à ce sondage
                    }
                }
            }

            // Vérifier si l'utilisateur a déjà voté
            String checkVoteSql = "SELECT COUNT(*) FROM votes v " +
                    "JOIN poll_options po ON v.poll_option_id = po.id " +
                    "WHERE po.poll_id = ? AND v.user_id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement checkVotePs = c.prepareStatement(checkVoteSql)) {
                checkVotePs.setInt(1, id);
                checkVotePs.setInt(2, userId);
                try (ResultSet rs = checkVotePs.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        // L'utilisateur a déjà voté, supprimer son vote précédent
                        String deleteSql = "DELETE FROM votes WHERE user_id = ? AND " +
                                "poll_option_id IN (SELECT id FROM poll_options WHERE poll_id = ?)";
                        try (PreparedStatement deletePs = c.prepareStatement(deleteSql)) {
                            deletePs.setInt(1, userId);
                            deletePs.setInt(2, id);
                            deletePs.executeUpdate();
                        }
                    }
                }
            }

            // Enregistrer le nouveau vote
            String sql = "INSERT INTO votes(poll_option_id, user_id) VALUES(?, ?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, optionId);
                ps.setInt(2, userId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    public static class PollOption {
        private int id;
        private int pollId;
        private String texte;

        public PollOption() {}
        public PollOption(int id, int pollId, String texte) {
            this.id = id;
            this.pollId = pollId;
            this.texte = texte;
        }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getPollId() { return pollId; }
        public void setPollId(int pollId) { this.pollId = pollId; }
        public String getTexte() { return texte; }
        public void setTexte(String texte) { this.texte = texte; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PollOption that = (PollOption) o;
            return id == that.id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }

        public static PollOption fromResultSet(ResultSet rs) throws SQLException {
            return new PollOption(
                    rs.getInt("id"),
                    rs.getInt("poll_id"),
                    rs.getString("text")
            );
        }
    }

    public static class Vote {
        private int pollOptionId;
        private int utilisateurId;
        private LocalDateTime horodatage;

        public Vote() {}
        public Vote(int pollOptionId, int utilisateurId, LocalDateTime horodatage) {
            this.pollOptionId = pollOptionId;
            this.utilisateurId = utilisateurId;
            this.horodatage = horodatage;
        }
        public int getPollOptionId() { return pollOptionId; }
        public void setPollOptionId(int pollOptionId) { this.pollOptionId = pollOptionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public LocalDateTime getHorodatage() { return horodatage; }
        public void setHorodatage(LocalDateTime horodatage) { this.horodatage = horodatage; }

        public static Vote fromResultSet(ResultSet rs) throws SQLException {
            return new Vote(
                    rs.getInt("poll_option_id"),
                    rs.getInt("user_id"),
                    rs.getTimestamp("timestamp").toLocalDateTime()
            );
        }
    }

    public static class Reaction {
        private int reunionId;
        private int utilisateurId;
        private String type;
        private LocalDateTime horodatage;

        public Reaction() {}
        public Reaction(int reunionId, int utilisateurId, String type, LocalDateTime horodatage) {
            this.reunionId = reunionId;
            this.utilisateurId = utilisateurId;
            this.type = type;
            this.horodatage = horodatage;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public LocalDateTime getHorodatage() { return horodatage; }
        public void setHorodatage(LocalDateTime horodatage) { this.horodatage = horodatage; }

        public static Reaction fromResultSet(ResultSet rs) throws SQLException {
            return new Reaction(
                    rs.getInt("meeting_id"),
                    rs.getInt("user_id"),
                    rs.getString("type"),
                    rs.getTimestamp("timestamp").toLocalDateTime()
            );
        }

        /**
         * Enregistre une réaction
         * @return true si l'enregistrement a réussi
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean save() throws SQLException {
            String sql = "INSERT INTO reactions(meeting_id, user_id, type) VALUES(?, ?, ?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, reunionId);
                ps.setInt(2, utilisateurId);
                ps.setString(3, type);
                return ps.executeUpdate() == 1;
            }
        }

        /**
         * Récupère toutes les réactions d'une réunion
         * @param meetingId ID de la réunion
         * @return Liste des réactions
         * @throws SQLException en cas d'erreur de base de données
         */
        public static List<Reaction> forMeeting(int meetingId) throws SQLException {
            List<Reaction> list = new ArrayList<>();
            String sql = "SELECT * FROM reactions WHERE meeting_id = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, meetingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(Reaction.fromResultSet(rs));
                }
            }
            return list;
        }
    }

    public static class MeetingRating {
        private int reunionId;
        private int utilisateurId;
        private int note;
        private String commentaire;
        private LocalDateTime horodatage;

        public MeetingRating() {}
        public MeetingRating(int reunionId, int utilisateurId, int note, String commentaire, LocalDateTime horodatage) {
            this.reunionId = reunionId;
            this.utilisateurId = utilisateurId;
            this.note = note;
            this.commentaire = commentaire;
            this.horodatage = horodatage;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public int getNote() { return note; }
        public void setNote(int note) { this.note = note; }
        public String getCommentaire() { return commentaire; }
        public void setCommentaire(String commentaire) { this.commentaire = commentaire; }
        public LocalDateTime getHorodatage() { return horodatage; }
        public void setHorodatage(LocalDateTime horodatage) { this.horodatage = horodatage; }

        public static MeetingRating fromResultSet(ResultSet rs) throws SQLException {
            return new MeetingRating(
                    rs.getInt("meeting_id"),
                    rs.getInt("user_id"),
                    rs.getInt("rating"),
                    rs.getString("comment"),
                    rs.getTimestamp("timestamp").toLocalDateTime()
            );
        }

        /**
         * Enregistre une évaluation de réunion
         * @return true si l'enregistrement a réussi
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean save() throws SQLException {
            String sql = "INSERT INTO meeting_ratings(meeting_id, user_id, rating, comment) VALUES(?, ?, ?, ?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, reunionId);
                ps.setInt(2, utilisateurId);
                ps.setInt(3, note);
                ps.setString(4, commentaire);
                return ps.executeUpdate() == 1;
            }
        }

        /**
         * Calcule la note moyenne d'une réunion
         * @param meetingId ID de la réunion
         * @return Note moyenne
         * @throws SQLException en cas d'erreur de base de données
         */
        public static double averageForMeeting(int meetingId) throws SQLException {
            String sql = "SELECT AVG(rating) AS avg_rating FROM meeting_ratings WHERE meeting_id = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, meetingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble("avg_rating") : 0.0;
                }
            }
        }
    }

    public static class VideoSession {
        private int reunionId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String resolution;
        private int framerate;

        public VideoSession() {}
        public VideoSession(int reunionId, LocalDateTime startTime, String resolution, int framerate) {
            this.reunionId = reunionId;
            this.startTime = startTime;
            this.resolution = resolution;
            this.framerate = framerate;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public String getResolution() { return resolution; }
        public void setResolution(String resolution) { this.resolution = resolution; }
        public int getFramerate() { return framerate; }
        public void setFramerate(int framerate) { this.framerate = framerate; }

        public void start() { this.startTime = LocalDateTime.now(); }
        public void stop() { this.endTime = LocalDateTime.now(); }
    }

    public static class ScreenShareSession {
        private int reunionId;
        private int utilisateurId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;

        public ScreenShareSession() {}
        public ScreenShareSession(int reunionId, int utilisateurId, LocalDateTime startTime) {
            this.reunionId = reunionId;
            this.utilisateurId = utilisateurId;
            this.startTime = startTime;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

        public void start() { this.startTime = LocalDateTime.now(); }
        public void stop() { this.endTime = LocalDateTime.now(); }
    }

    public static class RecordingSession {
        private int reunionId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String filePath;

        public RecordingSession() {}
        public RecordingSession(int reunionId, LocalDateTime startTime, String filePath) {
            this.reunionId = reunionId;
            this.startTime = startTime;
            this.filePath = filePath;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        /**
         * Archive la session d'enregistrement
         */
        public void archive() {
            // Implémentation de l'archivage des enregistrements
            // Méthode stub pour éviter l'erreur de compilation
            System.out.println("Archivage de l'enregistrement: " + filePath);
        }

        /**
         * Récupère tous les enregistrements d'une réunion
         * @param meetingId ID de la réunion
         * @return Liste des sessions d'enregistrement
         * @throws SQLException en cas d'erreur de base de données
         */
        public static List<RecordingSession> listForMeeting(int meetingId) throws SQLException {
            List<RecordingSession> list = new ArrayList<>();
            String sql = "SELECT * FROM recording_sessions WHERE meeting_id = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, meetingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        RecordingSession rsess = new RecordingSession(
                                rs.getInt("meeting_id"),
                                rs.getTimestamp("start_time").toLocalDateTime(),
                                rs.getString("file_path")
                        );
                        rsess.setEndTime(rs.getTimestamp("end_time").toLocalDateTime());
                        list.add(rsess);
                    }
                }
            }
            return list;
        }
    }

    public static class ParticipationStats {
        private int reunionId;
        private long totalInvited;
        private long acceptedCount;
        private long declinedCount;
        private long joinedCount;

        public ParticipationStats() {}
        public ParticipationStats(int reunionId, long totalInvited,
                                  long acceptedCount, long declinedCount, long joinedCount) {
            this.reunionId = reunionId;
            this.totalInvited = totalInvited;
            this.acceptedCount = acceptedCount;
            this.declinedCount = declinedCount;
            this.joinedCount = joinedCount;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public long getTotalInvited() { return totalInvited; }
        public void setTotalInvited(long totalInvited) { this.totalInvited = totalInvited; }
        public long getAcceptedCount() { return acceptedCount; }
        public void setAcceptedCount(long acceptedCount) { this.acceptedCount = acceptedCount; }
        public long getDeclinedCount() { return declinedCount; }
        public void setDeclinedCount(long declinedCount) { this.declinedCount = declinedCount; }
        public long getJoinedCount() { return joinedCount; }
        public void setJoinedCount(long joinedCount) { this.joinedCount = joinedCount; }

        public static ParticipationStats fromResultSet(ResultSet rs) throws SQLException {
            return new ParticipationStats(
                    rs.getInt("meeting_id"),
                    rs.getLong("total_invited"),
                    rs.getLong("accepted_count"),
                    rs.getLong("declined_count"),
                    rs.getLong("joined_count")
            );
        }
    }

    public static class BandwidthStat {
        private int reunionId;
        private int utilisateurId;
        private LocalDateTime horodatage;
        private long uploadBytes;
        private long downloadBytes;

        public BandwidthStat() {}
        public BandwidthStat(int reunionId, int utilisateurId, LocalDateTime horodatage,
                             long uploadBytes, long downloadBytes) {
            this.reunionId = reunionId;
            this.utilisateurId = utilisateurId;
            this.horodatage = horodatage;
            this.uploadBytes = uploadBytes;
            this.downloadBytes = downloadBytes;
        }
        public int getReunionId() { return reunionId; }
        public void setReunionId(int reunionId) { this.reunionId = reunionId; }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public LocalDateTime getHorodatage() { return horodatage; }
        public void setHorodatage(LocalDateTime horodatage) { this.horodatage = horodatage; }
        public long getUploadBytes() { return uploadBytes; }
        public void setUploadBytes(long uploadBytes) { this.uploadBytes = uploadBytes; }
        public long getDownloadBytes() { return downloadBytes; }
        public void setDownloadBytes(long downloadBytes) { this.downloadBytes = downloadBytes; }

        /**
         * Enregistre des statistiques de bande passante
         * @param up Octets envoyés
         * @param down Octets reçus
         * @throws SQLException en cas d'erreur de base de données
         */
        public void record(long up, long down) throws SQLException {
            String sql = "INSERT INTO bandwidth_stats(meeting_id, user_id, timestamp, upload, download) VALUES(?, ?, ?, ?, ?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, reunionId);
                ps.setInt(2, utilisateurId);
                ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
                ps.setLong(4, up);
                ps.setLong(5, down);
                ps.executeUpdate();
            }
        }

        /**
         * Récupère les statistiques de bande passante d'une réunion
         * @param meetingId ID de la réunion
         * @return Liste des statistiques de bande passante
         * @throws SQLException en cas d'erreur de base de données
         */
        public static List<BandwidthStat> forMeeting(int meetingId) throws SQLException {
            List<BandwidthStat> list = new ArrayList<>();
            String sql = "SELECT * FROM bandwidth_stats WHERE meeting_id = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, meetingId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new BandwidthStat(
                                rs.getInt("meeting_id"),
                                rs.getInt("user_id"),
                                rs.getTimestamp("timestamp").toLocalDateTime(),
                                rs.getLong("upload"),
                                rs.getLong("download")
                        ));
                    }
                }
            }
            return list;
        }
    }

    public static class UserProfile {
        private int utilisateurId;
        private String displayName;
        private byte[] photo;
        private String photoMimeType;

        public UserProfile() {}
        public UserProfile(int utilisateurId, String displayName, byte[] photo, String photoMimeType) {
            this.utilisateurId = utilisateurId;
            this.displayName = displayName;
            this.photo = photo;
            this.photoMimeType = photoMimeType;
        }
        public int getUtilisateurId() { return utilisateurId; }
        public void setUtilisateurId(int utilisateurId) { this.utilisateurId = utilisateurId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public byte[] getPhoto() { return photo; }
        public void setPhoto(byte[] photo) { this.photo = photo; }
        public String getPhotoMimeType() { return photoMimeType; }
        public void setPhotoMimeType(String photoMimeType) { this.photoMimeType = photoMimeType; }

        /**
         * Met à jour la photo de profil d'un utilisateur
         * @param newPhoto Nouvelle photo
         * @param mimeType Type MIME de la photo
         * @return true si la mise à jour a réussi
         * @throws SQLException en cas d'erreur de base de données
         */
        public boolean updatePhoto(byte[] newPhoto, String mimeType) throws SQLException {
            String sql = "UPDATE users SET photo = ?, photo_mimetype = ? WHERE id = ?";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setBytes(1, newPhoto);
                ps.setString(2, mimeType);
                ps.setInt(3, utilisateurId);
                return ps.executeUpdate() == 1;
            }
        }
    }

    public static class ExportJob {
        private UUID jobId;
        private String type;
        private LocalDateTime requestedAt;
        private String status;

        public ExportJob() {}
        public ExportJob(UUID jobId, String type, LocalDateTime requestedAt, String status) {
            this.jobId = jobId;
            this.type = type;
            this.requestedAt = requestedAt;
            this.status = status;
        }
        public UUID getJobId() { return jobId; }
        public void setJobId(UUID jobId) { this.jobId = jobId; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public LocalDateTime getRequestedAt() { return requestedAt; }
        public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public void start() { this.status = "RUNNING"; this.requestedAt = LocalDateTime.now(); }
        public void reportProgress(int percent) { this.status = percent + "%"; }
    }

    // ===== MÉTHODES D'ACCÈS AUX DONNÉES (DAO) =====

    /**
     * Met à jour le statut d'une réunion dans la base de données
     * @param reunionId ID de la réunion
     * @param statut Nouveau statut
     * @throws SQLException en cas d'erreur de base de données
     */
    private static void updateMeetingStatus(int reunionId, StatutReunion statut) throws SQLException {
        String sql = "UPDATE meetings SET status = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, statut.name());
            ps.setInt(2, reunionId);
            ps.executeUpdate();

            // Mettre à jour le cache si la réunion y est présente
            if (meetingCache.containsKey(reunionId)) {
                Reunion cachedMeeting = meetingCache.get(reunionId);
                cachedMeeting.setStatut(statut);
            }
        }
    }

    /**
     * Récupère un utilisateur par son ID
     * @param userId ID de l'utilisateur
     * @return L'utilisateur trouvé ou null
     * @throws SQLException en cas d'erreur de base de données
     */
    public static Utilisateur getUserById(int userId) throws SQLException {
        // Vérifier d'abord dans le cache
        if (userCache.containsKey(userId)) {
            return userCache.get(userId);
        }

        // Sinon, chercher en base de données
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Utilisateur user = Utilisateur.fromResultSet(rs);
                    userCache.put(userId, user); // Mettre en cache
                    return user;
                }
            }
        }
        return null;
    }

    /**
     * Ajoute un nouvel utilisateur
     * @param user L'utilisateur à ajouter
     * @return true si l'utilisateur a été ajouté avec succès
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean addUser(Utilisateur user) throws SQLException {
        String sql = "INSERT INTO users(login, password, name, role, photo, photo_mimetype) " +
                "VALUES(?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.getLogin());
            ps.setString(2, user.getMotDePasseHash());
            ps.setString(3, user.getNom());
            ps.setString(4, user.getRole().name());
            ps.setBytes(5, user.getPhoto());
            ps.setString(6, user.getPhotoMimeType());

            int affectedRows = ps.executeUpdate();
            if (affectedRows == 1) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        user.setId(keys.getInt(1));
                        userCache.put(user.getId(), user); // Mettre en cache
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Supprime un utilisateur par son ID
     * @param id ID de l'utilisateur à supprimer
     * @return true si l'utilisateur a été supprimé avec succès
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean deleteUser(int id) throws SQLException {
        // Vérification supplémentaire : ne pas supprimer le dernier admin
        if (isLastAdmin(id)) {
            LOGGER.warning("Tentative de suppression du dernier administrateur (ID: " + id + ")");
            return false;
        }

        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            int affectedRows = ps.executeUpdate();

            // Si la suppression a réussi, retirer du cache
            if (affectedRows == 1) {
                userCache.remove(id);
                return true;
            }
        }
        return false;
    }

    /**
     * Vérifie si un utilisateur est le dernier administrateur
     * @param userId ID de l'utilisateur à vérifier
     * @return true si c'est le dernier admin
     * @throws SQLException en cas d'erreur de base de données
     */
    private static boolean isLastAdmin(int userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND id != ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        }
        return false;
    }

    /**
     * Récupère tous les utilisateurs
     * @return Liste de tous les utilisateurs
     * @throws SQLException en cas d'erreur de base de données
     */
    public static List<Utilisateur> getAllUsers() throws SQLException {
        List<Utilisateur> users = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY login";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                Utilisateur user = Utilisateur.fromResultSet(rs);
                users.add(user);
                userCache.put(user.getId(), user); // Mettre en cache
            }
        }
        return users;
    }

    /**
     * Crée une nouvelle réunion
     * @param meeting La réunion à créer
     * @return ID de la réunion créée ou 0 si échec
     * @throws SQLException en cas d'erreur de base de données
     */
    public static int createMeeting(Reunion meeting) throws SQLException {
        String sql = "INSERT INTO meetings(title, agenda, datetime, duration, type, status, organizer_id) " +
                "VALUES(?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, meeting.getTitre());
            ps.setString(2, meeting.getAgenda() != null ? meeting.getAgenda() : "");
            ps.setTimestamp(3, Timestamp.valueOf(meeting.getDateHeure()));
            ps.setInt(4, meeting.getDuree());
            ps.setString(5, meeting.getType().name());
            ps.setString(6, meeting.getStatut().name());
            ps.setInt(7, meeting.getOrganisateur().getId());

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int newId = keys.getInt(1);
                    meeting.setId(newId);
                    meetingCache.put(newId, meeting); // Mettre en cache

                    // Ajouter automatiquement l'organisateur comme participant
                    addParticipant(newId, meeting.getOrganisateur().getId(), StatutInvitation.accepted);

                    return newId;
                }
            }
        }
        return 0;
    }

    /**
     * Récupère une réunion par son ID
     * @param meetingId ID de la réunion
     * @return La réunion trouvée ou null
     * @throws SQLException en cas d'erreur de base de données
     */
    public static Reunion getMeetingById(int meetingId) throws SQLException {
        // Vérifier d'abord dans le cache
        if (meetingCache.containsKey(meetingId)) {
            return meetingCache.get(meetingId);
        }

        // Sinon, chercher en base de données
        String sql = "SELECT m.*, u.* FROM meetings m " +
                "JOIN users u ON m.organizer_id = u.id " +
                "WHERE m.id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, meetingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // Créer l'organisateur
                    Utilisateur organizer = Utilisateur.fromResultSet(rs);

                    // Créer la réunion avec l'organisateur
                    Reunion meeting = new Reunion(
                            rs.getInt("id"),
                            rs.getString("title"),
                            rs.getString("agenda"),
                            rs.getTimestamp("datetime").toLocalDateTime(),
                            rs.getInt("duration"),
                            TypeReunion.valueOf(rs.getString("type")),
                            StatutReunion.valueOf(rs.getString("status")),
                            organizer,
                            rs.getTimestamp("created_at").toLocalDateTime()
                    );

                    // Mettre en cache
                    meetingCache.put(meetingId, meeting);
                    return meeting;
                }
            }
        }
        return null;
    }

    /**
     * Ajoute un participant à une réunion
     * @param meetingId ID de la réunion
     * @param userId ID de l'utilisateur
     * @param statut Statut initial de l'invitation
     * @return true si l'ajout a réussi
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean addParticipant(int meetingId, int userId, StatutInvitation statut) throws SQLException {
        String sql = "INSERT INTO participants(meeting_id, user_id, status, role) VALUES(?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, meetingId);
            ps.setInt(2, userId);
            ps.setString(3, statut.name());

            // Déterminer si l'utilisateur est l'organisateur
            boolean isOrganizer = false;
            Reunion meeting = getMeetingById(meetingId);
            if (meeting != null && meeting.getOrganisateur().getId() == userId) {
                isOrganizer = true;
            }

            ps.setString(4, isOrganizer ? "organizer" : "participant");

            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Enregistre le moment où un utilisateur rejoint une réunion
     * @param meetingId ID de la réunion
     * @param userId ID de l'utilisateur
     * @return true si l'opération a réussi
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean recordJoin(int meetingId, int userId) throws SQLException {
        String sql = "UPDATE participants SET status = 'joined', join_time = ? " +
                "WHERE meeting_id = ? AND user_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setInt(2, meetingId);
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setInt(2, meetingId);
            ps.setInt(3, userId);

            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Enregistre le moment où un utilisateur quitte une réunion
     * @param meetingId ID de la réunion
     * @param userId ID de l'utilisateur
     * @return true si l'opération a réussi
     * @throws SQLException en cas d'erreur de base de données
     */
    public static boolean recordLeave(int meetingId, int userId) throws SQLException {
        String sql = "UPDATE participants SET leave_time = ? " +
                "WHERE meeting_id = ? AND user_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            ps.setInt(2, meetingId);
            ps.setInt(3, userId);

            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Récupère les participants d'une réunion
     * @param meetingId ID de la réunion
     * @return Liste des participants
     * @throws SQLException en cas d'erreur de base de données
     */
    public static List<ParticipantSession> getParticipantsForMeeting(int meetingId) throws SQLException {
        List<ParticipantSession> participants = new ArrayList<>();

        String sql = "SELECT p.*, u.* FROM participants p " +
                "JOIN users u ON p.user_id = u.id " +
                "WHERE p.meeting_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, meetingId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Utilisateur user = Utilisateur.fromResultSet(rs);

                    // Réutiliser l'instance de Reunion en cache si disponible
                    Reunion meeting;
                    if (meetingCache.containsKey(meetingId)) {
                        meeting = meetingCache.get(meetingId);
                    } else {
                        meeting = getMeetingById(meetingId);
                    }

                    if (meeting != null) {
                        ParticipantSession session = new ParticipantSession(
                                meeting,
                                user,
                                StatutInvitation.valueOf(rs.getString("status")),
                                rs.getTimestamp("join_time") != null ?
                                        rs.getTimestamp("join_time").toLocalDateTime() : null,
                                rs.getTimestamp("leave_time") != null ?
                                        rs.getTimestamp("leave_time").toLocalDateTime() : null
                        );

                        participants.add(session);
                    }
                }
            }
        }
        return participants;
    }

    /**
     * Récupère toutes les réunions d'un utilisateur (organisées ou invitées)
     * @param userId ID de l'utilisateur
     * @return Liste des réunions
     * @throws SQLException en cas d'erreur de base de données
     */
    public static List<Reunion> getMeetingsForUser(int userId) throws SQLException {
        List<Reunion> meetings = new ArrayList<>();

        // On récupère à la fois les réunions organisées par l'utilisateur
        // et celles auxquelles il est invité
        String sql = "SELECT DISTINCT m.*, u.* FROM meetings m " +
                "JOIN users u ON m.organizer_id = u.id " +
                "LEFT JOIN participants p ON m.id = p.meeting_id " +
                "WHERE m.organizer_id = ? OR p.user_id = ? " +
                "ORDER BY m.datetime DESC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Créer l'organisateur
                    Utilisateur organizer = Utilisateur.fromResultSet(rs);

                    // Créer la réunion
                    int meetingId = rs.getInt("m.id");

                    // Si déjà en cache, récupérer l'instance en cache
                    if (meetingCache.containsKey(meetingId)) {
                        meetings.add(meetingCache.get(meetingId));
                        continue;
                    }

                    // Sinon créer une nouvelle instance
                    Reunion meeting = new Reunion(
                            meetingId,
                            rs.getString("m.title"),
                            rs.getString("m.agenda"),
                            rs.getTimestamp("m.datetime").toLocalDateTime(),
                            rs.getInt("m.duration"),
                            TypeReunion.valueOf(rs.getString("m.type")),
                            StatutReunion.valueOf(rs.getString("m.status")),
                            organizer,
                            rs.getTimestamp("m.created_at").toLocalDateTime()
                    );

                    // Mettre en cache et ajouter à la liste
                    meetingCache.put(meetingId, meeting);
                    meetings.add(meeting);
                }
            }
        }
        return meetings;
    }

    /**
     * Ajoute un message au chat d'une réunion
     * @param message Message à ajouter
     * @return ID du message créé ou 0 si échec
     * @throws SQLException en cas d'erreur de base de données
     */
    public static int addMessage(Message message) throws SQLException {
        String sql = "INSERT INTO messages(meeting_id, user_id, content) VALUES(?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, message.getReunionId());
            ps.setInt(2, message.getUtilisateurId());
            ps.setString(3, message.getContenu());

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int newId = keys.getInt(1);
                    message.setId(newId);
                    return newId;
                }
            }
        }
        return 0;
    }

    /**
     * Récupère les messages d'une réunion
     * @param meetingId ID de la réunion
     * @param limit Nombre maximum de messages à récupérer (0 pour tous)
     * @return Liste des messages
     * @throws SQLException en cas d'erreur de base de données
     */
    public static List<Message> getMessagesForMeeting(int meetingId, int limit) throws SQLException {
        List<Message> messages = new ArrayList<>();

        String sql = "SELECT m.*, u.name FROM messages m " +
                "JOIN users u ON m.user_id = u.id " +
                "WHERE m.meeting_id = ? " +
                "ORDER BY m.timestamp " +
                (limit > 0 ? "LIMIT ?" : "");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, meetingId);
            if (limit > 0) {
                ps.setInt(2, limit);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Message msg = Message.fromResultSet(rs);
                    // Ajouter le nom de l'utilisateur pour affichage
                    // Le nom est récupéré directement dans fromResultSet
                    messages.add(msg);
                }
            }
        }
        return messages;
    }

    /**
     * Crée un sondage pour une réunion
     * @param poll Le sondage à créer
     * @param options Liste des options du sondage
     * @return ID du sondage créé ou 0 si échec
     * @throws SQLException en cas d'erreur de base de données
     */
    public static int createPoll(Poll poll, List<String> options) throws SQLException {
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException("Un sondage doit avoir au moins 2 options");
        }

        Connection conn = null;
        try {
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);

            // Créer le sondage
            String pollSql = "INSERT INTO polls(meeting_id, question) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(pollSql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, poll.getReunionId());
                ps.setString(2, poll.getQuestion());

                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        int pollId = keys.getInt(1);
                        poll.setId(pollId);

                        // Ajouter les options
                        String optionSql = "INSERT INTO poll_options(poll_id, text) VALUES(?, ?)";
                        try (PreparedStatement optionPs = conn.prepareStatement(optionSql)) {
                            for (String option : options) {
                                optionPs.setInt(1, pollId);
                                optionPs.setString(2, option);
                                optionPs.addBatch();
                            }
                            optionPs.executeBatch();
                        }

                        conn.commit();
                        return pollId;
                    }
                }
            }

            conn.rollback();
            return 0;
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    LOGGER.log(Level.WARNING, "Erreur lors du rollback", ex);
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException ex) {
                    LOGGER.log(Level.WARNING, "Erreur lors de la fermeture de la connexion", ex);
                }
            }
        }
    }

    /**
     * Récupère tous les sondages d'une réunion
     * @param meetingId ID de la réunion
     * @return Liste des sondages
     * @throws SQLException en cas d'erreur de base de données
     */
    public static List<Poll> getPollsForMeeting(int meetingId) throws SQLException {
        List<Poll> polls = new ArrayList<>();
        String sql = "SELECT * FROM polls WHERE meeting_id = ? ORDER BY created_at DESC";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, meetingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Poll poll = Poll.fromResultSet(rs);
                    polls.add(poll);
                }
            }
        }
        return polls;
    }

    /**
     * Classe pour gérer les connexions à la base de données
     */
    public static class DatabaseConnectionPool implements AutoCloseable {
        private final DataSource dataSource;

        public DatabaseConnectionPool(DataSource ds) {
            this.dataSource = ds;
        }

        public Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void close() throws Exception {
            if (dataSource instanceof AutoCloseable) {
                ((AutoCloseable) dataSource).close();
            }
        }
    }

    /**
     * Classe pour l'exportation du schéma et des données de la base
     */
    public static class DatabaseSchemaExporter {
        private final String host;
        private final String dbName;
        private final String user;
        private final String password;

        public DatabaseSchemaExporter(String host, String dbName, String user, String password) {
            this.host = host;
            this.dbName = dbName;
            this.user = user;
            this.password = password;
        }

        /**
         * Exporte le schéma de la base sans les données
         * @param outputPath Chemin du fichier de sortie
         * @throws Exception en cas d'erreur lors de l'export
         */
        public void dumpSchema(String outputPath) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(
                    "mysqldump",
                    "-h", host,
                    "-u", user,
                    "-p" + password,
                    "--no-data",
                    dbName,
                    "--result-file=" + outputPath
            );
            Process p = pb.start();
            if (p.waitFor() != 0) {
                throw new RuntimeException("Erreur dump schema");
            }
        }

        /**
         * Exporte les données de la base sans le schéma
         * @param outputPath Chemin du fichier de sortie
         * @throws Exception en cas d'erreur lors de l'export
         */
        public void dumpData(String outputPath) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(
                    "mysqldump",
                    "-h", host,
                    "-u", user,
                    "-p" + password,
                    "--no-create-info",
                    dbName,
                    "--result-file=" + outputPath
            );
            Process p = pb.start();
            if (p.waitFor() != 0) {
                throw new RuntimeException("Erreur dump data");
            }
        }
    }
}
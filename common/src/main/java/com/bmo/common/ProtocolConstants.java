package com.bmo.common;

/**
 * Définit les constantes utilisées dans le protocole de communication entre le client et le serveur BMO.
 * Ces constantes sont partagées entre les modules client et serveur pour assurer la cohérence du protocole.
 */
public final class ProtocolConstants {

    private ProtocolConstants() {
        // Classe utilitaire, ne doit pas être instanciée
    }

    // Délimiteur utilisé dans le protocole
    public static final String DELIMITER = "|";

    // ======= COMMANDES CLIENT VERS SERVEUR =======

    // Authentification et gestion des sessions
    public static final String CMD_LOGIN = "LOGIN";
    public static final String CMD_LOGOUT = "LOGOUT";
    public static final String CMD_REGISTER = "REGISTER";

    // Gestion des réunions
    public static final String CMD_GET_MEETINGS = "GET_MEETINGS";
    public static final String CMD_NEW_MEETING = "NEW_MEETING";
    public static final String CMD_JOIN_MEETING = "JOIN";
    public static final String CMD_LEAVE_MEETING = "LEAVE";
    public static final String CMD_CLOSE_MEETING = "CLOSE_MEETING";

    // Communication en réunion
    public static final String CMD_CHAT_MESSAGE = "CHAT_MSG";
    public static final String CMD_REQUEST_SPEAK = "REQUEST_SPEAK";
    public static final String CMD_ALLOW_SPEAK = "ALLOW_SPEAK";
    public static final String CMD_DENY_SPEAK = "DENY_SPEAK";

    // Sondages
    public static final String CMD_CREATE_POLL = "CREATE_POLL";
    public static final String CMD_VOTE = "VOTE";
    public static final String CMD_GET_POLL_RESULTS = "GET_POLL_RESULTS";

    // Réactions et évaluations
    public static final String CMD_SEND_REACTION = "REACTION";
    public static final String CMD_RATE_MEETING = "RATE_MEETING";

    // Enregistrement de réunion
    public static final String CMD_START_RECORDING = "START_RECORDING";
    public static final String CMD_STOP_RECORDING = "STOP_RECORDING";

    // Gestion utilisateurs et paramètres (admin)
    public static final String CMD_GET_USERS = "GET_USERS";
    public static final String CMD_ADD_USER = "ADD_USER";
    public static final String CMD_DELETE_USER = "DELETE_USER";
    public static final String CMD_UPDATE_USER = "UPDATE_USER";
    public static final String CMD_GET_SETTINGS = "GET_SETTINGS";
    public static final String CMD_UPDATE_SETTING = "UPDATE_SETTING";

    // Métriques et statistiques
    public static final String CMD_UPDATE_BANDWIDTH = "UPDATE_BANDWIDTH";
    public static final String CMD_GET_STATS = "GET_STATS";
    public static final String CMD_EXPORT_DATA = "EXPORT_DATA";

    // ======= RÉPONSES SERVEUR VERS CLIENT =======

    // Réponses standards
    public static final String RESP_OK = "OK";
    public static final String RESP_ERROR = "ERREUR";

    // Authentification
    public static final String RESP_AUTH_OK = "AUTH_OK";
    public static final String RESP_AUTH_FAIL = "AUTH_ECHEC";

    // Gestion des réunions
    public static final String RESP_MEETINGS = "REUNIONS";
    public static final String RESP_JOIN_OK = "JOIN_OK";
    public static final String RESP_PARTICIPANTS = "PARTICIPANTS";

    // Gestion utilisateurs et paramètres
    public static final String RESP_USERS = "UTILISATEURS";
    public static final String RESP_SETTINGS = "PARAMETRES";

    // Sondages
    public static final String RESP_POLL_RESULTS = "RESULTATS_SONDAGE";

    // Métriques et statistiques
    public static final String RESP_STATS = "STATISTIQUES";
    public static final String RESP_EXPORT_STATUS = "STATUT_EXPORT";

    // ======= NOTIFICATIONS (PUSH SERVEUR VERS CLIENT) =======

    // Notifications des réunions
    public static final String NOTIFY_NEW_MEETING = "NOUVELLE_REUNION";
    public static final String NOTIFY_MEETING_UPDATED = "REUNION_MISE_A_JOUR";
    public static final String NOTIFY_MEETING_CANCELLED = "REUNION_ANNULEE";
    public static final String NOTIFY_MEETING_STATUS = "STATUT_REUNION";
    public static final String NOTIFY_MEETING_CLOSED = "REUNION_FERMEE";

    // Notifications des participants
    public static final String NOTIFY_USER_JOINED = "UTILISATEUR_REJOINT";
    public static final String NOTIFY_USER_LEFT = "UTILISATEUR_PARTI";

    // Notifications de communication
    public static final String NOTIFY_CHAT_MESSAGE = "MSG_CHAT";
    public static final String NOTIFY_SPEAK_REQUEST = "DEMANDE_PAROLE";
    public static final String NOTIFY_SPEAK_GRANTED = "PAROLE_ACCORDEE";
    public static final String NOTIFY_SPEAK_DENIED = "PAROLE_REFUSEE";
    public static final String NOTIFY_SPEAKER_ACTIVE = "ORATEUR_ACTIF";

    // Notifications des sondages
    public static final String NOTIFY_NEW_POLL = "NOUVEAU_SONDAGE";
    public static final String NOTIFY_POLL_ENDED = "SONDAGE_TERMINE";
    public static final String NOTIFY_POLL_RESULTS = "RESULTATS_SONDAGE";

    // Notifications des réactions
    public static final String NOTIFY_REACTION = "REACTION";
    public static final String NOTIFY_MEETING_RATED = "REUNION_EVALUEE";

    // Notifications d'enregistrement
    public static final String NOTIFY_RECORDING_STARTED = "ENREGISTREMENT_DEMARRE";
    public static final String NOTIFY_RECORDING_STOPPED = "ENREGISTREMENT_ARRETE";

    // Notifications des métriques
    public static final String NOTIFY_BANDWIDTH_WARNING = "AVERTISSEMENT_BANDE_PASSANTE";
    public static final String NOTIFY_CONNECTION_QUALITY = "QUALITE_CONNEXION";

    // ======= TYPES D'UTILISATEURS =======
    public static final String USER_ADMIN = "ADMIN";
    public static final String USER_REGULAR = "UTILISATEUR";

    // ======= TYPES DE RÉUNIONS =======
    public static final String MEETING_STANDARD = "Standard";
    public static final String MEETING_PRIVATE = "Privee";
    public static final String MEETING_DEMOCRATIC = "Democratique";

    // ======= STATUTS DE RÉUNIONS =======
    public static final String STATUS_PLANNED = "Planifiee";
    public static final String STATUS_OPEN = "Ouverte";
    public static final String STATUS_CLOSED = "Terminee";

    // ======= STATUTS D'INVITATION =======
    public static final String INVITATION_INVITED = "invite";
    public static final String INVITATION_ACCEPTED = "accepte";
    public static final String INVITATION_DECLINED = "decline";
    public static final String INVITATION_JOINED = "rejoint";

    // ======= TYPES DE RÉACTIONS =======
    public static final String REACTION_THUMBS_UP = "pouce_haut";
    public static final String REACTION_THUMBS_DOWN = "pouce_bas";
    public static final String REACTION_CLAP = "applaudissement";
    public static final String REACTION_LAUGH = "rire";
    public static final String REACTION_SURPRISE = "surprise";
    public static final String REACTION_CONFUSED = "confus";
    public static final String REACTION_QUESTION = "question";
    public static final String REACTION_AGREE = "accord";
    public static final String REACTION_DISAGREE = "desaccord";

    // ======= PARAMÈTRES SYSTÈME =======
    public static final String SETTING_MAX_CONNECTIONS = "serveur.maxConnexions";
    public static final String SETTING_TIMEOUT_SECONDS = "serveur.tempsExpiration";
    public static final String SETTING_VIDEO_ENABLED = "video.active";
    public static final String SETTING_CHAT_FILTER = "chat.filtrerMots";
    public static final String SETTING_RECORDING_ENABLED = "fonction.enregistrement";
    public static final String SETTING_POLLS_ENABLED = "fonction.sondages";
    public static final String SETTING_REACTIONS_ENABLED = "fonction.reactions";
    public static final String SETTING_STRONG_PASSWORD = "auth.exigerMotDePasseFort";
    public static final String SETTING_SELF_REGISTRATION = "auth.autoriserAutoInscription";
    public static final String SETTING_TLS_ENABLED = "tls.active";
    public static final String SETTING_UI_THEME = "ui.theme";

    // ======= CODES D'ERREUR =======
    public static final int ERROR_UNAUTHORIZED = 401;
    public static final int ERROR_FORBIDDEN = 403;
    public static final int ERROR_NOT_FOUND = 404;
    public static final int ERROR_SERVER = 500;
    public static final int ERROR_TIMEOUT = 408;
    public static final int ERROR_MEETING_CLOSED = 410;
    public static final int ERROR_CONSTRAINT = 409;

    // ======= PARAMÈTRES VIDÉO =======
    public static final String VIDEO_LOW = "640x480";
    public static final String VIDEO_MEDIUM = "1280x720";
    public static final String VIDEO_HIGH = "1920x1080";

    // ======= FORMATS DE DATE =======
    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss";

    // ======= QUALITÉ DE CONNEXION =======
    public static final String CONNECTION_EXCELLENT = "excellente";
    public static final String CONNECTION_GOOD = "bonne";
    public static final String CONNECTION_FAIR = "moyenne";
    public static final String CONNECTION_POOR = "faible";
    public static final String CONNECTION_CRITICAL = "critique";

    // Commandes d'authentification
    public static final String LOGIN = "LOGIN";
    public static final String AUTH_OK = "AUTH_OK";
    public static final String AUTH_FAILED = "AUTH_ECHEC";
    public static final String LOGOUT = "LOGOUT";

    // Commandes de gestion des réunions
    public static final String GET_MEETINGS = "GET_MEETINGS";
    public static final String MEETINGS_LIST = "LISTE_REUNIONS";
    public static final String NEWMEETING = "NOUVELLE_REUNION";
    public static final String MEETING_CREATED = "REUNION_CREEE";
    public static final String MEETING_UPDATED = "REUNION_MISE_A_JOUR";
    public static final String MEETING_DELETED = "REUNION_SUPPRIMEE";
    public static final String JOIN = "REJOINDRE";
    public static final String JOIN_OK = "REJOINDRE_OK";
    public static final String JOIN_FAILED = "REJOINDRE_ECHEC";
    public static final String LEAVE = "QUITTER";
    public static final String CLOSE_MEETING = "FERMER_REUNION";
    public static final String MEETING_CLOSED = "REUNION_FERMEE";

    // Commandes de gestion des utilisateurs
    public static final String GET_USERS = "GET_USERS";
    public static final String USERS_LIST = "LISTE_UTILISATEURS";
    public static final String ADD_USER = "AJOUTER_UTILISATEUR";
    public static final String USER_JOINED = "UTILISATEUR_REJOINT";
    public static final String USER_LEFT = "UTILISATEUR_PARTI";

    // Commandes de chat et interactions
    public static final String CHAT_MSG = "MSG_CHAT";
    public static final String REQUEST_SPEAK = "DEMANDE_PAROLE";
    public static final String SPEAK_REQUEST = "REQUETE_PAROLE";
    public static final String ALLOW_SPEAK = "AUTORISER_PAROLE";
    public static final String DENY_SPEAK = "REFUSER_PAROLE";
    public static final String SPEAK_GRANTED = "PAROLE_ACCORDEE";
    public static final String SPEAK_DENIED = "PAROLE_REFUSEE";

    // Commandes de sondages
    public static final String CREATE_POLL = "CREER_SONDAGE";
    public static final String POLL = "SONDAGE";
    public static final String VOTE = "VOTE";
    public static final String POLL_RESULTS = "RESULTATS_SONDAGE";

    // Commandes de réactions
    public static final String REACTION = "REACTION";

    // Réponses d'erreur
    public static final String ERROR = "ERREUR";

    // Autres commandes
    public static final String DISCONNECTED = "DECONNECTE";

    // Séparateurs de protocole
    public static final String FIELD_SEPARATOR = "|";
    public static final String LIST_SEPARATOR = ";;";

    // États de réunion
    public static final String MEETING_STATUS_PLANNED = "Planifiée";
    public static final String MEETING_STATUS_OPEN = "Ouverte";
    public static final String MEETING_STATUS_CLOSED = "Terminée";

    // Types de réunion
    public static final String MEETING_TYPE_STANDARD = "Standard";
    public static final String MEETING_TYPE_PRIVATE = "Privée";
    public static final String MEETING_TYPE_DEMOCRATIC = "Démocratique";

    // Rôles utilisateurs
    public static final String USER_ROLE_USER = "UTILISATEUR";
    public static final String USER_ROLE_ADMIN = "ADMIN";

    // Statuts de participation
    public static final String PARTICIPANT_STATUS_INVITED = "invite";
    public static final String PARTICIPANT_STATUS_ACCEPTED = "accepte";
    public static final String PARTICIPANT_STATUS_DECLINED = "decline";
    public static final String PARTICIPANT_STATUS_JOINED = "rejoint";
}

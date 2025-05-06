package com.bmo.client;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.prefs.Preferences;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * Point d'entrée principal de l'application client BMO
 */
public class MainApp extends Application {
    private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());
    private static final String APP_VERSION = "1.0.0";
    private static final String APP_TITLE = "BMO - Bureau de Médiation en Ligne";

    private Models models;
    private Stage primaryStage;
    private Scene currentScene;

    // Cache pour les chargements des vues FXML
    private final Map<String, Parent> viewCache = new HashMap<>();

    // Constantes pour les chemins des vues FXML
    private static final String LOGIN_VIEW = "/view/LoginView.fxml";
    private static final String ORGANIZER_DASHBOARD_VIEW = "/view/OrganizerDashboardView.fxml";
    private static final String ADMIN_DASHBOARD_VIEW = "/view/AdminDashboardView.fxml";
    private static final String MEETING_VIEW = "/view/MeetingView.fxml";

    private static final String CSS_PATH = "/css/style.css";
    private static final String APP_ICON_PATH = "/images/bmo_logo.png";

    /**
     * Point d'entrée de l'application
     * @param args Arguments de ligne de commande
     */
    public static void main(String[] args) {
        // Configurer le logger avant tout
        configureLogger();

        try {
            // Vérifier que JavaFX est disponible
            Class.forName("javafx.application.Application");

            // Lancer l'application JavaFX
            LOGGER.info("Démarrage de l'application BMO v" + APP_VERSION);
            launch(args);
        } catch (ClassNotFoundException e) {
            LOGGER.severe("Erreur critique: JavaFX runtime est manquant. Vérifiez votre configuration.");
            System.err.println("Erreur critique: JavaFX runtime est manquant. L'application nécessite JavaFX pour fonctionner.");
            System.err.println("Assurez-vous que JavaFX SDK est installé et correctement configuré dans votre IDE ou ligne de commande.");
            System.err.println("Détails techniques: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Méthode d'initialisation appelée avant la méthode start
     */
    @Override
    public void init() throws Exception {
        super.init();

        // Initialiser le modèle
        models = Models.getInstance();

        // Précharger les vues principales (optionnel, pour accélérer les transitions)
        CompletableFuture.runAsync(() -> {
            try {
                preloadView(LOGIN_VIEW);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Impossible de précharger la vue de login", e);
            }
        });

        LOGGER.info("Initialisation de l'application BMO terminée");
    }

    /**
     * Précharge une vue FXML dans le cache
     * @param fxmlPath Le chemin vers le fichier FXML
     * @throws IOException En cas d'erreur de chargement
     */
    private void preloadView(String fxmlPath) throws IOException {
        if (!viewCache.containsKey(fxmlPath)) {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
            Parent root = loader.load();
            viewCache.put(fxmlPath, root);
            LOGGER.fine("Vue préchargée: " + fxmlPath);
        }
    }

    /**
     * Point d'entrée principal de l'application JavaFX
     * @param primaryStage La scène principale
     */
    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;

        // Configurer la scène principale
        configurePrimaryStage(primaryStage);

        // Afficher l'écran de login
        showLoginScreen();

        // Configurer les événements de fermeture
        setupCloseHandlers(primaryStage);

        // Vérifier les mises à jour (exemple d'extension future)
        checkForUpdates();

        // Log d'information sur le système
        logSystemInfo();
    }

    /**
     * Configure la scène principale
     * @param stage La scène à configurer
     */
    private void configurePrimaryStage(Stage stage) {
        stage.setTitle(APP_TITLE);

        // Charger l'icône de l'application si disponible
        try {
            URL iconUrl = getClass().getResource(APP_ICON_PATH);
            if (iconUrl != null) {
                stage.getIcons().add(new Image(iconUrl.toString()));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Impossible de charger l'icône de l'application", e);
        }

        // Définir la taille minimale
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        // Restaurer la taille et la position de la fenêtre à partir des préférences
        restoreWindowState(stage);

        // Centrer la fenêtre sur l'écran si c'est le premier lancement
        stage.centerOnScreen();
    }

    /**
     * Configure les gestionnaires d'événements de fermeture
     * @param stage La scène à configurer
     */
    private void setupCloseHandlers(Stage stage) {
        stage.setOnCloseRequest(event -> {
            // Si une réunion est en cours, demander confirmation
            if (models.getCurrentMeeting() != null) {
                event.consume();
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Quitter la réunion");
                alert.setHeaderText("Une réunion est en cours");
                alert.setContentText("Êtes-vous sûr de vouloir quitter la réunion et fermer l'application ?");

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    // Quitter la réunion proprement avant de fermer l'application
                    models.leaveMeeting();

                    // Sauvegarder l'état de la fenêtre et fermer l'application
                    saveWindowState(stage);
                    cleanupAndExit(stage);
                }
            } else {
                // Sinon, sauvegarder l'état de la fenêtre et fermer normalement
                saveWindowState(stage);
                cleanupAndExit(stage);
            }
        });
    }

    /**
     * Affiche l'écran de login
     */
    public void showLoginScreen() {
        try {
            // Charger la vue de login
            FXMLLoader loader = new FXMLLoader(getClass().getResource(LOGIN_VIEW));
            Parent root = loader.load();

            // Configurer le contrôleur
            Controllers.LoginController controller = loader.getController();
            controller.setStage(primaryStage);

            // Créer la scène
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

            // Enregistrer la scène et l'afficher
            currentScene = scene;
            primaryStage.setScene(scene);
            primaryStage.setTitle(APP_TITLE + " - Connexion");
            primaryStage.show();

            LOGGER.info("Écran de login affiché");

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Impossible de charger l'écran de login", e);
            showErrorAndExit("Erreur critique", "Impossible de charger l'interface utilisateur.", e.getMessage());
        }
    }

    /**
     * Affiche le tableau de bord approprié selon le rôle de l'utilisateur
     */
    public void showDashboard() {
        if (models.getCurrentUser() == null) {
            LOGGER.warning("Tentative d'affichage du tableau de bord sans utilisateur connecté");
            showLoginScreen();
            return;
        }

        try {
            // Déterminer la vue à afficher selon le rôle de l'utilisateur
            String dashboardView = models.getCurrentUser().isAdmin() ?
                    ADMIN_DASHBOARD_VIEW : ORGANIZER_DASHBOARD_VIEW;

            // Charger la vue du tableau de bord
            FXMLLoader loader = new FXMLLoader(getClass().getResource(dashboardView));
            Parent root = loader.load();

            // Configurer le contrôleur
            if (models.getCurrentUser().isAdmin()) {
                Controllers.AdminDashboardController controller = loader.getController();
                controller.setStage(primaryStage);
            } else {
                Controllers.OrganizerDashboardController controller = loader.getController();
                controller.setStage(primaryStage);
            }

            // Créer la scène
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

            // Enregistrer la scène et l'afficher
            currentScene = scene;
            primaryStage.setScene(scene);
            primaryStage.setTitle(APP_TITLE + " - Tableau de bord");

            // Demander la liste des réunions si pas encore reçue
            if (models.getMeetings().isEmpty()) {
                models.requestMeetings();
            }

            LOGGER.info("Tableau de bord affiché pour l'utilisateur " + models.getCurrentUser().getLogin());

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Impossible de charger le tableau de bord", e);
            showError("Erreur", "Impossible de charger le tableau de bord", e.getMessage());
        }
    }

    /**
     * Affiche la vue de réunion
     */
    public void showMeetingView() {
        if (models.getCurrentMeeting() == null) {
            LOGGER.warning("Tentative d'affichage de la vue de réunion sans réunion active");
            showDashboard();
            return;
        }

        try {
            // Charger la vue de réunion
            FXMLLoader loader = new FXMLLoader(getClass().getResource(MEETING_VIEW));
            Parent root = loader.load();

            // Configurer le contrôleur
            Controllers.MeetingController controller = loader.getController();
            controller.setStage(primaryStage);

            // Créer la scène
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource(CSS_PATH).toExternalForm());

            // Enregistrer la scène et l'afficher
            currentScene = scene;
            primaryStage.setScene(scene);
            primaryStage.setTitle(APP_TITLE + " - Réunion: " + models.getCurrentMeeting().getTitle());

            LOGGER.info("Vue de réunion affichée pour la réunion " + models.getCurrentMeeting().getId());

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Impossible de charger la vue de réunion", e);
            showError("Erreur", "Impossible de charger la vue de réunion", e.getMessage());
        }
    }

    /**
     * Affiche une erreur et ferme l'application
     */
    private void showErrorAndExit(String title, String header, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
            Platform.exit();
        });
    }

    /**
     * Affiche une erreur
     */
    private void showError(String title, String header, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    /**
     * Nettoie les ressources et ferme l'application
     */
    private void cleanupAndExit(Stage stage) {
        LOGGER.info("Fermeture de l'application BMO");

        // Fermer la connexion au serveur
        if (models != null) {
            models.disconnect();
            models.shutdown();
        }

        // Fermer la fenêtre et quitter l'application
        stage.close();
        Platform.exit();
    }

    /**
     * Sauvegarde l'état de la fenêtre
     * @param stage La fenêtre dont l'état doit être sauvegardé
     */
    private void saveWindowState(Stage stage) {
        Preferences prefs = Preferences.userNodeForPackage(MainApp.class);
        prefs.putDouble("window.x", stage.getX());
        prefs.putDouble("window.y", stage.getY());
        prefs.putDouble("window.width", stage.getWidth());
        prefs.putDouble("window.height", stage.getHeight());
        prefs.putBoolean("window.maximized", stage.isMaximized());
    }

    /**
     * Restaure l'état de la fenêtre
     * @param stage La fenêtre dont l'état doit être restauré
     */
    private void restoreWindowState(Stage stage) {
        Preferences prefs = Preferences.userNodeForPackage(MainApp.class);

        // Récupérer les valeurs avec des valeurs par défaut raisonnables
        double x = prefs.getDouble("window.x", 100);
        double y = prefs.getDouble("window.y", 100);
        double width = prefs.getDouble("window.width", 1024);
        double height = prefs.getDouble("window.height", 768);
        boolean maximized = prefs.getBoolean("window.maximized", false);

        // Appliquer les valeurs
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setMaximized(maximized);
    }

    /**
     * Configure le logger
     */
    private static void configureLogger() {
        try {
            // Créer le répertoire des logs s'il n'existe pas
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdir();
            }

            // Configurer le handler de fichier
            String logFileName = String.format("logs/bmo-client_%s.log",
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            FileHandler fileHandler = new FileHandler(logFileName);
            fileHandler.setFormatter(new SimpleFormatter());

            Logger rootLogger = Logger.getLogger("");
            rootLogger.addHandler(fileHandler);
            rootLogger.setLevel(Level.FINE); // Niveau global

            // Ajuster les niveaux spécifiques
            Logger.getLogger("com.bmo").setLevel(Level.FINE); // Plus détaillé pour notre app
            Logger.getLogger("javafx").setLevel(Level.INFO); // Moins détaillé pour JavaFX

        } catch (IOException | SecurityException e) {
            System.err.println("Impossible de configurer le logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Vérifie les mises à jour disponibles
     */
    private void checkForUpdates() {
        // Implémentation future - Par exemple avec un service web
        CompletableFuture.runAsync(() -> {
            LOGGER.fine("Vérification des mises à jour en cours...");
            // Simuler un délai
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LOGGER.fine("Aucune mise à jour disponible");
        });
    }

    /**
     * Journalise des informations sur le système
     */
    private void logSystemInfo() {
        LOGGER.info("=== Informations système ===");
        LOGGER.info("Système d'exploitation: " + System.getProperty("os.name") + " " +
                System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        LOGGER.info("Java Version: " + System.getProperty("java.version"));
        LOGGER.info("JavaFX Version: " + System.getProperty("javafx.version", "Non disponible"));
        LOGGER.info("Utilisateur: " + System.getProperty("user.name"));
        LOGGER.info("Dossier utilisateur: " + System.getProperty("user.home"));
        LOGGER.info("Répertoire de travail: " + System.getProperty("user.dir"));
        LOGGER.info("Mémoire disponible: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " Mo");
        LOGGER.info("===========================");
    }

    /**
     * Méthode appelée lors de l'arrêt de l'application
     */
    @Override
    public void stop() throws Exception {
        LOGGER.info("Arrêt de l'application BMO");

        // Nettoyer les ressources
        if (models != null) {
            models.disconnect();
            models.shutdown();
        }

        // Libérer les ressources du cache de vues
        viewCache.clear();

        super.stop();
    }

    /**
     * Obtient l'instance du singleton Models
     * @return L'instance Models
     */
    public Models getModels() {
        return models;
    }

    /**
     * Obtient la scène principale
     * @return La scène principale
     */
    public Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * Vérifie si le compte administrateur existe déjà et le crée si nécessaire
     */
    public void checkAndCreateAdminAccount() {
        // Cette méthode pourrait être appelée après la connexion initiale au serveur
        // pour vérifier si le compte administrateur existe déjà
        CompletableFuture.runAsync(() -> {
            // Logique pour vérifier si le compte admin existe et le créer sinon
            LOGGER.info("Vérification du compte administrateur");

            // Exemple de vérification/création :
            // models.sendMessage("CHECK_ADMIN_EXISTS");
            // La réponse sera traitée par un gestionnaire d'événements dans Models
        });
    }

    /**
     * Recharge la vue courante (utile en cas de changement de thème)
     */
    public void refreshCurrentView() {
        if (models.getCurrentMeeting() != null) {
            showMeetingView();
        } else if (models.getCurrentUser() != null) {
            showDashboard();
        } else {
            showLoginScreen();
        }
    }

    /**
     * Obtient la version de l'application
     * @return La version
     */
    public static String getAppVersion() {
        return APP_VERSION;
    }
}
package com.bmo.client;

import com.bmo.common.ProtocolConstants;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Callback;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Classe regroupant les contrôleurs pour l'application client BMO
 */
public class Controllers {
    private static final Logger LOGGER = Logger.getLogger(Controllers.class.getName());
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /**
     * Contrôleur pour la vue de login
     */
    public static class LoginController {
        @FXML private TextField usernameField;
        @FXML private PasswordField passwordField;
        @FXML private Button loginButton;
        @FXML private CheckBox rememberMeCheckbox;
        @FXML private Label errorLabel;
        @FXML private ProgressIndicator loginProgress;

        private Models models;
        private Stage stage;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();
            errorLabel.setVisible(false);
            loginProgress.setVisible(false);

            // Activer le bouton de login uniquement si les champs requis sont remplis
            loginButton.disableProperty().bind(
                    usernameField.textProperty().isEmpty()
                            .or(passwordField.textProperty().isEmpty())
            );

            // Charger les identifiants mémorisés
            String[] savedCredentials = models.loadSavedCredentials();
            if (savedCredentials != null) {
                usernameField.setText(savedCredentials[0]);
                passwordField.setText(savedCredentials[1]);
                rememberMeCheckbox.setSelected(true);
            }
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère l'événement de login
         */
        @FXML
        private void handleLogin() {
            String username = usernameField.getText().trim();
            String password = passwordField.getText();

            errorLabel.setVisible(false);
            loginButton.setDisable(true);
            loginProgress.setVisible(true);

            // Enregistrer un gestionnaire pour la réponse d'authentification
            models.registerEventHandler(ProtocolConstants.AUTH_OK, this::handleAuthSuccess);
            models.registerEventHandler(ProtocolConstants.AUTH_FAILED, this::handleAuthFailure);
            models.registerEventHandler("DISCONNECTED", this::handleDisconnection);

            // Se connecter au serveur si ce n'est pas déjà fait
            if (!connectToServer()) {
                handleConnectionFailure("Impossible de se connecter au serveur");
                return;
            }

            // Envoyer la requête de login
            models.loginWithRememberOption(username, password, rememberMeCheckbox.isSelected());
        }

        /**
         * Gère le succès de l'authentification
         */
        private void handleAuthSuccess(String payload) {
            Platform.runLater(() -> {
                try {
                    // Déterminer la vue à afficher selon le rôle de l'utilisateur
                    String fxmlPath;
                    if (models.getCurrentUser().isAdmin()) {
                        fxmlPath = "/view/AdminDashboardView.fxml";
                    } else {
                        fxmlPath = "/view/OrganizerDashboardView.fxml";
                    }

                    // Charger la nouvelle vue
                    FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
                    Parent root = loader.load();

                    // Configurer la scène
                    Scene scene = new Scene(root);
                    scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                    // Récupérer et configurer le contrôleur
                    if (models.getCurrentUser().isAdmin()) {
                        AdminDashboardController controller = loader.getController();
                        controller.setStage(stage);
                    } else {
                        OrganizerDashboardController controller = loader.getController();
                        controller.setStage(stage);
                    }

                    // Afficher la nouvelle vue
                    stage.setScene(scene);
                    stage.setTitle("BMO - Bureau de Médiation en Ligne");

                    // Demander la liste des réunions si pas encore reçue
                    if (models.getMeetings().isEmpty()) {
                        models.requestMeetings();
                    }

                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Erreur lors du chargement du dashboard", e);
                    errorLabel.setText("Erreur interne de l'application");
                    errorLabel.setVisible(true);
                    loginButton.setDisable(false);
                    loginProgress.setVisible(false);
                } finally {
                    // Désenregistrer les gestionnaires
                    models.unregisterEventHandler(ProtocolConstants.AUTH_OK, this::handleAuthSuccess);
                    models.unregisterEventHandler(ProtocolConstants.AUTH_FAILED, this::handleAuthFailure);
                }
            });
        }

        /**
         * Gère l'échec de l'authentification
         */
        private void handleAuthFailure(String payload) {
            Platform.runLater(() -> {
                errorLabel.setText("Identifiants invalides");
                errorLabel.setVisible(true);
                loginButton.setDisable(false);
                loginProgress.setVisible(false);

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler(ProtocolConstants.AUTH_OK, this::handleAuthSuccess);
                models.unregisterEventHandler(ProtocolConstants.AUTH_FAILED, this::handleAuthFailure);
            });
        }

        /**
         * Gère une erreur de connexion
         */
        private void handleConnectionFailure(String message) {
            Platform.runLater(() -> {
                errorLabel.setText(message);
                errorLabel.setVisible(true);
                loginButton.setDisable(false);
                loginProgress.setVisible(false);

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler(ProtocolConstants.AUTH_OK, this::handleAuthSuccess);
                models.unregisterEventHandler(ProtocolConstants.AUTH_FAILED, this::handleAuthFailure);
            });
        }

        /**
         * Gère une déconnexion
         */
        private void handleDisconnection(String payload) {
            Platform.runLater(() -> {
                errorLabel.setText("Connexion perdue");
                errorLabel.setVisible(true);
                loginButton.setDisable(false);
                loginProgress.setVisible(false);

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler("DISCONNECTED", this::handleDisconnection);
            });
        }

        /**
         * Établit la connexion au serveur
         * @return true si la connexion est établie, false sinon
         */
        private boolean connectToServer() {
            // Récupération des paramètres de connexion
            String host = ConfigUtils.retrieveServerHost();
            int port = ConfigUtils.retrieveServerPort();

            // Se connecter au serveur
            return models.connect(host, port);
        }

        /**
         * Récupère l'adresse du serveur depuis les préférences ou utilise localhost par défaut
         */
        private String retrieveServerHost() {
            // À implémenter - pour le moment, on utilise localhost
            return "localhost";
        }

        /**
         * Récupère le port du serveur depuis les préférences ou utilise 12345 par défaut
         */
        private int retrieveServerPort() {
            // À implémenter - pour le moment, on utilise 12345
            return 12345;
        }

        /**
         * Gère l'événement de création de compte
         */
        @FXML
        private void handleCreateAccount() {
            // À implémenter si nécessaire - pour le moment, on ne fait rien
            errorLabel.setText("Contactez votre administrateur pour créer un compte");
            errorLabel.setVisible(true);
        }

        /**
         * Gère l'événement de mot de passe oublié
         */
        @FXML
        private void handleForgotPassword() {
            // À implémenter si nécessaire - pour le moment, on ne fait rien
            errorLabel.setText("Contactez votre administrateur pour réinitialiser votre mot de passe");
            errorLabel.setVisible(true);
        }
    }

    /**
     * Contrôleur de base pour les dashboards
     */
    private static abstract class BaseDashboardController {
        @FXML protected Label userNameLabel;
        @FXML protected Label userRoleLabel;
        @FXML protected TableView<Models.Meeting> meetingsTable;
        @FXML protected TextField searchField;
        @FXML protected ComboBox<String> filterStatusComboBox;
        @FXML protected Button joinMeetingButton;
        @FXML protected Button createMeetingButton;
        @FXML protected Label statusLabel;
        @FXML protected MenuButton userMenu;

        protected Models models;
        protected Stage stage;
        protected FilteredList<Models.Meeting> filteredMeetings;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer le texte d'utilisateur
            if (models.getCurrentUser() != null) {
                userNameLabel.setText(models.getCurrentUser().getName());
                userRoleLabel.setText(models.getCurrentUser().isAdmin() ? "Administrateur" : "Utilisateur");
            }

            // Configurer le menu utilisateur
            MenuItem profileMenuItem = new MenuItem("Profil");
            profileMenuItem.setOnAction(e -> showUserProfile());

            MenuItem logoutMenuItem = new MenuItem("Déconnexion");
            logoutMenuItem.setOnAction(e -> handleLogout());

            userMenu.getItems().addAll(profileMenuItem, new SeparatorMenuItem(), logoutMenuItem);

            // Configurer la table des réunions
            setupMeetingsTable();

            // Configurer les filtres
            setupFilters();

            // Configurer les boutons
            joinMeetingButton.setDisable(true);
            meetingsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) ->
                    joinMeetingButton.setDisable(newVal == null));

            // Enregistrer les gestionnaires d'événements
            models.registerEventHandler(ProtocolConstants.MEETING_CREATED, this::handleMeetingCreated);
            models.registerEventHandler(ProtocolConstants.MEETING_UPDATED, this::handleMeetingUpdated);
            models.registerEventHandler(ProtocolConstants.MEETING_DELETED, this::handleMeetingDeleted);
            models.registerEventHandler("DISCONNECTED", this::handleDisconnection);

            // Demander la liste des réunions
            models.requestMeetings();
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;

            // Configurer la fermeture de l'application
            stage.setOnCloseRequest(e -> {
                models.disconnect();
                Platform.exit();
            });
        }

        /**
         * Configure la table des réunions
         */
        protected void setupMeetingsTable() {
            // Créer les colonnes de la table
            TableColumn<Models.Meeting, String> titleCol = new TableColumn<>("Titre");
            titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));
            titleCol.setPrefWidth(200);

            TableColumn<Models.Meeting, LocalDateTime> dateCol = new TableColumn<>("Date");
            dateCol.setCellValueFactory(new PropertyValueFactory<>("datetime"));
            dateCol.setCellFactory(column -> new TableCell<Models.Meeting, LocalDateTime>() {
                @Override
                protected void updateItem(LocalDateTime item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item == null || empty) {
                        setText(null);
                    } else {
                        setText(DATE_TIME_FORMATTER.format(item));
                    }
                }
            });
            dateCol.setPrefWidth(150);

            TableColumn<Models.Meeting, Integer> durationCol = new TableColumn<>("Durée");
            durationCol.setCellValueFactory(new PropertyValueFactory<>("duration"));
            durationCol.setCellFactory(column -> new TableCell<Models.Meeting, Integer>() {
                @Override
                protected void updateItem(Integer item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item == null || empty) {
                        setText(null);
                    } else {
                        setText(item + " min");
                    }
                }
            });
            durationCol.setPrefWidth(80);

            TableColumn<Models.Meeting, String> typeCol = new TableColumn<>("Type");
            typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
            typeCol.setPrefWidth(100);

            TableColumn<Models.Meeting, String> statusCol = new TableColumn<>("Statut");
            statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
            statusCol.setCellFactory(column -> new TableCell<Models.Meeting, String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item == null || empty) {
                        setText(null);
                        setStyle("");
                    } else {
                        setText(item);
                        switch (item) {
                            case "Planifiée":
                                setStyle("-fx-text-fill: blue;");
                                break;
                            case "Ouverte":
                                setStyle("-fx-text-fill: green;");
                                break;
                            case "Terminée":
                                setStyle("-fx-text-fill: gray;");
                                break;
                            default:
                                setStyle("");
                                break;
                        }
                    }
                }
            });
            statusCol.setPrefWidth(100);

            TableColumn<Models.Meeting, String> organizerCol = new TableColumn<>("Organisateur");
            organizerCol.setCellValueFactory(cellData -> {
                Models.Meeting meeting = cellData.getValue();
                return new SimpleStringProperty(meeting.getOrganizerName() != null ?
                        meeting.getOrganizerName() : "ID: " + meeting.getOrganizerId());
            });
            organizerCol.setPrefWidth(150);

            // Ajouter les colonnes à la table
            meetingsTable.getColumns().addAll(titleCol, dateCol, durationCol, typeCol, statusCol, organizerCol);

            // Filtrer la liste des réunions
            filteredMeetings = new FilteredList<>(models.getMeetings(), p -> true);
            SortedList<Models.Meeting> sortedMeetings = new SortedList<>(filteredMeetings);
            sortedMeetings.comparatorProperty().bind(meetingsTable.comparatorProperty());
            meetingsTable.setItems(sortedMeetings);

            // Ajouter un listener de double-clic pour rejoindre une réunion
            meetingsTable.setRowFactory(tv -> {
                TableRow<Models.Meeting> row = new TableRow<>();
                row.setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !row.isEmpty()) {
                        handleJoinMeeting();
                    }
                });
                return row;
            });
        }

        /**
         * Configure les filtres de recherche et de statut
         */
        protected void setupFilters() {
            // Configurer le combo box de statut
            filterStatusComboBox.getItems().addAll("Tous", "Planifiées", "Ouvertes", "Terminées");
            filterStatusComboBox.setValue("Tous");
            filterStatusComboBox.valueProperty().addListener((obs, oldVal, newVal) -> updateFilters());

            // Configurer le champ de recherche
            searchField.textProperty().addListener((obs, oldVal, newVal) -> updateFilters());
        }

        /**
         * Met à jour les filtres de la table
         */
        protected void updateFilters() {
            String searchText = searchField.getText().toLowerCase();
            String statusFilter = filterStatusComboBox.getValue();

            filteredMeetings.setPredicate(meeting -> {
                boolean matchesSearch = searchText.isEmpty() ||
                        meeting.getTitle().toLowerCase().contains(searchText) ||
                        (meeting.getAgenda() != null && meeting.getAgenda().toLowerCase().contains(searchText)) ||
                        (meeting.getOrganizerName() != null && meeting.getOrganizerName().toLowerCase().contains(searchText));

                boolean matchesStatus = "Tous".equals(statusFilter) ||
                        (statusFilter.equals("Planifiées") && meeting.getStatus().equals("Planifiée")) ||
                        (statusFilter.equals("Ouvertes") && meeting.getStatus().equals("Ouverte")) ||
                        (statusFilter.equals("Terminées") && meeting.getStatus().equals("Terminée"));

                return matchesSearch && matchesStatus;
            });
        }

        /**
         * Affiche le profil utilisateur
         */
        protected void showUserProfile() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/UserProfileView.fxml"));
                Parent root = loader.load();

                Stage profileStage = new Stage();
                profileStage.initModality(Modality.APPLICATION_MODAL);
                profileStage.initOwner(stage);

                UserProfileController controller = loader.getController();
                controller.setUser(models.getCurrentUser());
                controller.setStage(profileStage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                profileStage.setScene(scene);
                profileStage.setTitle("Profil utilisateur");
                profileStage.showAndWait();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement du profil utilisateur", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                        "Impossible de charger la vue du profil utilisateur.");
            }
        }

        /**
         * Gère la déconnexion
         */
        protected void handleLogout() {
            models.logout();

            // Désenregistrer les gestionnaires d'événements
            models.unregisterEventHandler(ProtocolConstants.MEETING_CREATED, this::handleMeetingCreated);
            models.unregisterEventHandler(ProtocolConstants.MEETING_UPDATED, this::handleMeetingUpdated);
            models.unregisterEventHandler(ProtocolConstants.MEETING_DELETED, this::handleMeetingDeleted);
            models.unregisterEventHandler("DISCONNECTED", this::handleDisconnection);

            // Retourner à l'écran de login
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/LoginView.fxml"));
                Parent root = loader.load();

                LoginController controller = loader.getController();
                controller.setStage(stage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                stage.setScene(scene);
                stage.setTitle("BMO - Connexion");

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de login", e);
                Platform.exit();
            }
        }

        /**
         * Gère la création d'une réunion
         */
        @FXML
        protected void handleCreateMeeting() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/CreateMeetingView.fxml"));
                Parent root = loader.load();

                Stage createStage = new Stage();
                createStage.initModality(Modality.APPLICATION_MODAL);
                createStage.initOwner(stage);

                CreateMeetingController controller = loader.getController();
                controller.setStage(createStage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                createStage.setScene(scene);
                createStage.setTitle("Créer une réunion");
                createStage.showAndWait();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de création de réunion", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                        "Impossible de charger la vue de création de réunion.");
            }
        }

        /**
         * Gère la participation à une réunion
         */
        @FXML
        protected void handleJoinMeeting() {
            Models.Meeting selectedMeeting = meetingsTable.getSelectionModel().getSelectedItem();
            if (selectedMeeting == null) return;

            // Vérifier si la réunion peut être rejointe
            if ("Terminée".equals(selectedMeeting.getStatus())) {
                showAlert(Alert.AlertType.WARNING, "Réunion terminée", "Impossible de rejoindre",
                        "Cette réunion est déjà terminée.");
                return;
            }

            // Enregistrer un gestionnaire pour la réponse de JOIN
            models.registerEventHandler(ProtocolConstants.JOIN_OK, this::handleJoinOk);
            models.registerEventHandler(ProtocolConstants.JOIN_FAILED, this::handleJoinFailed);

            // Envoyer la requête pour rejoindre la réunion
            models.joinMeeting(selectedMeeting.getId());

            // Afficher un indicateur de chargement
            statusLabel.setText("Connexion à la réunion...");
        }

        /**
         * Gère le succès de la connexion à une réunion
         */
        private void handleJoinOk(String payload) {
            Platform.runLater(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MeetingView.fxml"));
                    Parent root = loader.load();

                    MeetingController controller = loader.getController();
                    controller.setStage(stage);

                    Scene scene = new Scene(root);
                    scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                    stage.setScene(scene);
                    stage.setTitle("BMO - Réunion: " + models.getCurrentMeeting().getTitle());

                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de réunion", e);
                    showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                            "Impossible de charger la vue de réunion.");
                    statusLabel.setText("");
                } finally {
                    // Désenregistrer les gestionnaires
                    models.unregisterEventHandler(ProtocolConstants.JOIN_OK, this::handleJoinOk);
                    models.unregisterEventHandler(ProtocolConstants.JOIN_FAILED, this::handleJoinFailed);
                }
            });
        }

        /**
         * Gère l'échec de la connexion à une réunion
         */
        private void handleJoinFailed(String payload) {
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.ERROR, "Erreur de connexion", "Impossible de rejoindre la réunion", payload);
                statusLabel.setText("");

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler(ProtocolConstants.JOIN_OK, this::handleJoinOk);
                models.unregisterEventHandler(ProtocolConstants.JOIN_FAILED, this::handleJoinFailed);
            });
        }

        /**
         * Gère une déconnexion
         */
        private void handleDisconnection(String payload) {
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.ERROR, "Déconnexion", "Connexion perdue",
                        "La connexion au serveur a été perdue. Veuillez vous reconnecter.");
                handleLogout();
            });
        }

        /**
         * Gère la création d'une nouvelle réunion
         */
        private void handleMeetingCreated(String payload) {
            Platform.runLater(() -> {
                statusLabel.setText("Nouvelle réunion créée");
                // Les données sont déjà mises à jour dans le modèle
            });
        }

        /**
         * Gère la mise à jour d'une réunion
         */
        private void handleMeetingUpdated(String payload) {
            Platform.runLater(() -> {
                statusLabel.setText("Réunion mise à jour");
                // Les données sont déjà mises à jour dans le modèle
            });
        }

        /**
         * Gère la suppression d'une réunion
         */
        private void handleMeetingDeleted(String payload) {
            Platform.runLater(() -> {
                statusLabel.setText("Réunion supprimée");
                // Les données sont déjà mises à jour dans le modèle
            });
        }

        /**
         * Affiche une alerte
         */
        protected void showAlert(Alert.AlertType type, String title, String header, String content) {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
        }
    }

    /**
     * Contrôleur pour le dashboard organisateur
     */
    public static class OrganizerDashboardController extends BaseDashboardController {
        @FXML private Button editMeetingButton;
        @FXML private Button deleteMeetingButton;
        @FXML private TabPane tabPane;
        @FXML private Tab myMeetingsTab;
        @FXML private Tab allMeetingsTab;

        /**
         * Initialise le contrôleur
         */
        @Override
        @FXML
        public void initialize() {
            super.initialize();

            // Configurer les boutons supplémentaires
            editMeetingButton.setDisable(true);
            deleteMeetingButton.setDisable(true);

            meetingsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                boolean isSelected = newVal != null;
                boolean isOrganizer = isSelected &&
                        models.getCurrentUser().getId() == newVal.getOrganizerId();
                boolean isTerminated = isSelected && "Terminée".equals(newVal.getStatus());

                editMeetingButton.setDisable(!isSelected || !isOrganizer || isTerminated);
                deleteMeetingButton.setDisable(!isSelected || !isOrganizer || isTerminated);
                joinMeetingButton.setDisable(!isSelected || isTerminated);
            });

            // Configurer le filtre pour les onglets
            tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (myMeetingsTab.equals(newTab)) {
                    // Filtrer pour ne montrer que les réunions organisées par l'utilisateur courant
                    filteredMeetings.setPredicate(meeting ->
                            meeting.getOrganizerId() == models.getCurrentUser().getId());
                } else if (allMeetingsTab.equals(newTab)) {
                    // Montrer toutes les réunions
                    updateFilters();
                }
            });
        }

        /**
         * Gère la modification d'une réunion
         */
        @FXML
        private void handleEditMeeting() {
            Models.Meeting selectedMeeting = meetingsTable.getSelectionModel().getSelectedItem();
            if (selectedMeeting == null) return;

            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/EditMeetingView.fxml"));
                Parent root = loader.load();

                Stage editStage = new Stage();
                editStage.initModality(Modality.APPLICATION_MODAL);
                editStage.initOwner(stage);

                EditMeetingController controller = loader.getController();
                controller.setMeeting(selectedMeeting);
                controller.setStage(editStage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                editStage.setScene(scene);
                editStage.setTitle("Modifier une réunion");
                editStage.showAndWait();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue d'édition de réunion", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                        "Impossible de charger la vue d'édition de réunion.");
            }
        }

        /**
         * Gère la suppression d'une réunion
         */
        @FXML
        private void handleDeleteMeeting() {
            Models.Meeting selectedMeeting = meetingsTable.getSelectionModel().getSelectedItem();
            if (selectedMeeting == null) return;

            Alert confirmationAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmationAlert.setTitle("Confirmation de suppression");
            confirmationAlert.setHeaderText("Supprimer la réunion");
            confirmationAlert.setContentText("Êtes-vous sûr de vouloir supprimer cette réunion ?\n" +
                    "Titre: " + selectedMeeting.getTitle() + "\n" +
                    "Date: " + DATE_TIME_FORMATTER.format(selectedMeeting.getDatetime()));

            Optional<ButtonType> result = confirmationAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Envoyer la demande de suppression au serveur
                models.sendMessage("DELETE_MEETING|" + selectedMeeting.getId());
                statusLabel.setText("Demande de suppression envoyée...");
            }
        }
    }

    /**
     * Contrôleur pour le dashboard administrateur
     */
    public static class AdminDashboardController extends OrganizerDashboardController {
        @FXML private TabPane mainTabPane;
        @FXML private Tab usersTab;
        @FXML private Tab settingsTab;
        @FXML private TableView<Models.User> usersTable;
        @FXML private Button addUserButton;
        @FXML private Button editUserButton;
        @FXML private Button deleteUserButton;
        @FXML private TextField searchUserField;
        @FXML private Label serverStatusLabel;
        @FXML private Label dbStatusLabel;
        @FXML private TextField serverHostField;
        @FXML private TextField serverPortField;
        @FXML private CheckBox enableTlsCheckbox;
        @FXML private Button applySettingsButton;
        @FXML private Button restartServerButton;
        @FXML private LineChart<Number, Number> bandwidthChart;

        private FilteredList<Models.User> filteredUsers;
        private XYChart.Series<Number, Number> uploadSeries;
        private XYChart.Series<Number, Number> downloadSeries;
        private Timer bandwidthUpdateTimer;

        /**
         * Initialise le contrôleur
         */
        @Override
        @FXML
        public void initialize() {
            super.initialize();

            // Initialiser la table des utilisateurs
            setupUsersTable();

            // Configurer les boutons utilisateurs
            editUserButton.setDisable(true);
            deleteUserButton.setDisable(true);

            usersTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                boolean hasSelection = newVal != null;
                editUserButton.setDisable(!hasSelection);
                deleteUserButton.setDisable(!hasSelection);
            });

            // Configurer la recherche utilisateur
            searchUserField.textProperty().addListener((obs, oldVal, newVal) -> {
                filteredUsers.setPredicate(user -> {
                    if (newVal == null || newVal.isEmpty())
                        return true;
                    
                    String searchText = newVal.toLowerCase();
                    return user.getLogin().toLowerCase().contains(searchText) ||
                           user.getName().toLowerCase().contains(searchText);
                });
            });

            // Configurer les paramètres serveur
            serverHostField.setText(ConfigUtils.retrieveServerHost());
            serverPortField.setText(String.valueOf(ConfigUtils.retrieveServerPort()));
            enableTlsCheckbox.setSelected(false); // À remplacer par la valeur réelle

            // Configurer le graphique de bande passante
            setupBandwidthChart();

            // Démarrer la mise à jour périodique des statistiques
            startBandwidthMonitoring();

            // Demander la liste des utilisateurs
            models.requestUsers();

            // Enregistrer les gestionnaires d'événements pour les utilisateurs
            models.registerEventHandler(ProtocolConstants.USERS_LIST, this::handleUsersList);
            
            // État initial des statuts
            updateServerStatus();
        }

        /**
         * Met à jour l'affichage du statut du serveur et de la base de données
         */
        private void updateServerStatus() {
            boolean isConnected = models.isConnected();
            serverStatusLabel.setText(isConnected ? "Connecté" : "Déconnecté");
            serverStatusLabel.setStyle(isConnected ? "-fx-text-fill: green;" : "-fx-text-fill: red;");
            
            // Pour la base de données, nous pouvons vérifier si nous avons des données utilisateur
            boolean dbOk = !models.getUsers().isEmpty();
            dbStatusLabel.setText(dbOk ? "Opérationnelle" : "Non connectée");
            dbStatusLabel.setStyle(dbOk ? "-fx-text-fill: green;" : "-fx-text-fill: orange;");
        }

        /**
         * Configure la table des utilisateurs
         */
        private void setupUsersTable() {
            // Créer les colonnes de la table
            TableColumn<Models.User, Integer> idCol = new TableColumn<>("ID");
            idCol.setCellValueFactory(new PropertyValueFactory<>("id"));
            idCol.setPrefWidth(50);

            TableColumn<Models.User, String> loginCol = new TableColumn<>("Identifiant");
            loginCol.setCellValueFactory(new PropertyValueFactory<>("login"));
            loginCol.setPrefWidth(150);

            TableColumn<Models.User, String> nameCol = new TableColumn<>("Nom");
            nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
            nameCol.setPrefWidth(200);

            TableColumn<Models.User, Boolean> roleCol = new TableColumn<>("Rôle");
            roleCol.setCellValueFactory(new PropertyValueFactory<>("admin"));
            roleCol.setCellFactory(column -> new TableCell<Models.User, Boolean>() {
                @Override
                protected void updateItem(Boolean item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item ? "Administrateur" : "Utilisateur");
                    }
                }
            });
            roleCol.setPrefWidth(150);
            
            // Colonne pour la date de création (si disponible dans le modèle)
            TableColumn<Models.User, LocalDateTime> creationDateCol = new TableColumn<>("Date de création");
            creationDateCol.setCellValueFactory(new PropertyValueFactory<>("creationDate"));
            creationDateCol.setCellFactory(column -> new TableCell<Models.User, LocalDateTime>() {
                @Override
                protected void updateItem(LocalDateTime item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(DATE_FORMATTER.format(item));
                    }
                }
            });
            creationDateCol.setPrefWidth(150);

            // Ajouter les colonnes à la table
            usersTable.getColumns().addAll(idCol, loginCol, nameCol, roleCol, creationDateCol);

            // Filtrer la liste des utilisateurs
            filteredUsers = new FilteredList<>(models.getUsers(), p -> true);
            SortedList<Models.User> sortedUsers = new SortedList<>(filteredUsers);
            sortedUsers.comparatorProperty().bind(usersTable.comparatorProperty());
            usersTable.setItems(sortedUsers);
        }

        /**
         * Configure le graphique de bande passante
         */
        private void setupBandwidthChart() {
            // Configurer les axes
            NumberAxis xAxis = (NumberAxis) bandwidthChart.getXAxis();
            xAxis.setLabel("Temps (s)");
            xAxis.setTickLabelFormatter(new StringConverter<Number>() {
                @Override
                public String toString(Number object) {
                    return String.format("%.0f", object);
                }

                @Override
                public Number fromString(String string) {
                    return Double.parseDouble(string);
                }
            });

            NumberAxis yAxis = (NumberAxis) bandwidthChart.getYAxis();
            yAxis.setLabel("KB/s");

            // Créer les séries
            uploadSeries = new XYChart.Series<>();
            uploadSeries.setName("Upload");

            downloadSeries = new XYChart.Series<>();
            downloadSeries.setName("Download");

            // Ajouter les séries au graphique
            bandwidthChart.getData().addAll(uploadSeries, downloadSeries);

            // Limiter le nombre de points sur l'axe x
            xAxis.setAutoRanging(false);
            xAxis.setLowerBound(0);
            xAxis.setUpperBound(60);
            xAxis.setTickUnit(10);
        }

        /**
         * Démarre la surveillance de la bande passante
         */
        private void startBandwidthMonitoring() {
            // Utiliser le BandwidthMonitor de l'application pour obtenir les statistiques
            BandwidthMonitor monitor = models.getBandwidthMonitor();
            if (monitor != null) {
                monitor.setSpeedUpdateCallback((download, upload) -> {
                    Platform.runLater(() -> updateBandwidthChart(download / 1024, upload / 1024));
                });
            }
            
            // Actualiser toutes les secondes
            bandwidthUpdateTimer = new Timer(true);
            bandwidthUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                private int seconds = 0;
                
                @Override
                public void run() {
                    seconds++;
                    // Si nous n'avons pas de moniteur, générer des données aléatoires pour la démo
                    if (models.getBandwidthMonitor() == null) {
                        double download = Math.random() * 100;
                        double upload = Math.random() * 20;
                        Platform.runLater(() -> updateBandwidthChart(download, upload));
                    }
                }
            }, 0, 1000);
        }
        
        /**
         * Met à jour le graphique de bande passante
         */
        private void updateBandwidthChart(double downloadKBps, double uploadKBps) {
            // Ajouter les points aux séries
            final int MAX_DATA_POINTS = 60;
            
            // Ajouter un nouveau point
            double currentTime = downloadSeries.getData().size();
            downloadSeries.getData().add(new XYChart.Data<>(currentTime, downloadKBps));
            uploadSeries.getData().add(new XYChart.Data<>(currentTime, uploadKBps));
            
            // Limiter le nombre de points
            if (downloadSeries.getData().size() > MAX_DATA_POINTS) {
                downloadSeries.getData().remove(0);
                uploadSeries.getData().remove(0);
                
                // Ajuster les valeurs X pour qu'elles commencent toujours à 0
                for (int i = 0; i < downloadSeries.getData().size(); i++) {
                    downloadSeries.getData().get(i).setXValue(i);
                    uploadSeries.getData().get(i).setXValue(i);
                }
            }
        }

        /**
         * Arrête la surveillance de la bande passante
         */
        private void stopBandwidthMonitoring() {
            if (bandwidthUpdateTimer != null) {
                bandwidthUpdateTimer.cancel();
                bandwidthUpdateTimer = null;
            }
        }

        /**
         * Gère la réception de la liste des utilisateurs
         */
        private void handleUsersList(String payload) {
            // Les données sont déjà mises à jour dans le modèle
            Platform.runLater(() -> {
                statusLabel.setText("Liste des utilisateurs mise à jour");
                updateServerStatus();
            });
        }

        /**
         * Gère l'ajout d'un utilisateur
         */
        @FXML
        public void handleAddUser() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/AddUserView.fxml"));
                Parent root = loader.load();
                AddUserController controller = loader.getController();
                
                Stage dialog = new Stage();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.initOwner(stage);
                dialog.setTitle("Ajouter un utilisateur");
                dialog.setScene(new Scene(root));
                controller.setStage(dialog);
                
                dialog.showAndWait();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Impossible de charger la vue d'ajout d'utilisateur", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Impossible de charger la vue d'ajout d'utilisateur", e.getMessage());
            }
        }

        /**
         * Gère la modification d'un utilisateur
         */
        @FXML
        public void handleEditUser() {
            Models.User selectedUser = usersTable.getSelectionModel().getSelectedItem();
            if (selectedUser == null)
                return;

            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/EditUserView.fxml"));
                Parent root = loader.load();
                EditUserController controller = loader.getController();
                controller.setUser(selectedUser);
                
                Stage dialog = new Stage();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.initOwner(stage);
                dialog.setTitle("Modifier l'utilisateur");
                dialog.setScene(new Scene(root));
                controller.setStage(dialog);
                
                dialog.showAndWait();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Impossible de charger la vue de modification d'utilisateur", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Impossible de charger la vue de modification d'utilisateur", e.getMessage());
            }
        }

        /**
         * Gère la suppression d'un utilisateur
         */
        @FXML
        public void handleDeleteUser() {
            Models.User selectedUser = usersTable.getSelectionModel().getSelectedItem();
            if (selectedUser == null)
                return;

            // Vérifier que l'utilisateur ne supprime pas son propre compte
            if (selectedUser.getId() == models.getCurrentUser().getId()) {
                showAlert(Alert.AlertType.WARNING, "Attention", "Action non autorisée", 
                          "Vous ne pouvez pas supprimer votre propre compte.");
                return;
            }

            Alert confirmationAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmationAlert.setTitle("Confirmation de suppression");
            confirmationAlert.setHeaderText("Supprimer l'utilisateur");
            confirmationAlert.setContentText("Êtes-vous sûr de vouloir supprimer cet utilisateur ?\n" +
                    "Login: " + selectedUser.getLogin() + "\n" +
                    "Nom: " + selectedUser.getName());

            Optional<ButtonType> result = confirmationAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Envoyer la commande de suppression
                models.deleteUser(selectedUser.getId());
                statusLabel.setText("Suppression de l'utilisateur en cours...");
            }
        }

        /**
         * Gère l'application des paramètres serveur
         */
        @FXML
        public void handleApplySettings() {
            String host = serverHostField.getText().trim();
            String portText = serverPortField.getText().trim();
            boolean enableTls = enableTlsCheckbox.isSelected();

            if (host.isEmpty()) {
                showAlert(Alert.AlertType.ERROR, "Erreur", "Adresse serveur invalide", 
                          "Veuillez saisir une adresse serveur valide.");
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    showAlert(Alert.AlertType.ERROR, "Erreur", "Port invalide", 
                              "Le port doit être entre 1 et 65535.");
                    return;
                }
            } catch (NumberFormatException e) {
                showAlert(Alert.AlertType.ERROR, "Erreur", "Port invalide", 
                          "Veuillez saisir un port valide.");
                return;
            }

            // Sauvegarder les paramètres localement
            ConfigUtils.saveServerHost(host);
            ConfigUtils.saveServerPort(port);

            // Envoyer les paramètres au serveur
            StringBuilder command = new StringBuilder("SET_SERVER_SETTINGS");
            command.append("|host=").append(host);
            command.append("|port=").append(port);
            command.append("|tls=").append(enableTls ? "true" : "false");

            models.sendMessage(command.toString());
            statusLabel.setText("Application des paramètres serveur...");
        }

        /**
         * Gère le redémarrage du serveur
         */
        @FXML
        public void handleRestartServer() {
            Alert confirmationAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmationAlert.setTitle("Redémarrage serveur");
            confirmationAlert.setHeaderText("Redémarrer le serveur");
            confirmationAlert.setContentText("Êtes-vous sûr de vouloir redémarrer le serveur ?\n" +
                    "Tous les utilisateurs seront déconnectés.");

            Optional<ButtonType> result = confirmationAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Envoyer la commande de redémarrage
                models.sendMessage("RESTART_SERVER");
                statusLabel.setText("Redémarrage du serveur en cours...");
                
                // Afficher une alerte pour informer l'utilisateur
                showAlert(Alert.AlertType.INFORMATION, "Redémarrage", "Redémarrage du serveur", 
                          "Le serveur va redémarrer. Vous serez déconnecté et devrez vous reconnecter dans quelques instants.");
            }
        }

        /**
         * Libère les ressources lors de la fermeture
         */
        @Override
        public void setStage(Stage stage) {
            super.setStage(stage);

            // Arrêter la surveillance de la bande passante lors de la fermeture
            stage.setOnCloseRequest(e -> {
                stopBandwidthMonitoring();
            });
        }
    }

    /**
     * Contrôleur pour la vue de réunion
     */
    public static class MeetingController {
        @FXML private Label meetingTitleLabel;
        @FXML private Label meetingStatusLabel;
        @FXML private Label meetingDateLabel;
        @FXML private Label meetingOrganizerLabel;
        @FXML private ListView<Models.User> participantsListView;
        @FXML private ListView<Models.Message> chatListView;
        @FXML private TextArea messageInput;
        @FXML private Button sendMessageButton;
        @FXML private Button requestToSpeakButton;
        @FXML private Button shareScreenButton;
        @FXML private Button createPollButton;
        @FXML private Button closeMeetingButton;
        @FXML private Button leaveMeetingButton;
        @FXML private Label statusLabel;
        @FXML private BorderPane videoContainer;
        @FXML private ListView<String> speakRequestsListView;
        @FXML private HBox speakRequestsContainer;
        @FXML private TabPane tabPane;
        @FXML private Tab chatTab;
        @FXML private Tab pollsTab;
        @FXML private VBox pollsContainer;

        private Models models;
        private Stage stage;
        private boolean isSpeaking = false;
        private boolean isScreenSharing = false;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les informations de réunion
            Models.Meeting meeting = models.getCurrentMeeting();
            if (meeting != null) {
                meetingTitleLabel.setText(meeting.getTitle());
                meetingStatusLabel.setText(meeting.getStatus());
                meetingDateLabel.setText(DATE_TIME_FORMATTER.format(meeting.getDatetime()));
                meetingOrganizerLabel.setText(meeting.getOrganizerName() != null ?
                        meeting.getOrganizerName() : "Organisateur ID: " + meeting.getOrganizerId());
            }

            // Configurer la liste des participants
            participantsListView.setItems(models.getCurrentMeetingParticipants());
            participantsListView.setCellFactory(lv -> new ListCell<Models.User>() {
                @Override
                protected void updateItem(Models.User user, boolean empty) {
                    super.updateItem(user, empty);
                    if (empty || user == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(user.getName() + (user.isAdmin() ? " (Admin)" : ""));

                        // Ajouter une icône pour l'organisateur
                        if (meeting != null && user.getId() == meeting.getOrganizerId()) {
                            setGraphic(new ImageView(new Image(getClass().getResourceAsStream("/images/organizer_icon.png"))));
                        } else {
                            setGraphic(null);
                        }
                    }
                }
            });

            // Configurer la liste des messages
            chatListView.setItems(models.getCurrentMeetingMessages());
            chatListView.setCellFactory(lv -> new ListCell<Models.Message>() {
                @Override
                protected void updateItem(Models.Message message, boolean empty) {
                    super.updateItem(message, empty);
                    if (empty || message == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        String time = TIME_FORMATTER.format(message.getTimestamp());
                        setText(String.format("[%s] %s: %s", time, message.getUserName(), message.getContent()));
                    }
                }
            });

            // Configurer l'envoi de message
            sendMessageButton.setDisable(true);
            messageInput.textProperty().addListener((obs, oldVal, newVal) ->
                    sendMessageButton.setDisable(newVal.trim().isEmpty()));

            // Configurer les demandes de parole
            speakRequestsContainer.setVisible(isMeetingOrganizer());
            speakRequestsContainer.setManaged(isMeetingOrganizer());

            // Configurer les boutons selon le rôle
            createPollButton.setVisible(isMeetingOrganizer());
            createPollButton.setManaged(isMeetingOrganizer());
            closeMeetingButton.setVisible(isMeetingOrganizer());
            closeMeetingButton.setManaged(isMeetingOrganizer());

            // Enregistrer les gestionnaires d'événements
            models.registerEventHandler(ProtocolConstants.CHAT_MSG, this::handleChatMessage);
            models.registerEventHandler(ProtocolConstants.USER_JOINED, this::handleUserJoined);
            models.registerEventHandler(ProtocolConstants.USER_LEFT, this::handleUserLeft);
            models.registerEventHandler(ProtocolConstants.SPEAK_REQUEST, this::handleSpeakRequest);
            models.registerEventHandler(ProtocolConstants.SPEAK_GRANTED, this::handleSpeakGranted);
            models.registerEventHandler(ProtocolConstants.SPEAK_DENIED, this::handleSpeakDenied);
            models.registerEventHandler(ProtocolConstants.POLL, this::handlePoll);
            models.registerEventHandler(ProtocolConstants.POLL_RESULTS, this::handlePollResults);
            models.registerEventHandler(ProtocolConstants.MEETING_CLOSED, this::handleMeetingClosed);
            models.registerEventHandler("DISCONNECTED", this::handleDisconnection);
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;

            // Configurer la fermeture de la scène
            stage.setOnCloseRequest(e -> {
                e.consume(); // Empêcher la fermeture directe
                handleLeaveMeeting(); // Quitter proprement la réunion
            });
        }

        /**
         * Vérifie si l'utilisateur courant est l'organisateur de la réunion
         */
        private boolean isMeetingOrganizer() {
            return models.getCurrentMeeting() != null &&
                    models.getCurrentUser().getId() == models.getCurrentMeeting().getOrganizerId();
        }

        /**
         * Gère l'envoi d'un message
         */
        @FXML
        private void handleSendMessage() {
            String message = messageInput.getText().trim();
            if (message.isEmpty()) return;

            // Envoyer le message au serveur
            models.sendChatMessage(message);

            // Vider le champ de saisie
            messageInput.clear();
            messageInput.requestFocus();
        }

        /**
         * Gère la réception d'un message
         */
        private void handleChatMessage(String payload) {
            // Le message est déjà traité par le modèle et ajouté à la liste
            Platform.runLater(() -> {
                chatListView.scrollTo(models.getCurrentMeetingMessages().size() - 1);

                // Mettre en évidence l'onglet du chat si ce n'est pas l'onglet actif
                if (!chatTab.isSelected()) {
                    chatTab.setStyle("-fx-font-weight: bold;");
                }
            });
        }

        /**
         * Gère l'arrivée d'un utilisateur
         */
        private void handleUserJoined(String payload) {
            // L'utilisateur est déjà ajouté à la liste des participants par le modèle
            Platform.runLater(() -> {
                statusLabel.setText("Un nouvel utilisateur a rejoint la réunion");
            });
        }

        /**
         * Gère le départ d'un utilisateur
         */
        private void handleUserLeft(String payload) {
            // L'utilisateur est déjà supprimé de la liste des participants par le modèle
            Platform.runLater(() -> {
                statusLabel.setText("Un utilisateur a quitté la réunion");
            });
        }

        /**
         * Gère une demande de parole
         */
        private void handleSpeakRequest(String payload) {
            // Format: userId|userName
            Platform.runLater(() -> {
                String[] parts = payload.split("\\|");
                if (parts.length >= 2) {
                    int userId = Integer.parseInt(parts[0]);
                    String userName = parts[1];

                    // Si je suis l'organisateur, je reçois une notification
                    if (isMeetingOrganizer()) {
                        speakRequestsListView.getItems().add(userName + " (" + userId + ")");
                        statusLabel.setText(userName + " demande la parole");
                    }
                }
            });
        }

        /**
         * Gère l'acceptation d'une demande de parole
         */
        private void handleSpeakGranted(String payload) {
            Platform.runLater(() -> {
                statusLabel.setText("Vous avez la parole");
                requestToSpeakButton.setText("Fin de parole");
                requestToSpeakButton.setStyle("-fx-background-color: green;");
                isSpeaking = true;

                // Activer le micro (à implémenter)
            });
        }

        /**
         * Gère le refus d'une demande de parole
         */
        private void handleSpeakDenied(String payload) {
            Platform.runLater(() -> {
                statusLabel.setText("Votre demande de parole a été refusée");
                requestToSpeakButton.setText("Demander la parole");
                requestToSpeakButton.setStyle("");
                isSpeaking = false;
            });
        }

        /**
         * Gère la réception d'un sondage
         */
        private void handlePoll(String payload) {
            Platform.runLater(() -> {
                // Créer l'UI pour le sondage
                VBox pollBox = new VBox(10);
                pollBox.setStyle("-fx-border-color: #cccccc; -fx-border-radius: 5; -fx-padding: 10;");

                String[] parts = payload.split("\\|");
                if (parts.length >= 3) {
                    int pollId = Integer.parseInt(parts[0]);
                    int meetingId = Integer.parseInt(parts[1]);
                    String question = parts[2];

                    Label questionLabel = new Label(question);
                    questionLabel.setStyle("-fx-font-weight: bold;");
                    pollBox.getChildren().add(questionLabel);

                    ToggleGroup optionsGroup = new ToggleGroup();

                    // Ajouter les options
                    for (int i = 3; i < parts.length; i += 2) {
                        int optionId = Integer.parseInt(parts[i]);
                        String optionText = parts[i + 1];

                        RadioButton option = new RadioButton(optionText);
                        option.setToggleGroup(optionsGroup);
                        option.setUserData(optionId);
                        pollBox.getChildren().add(option);
                    }

                    Button voteButton = new Button("Voter");
                    voteButton.setOnAction(e -> {
                        if (optionsGroup.getSelectedToggle() != null) {
                            int selectedOptionId = (Integer) optionsGroup.getSelectedToggle().getUserData();
                            models.vote(pollId, selectedOptionId);
                            voteButton.setDisable(true);
                            statusLabel.setText("Vote envoyé");
                        }
                    });
                    pollBox.getChildren().add(voteButton);

                    pollsContainer.getChildren().add(pollBox);

                    // Sélectionner l'onglet des sondages
                    tabPane.getSelectionModel().select(pollsTab);
                }
            });
        }

        /**
         * Gère la réception des résultats d'un sondage
         */
        private void handlePollResults(String payload) {
            Platform.runLater(() -> {
                // Créer un graphique pour les résultats
                String[] parts = payload.split("\\|");
                if (parts.length >= 3) {
                    int pollId = Integer.parseInt(parts[0]);

                    // Trouver le sondage dans la liste des sondages
                    Models.Poll poll = null;
                    for (Models.Poll p : models.getCurrentMeetingPolls()) {
                        if (p.getId() == pollId) {
                            poll = p;
                            break;
                        }
                    }

                    if (poll != null) {
                        // Créer un graphique en camembert
                        PieChart pieChart = new PieChart();
                        pieChart.setTitle("Résultats: " + poll.getQuestion());
                        pieChart.setLegendVisible(true);

                        // Suite de la méthode handlePollResults dans MeetingController
                        for (Models.PollOption option : poll.getOptions()) {
                            PieChart.Data slice = new PieChart.Data(option.getText() + " (" + option.getVotes() + ")", option.getVotes());
                            pieChart.getData().add(slice);
                        }

                        // Remplacer le contenu du sondage par le graphique
                        for (javafx.scene.Node node : pollsContainer.getChildren()) {
                            if (node instanceof VBox) {
                                VBox pollBox = (VBox) node;
                                if (pollBox.getChildren().size() > 0 && pollBox.getChildren().get(0) instanceof Label) {
                                    Label questionLabel = (Label) pollBox.getChildren().get(0);
                                    if (questionLabel.getText().equals(poll.getQuestion())) {
                                        pollBox.getChildren().clear();
                                        pollBox.getChildren().add(pieChart);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }

        /**
         * Gère la demande de parole
         */
        @FXML
        private void handleRequestToSpeak() {
            if (isSpeaking) {
                // Terminer la parole
                models.sendMessage("END_SPEAK|" + models.getCurrentMeeting().getId());
                requestToSpeakButton.setText("Demander la parole");
                requestToSpeakButton.setStyle("");
                isSpeaking = false;
                statusLabel.setText("Vous avez terminé votre tour de parole");

                // Désactiver le micro (à implémenter)
            } else {
                // Demander la parole
                models.requestToSpeak();
                requestToSpeakButton.setText("En attente...");
                requestToSpeakButton.setStyle("-fx-background-color: orange;");
                statusLabel.setText("Demande de parole envoyée");
            }
        }

        /**
         * Gère l'acceptation d'une demande de parole (côté organisateur)
         */
        @FXML
        private void handleAllowToSpeak() {
            String selected = speakRequestsListView.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            // Extraire l'ID utilisateur
            int startIdx = selected.lastIndexOf('(');
            int endIdx = selected.lastIndexOf(')');
            if (startIdx >= 0 && endIdx > startIdx) {
                try {
                    int userId = Integer.parseInt(selected.substring(startIdx + 1, endIdx));
                    models.allowUserToSpeak(userId);
                    speakRequestsListView.getItems().remove(selected);
                    statusLabel.setText("Parole accordée");
                } catch (NumberFormatException e) {
                    statusLabel.setText("Erreur: format d'ID utilisateur invalide");
                }
            }
        }

        /**
         * Gère le refus d'une demande de parole (côté organisateur)
         */
        @FXML
        private void handleDenyToSpeak() {
            String selected = speakRequestsListView.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            // Extraire l'ID utilisateur
            int startIdx = selected.lastIndexOf('(');
            int endIdx = selected.lastIndexOf(')');
            if (startIdx >= 0 && endIdx > startIdx) {
                try {
                    int userId = Integer.parseInt(selected.substring(startIdx + 1, endIdx));
                    models.denyUserToSpeak(userId);
                    speakRequestsListView.getItems().remove(selected);
                    statusLabel.setText("Parole refusée");
                } catch (NumberFormatException e) {
                    statusLabel.setText("Erreur: format d'ID utilisateur invalide");
                }
            }
        }

        /**
         * Gère le partage d'écran
         */
        @FXML
        private void handleShareScreen() {
            if (isScreenSharing) {
                // Arrêter le partage d'écran
                models.sendMessage("SCREEN_OFF|" + models.getCurrentMeeting().getId());
                shareScreenButton.setText("Partager l'écran");
                shareScreenButton.setStyle("");
                isScreenSharing = false;
                statusLabel.setText("Partage d'écran arrêté");

                // Désactiver le partage d'écran (à implémenter)
            } else {
                // Démarrer le partage d'écran
                models.sendMessage("SCREEN_ON|" + models.getCurrentMeeting().getId());
                shareScreenButton.setText("Arrêter le partage");
                shareScreenButton.setStyle("-fx-background-color: green;");
                isScreenSharing = true;
                statusLabel.setText("Partage d'écran démarré");

                // Activer le partage d'écran (à implémenter)
            }
        }

        /**
         * Gère la création d'un sondage
         */
        @FXML
        private void handleCreatePoll() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/CreatePollView.fxml"));
                Parent root = loader.load();

                Stage pollStage = new Stage();
                pollStage.initModality(Modality.APPLICATION_MODAL);
                pollStage.initOwner(stage);

                CreatePollController controller = loader.getController();
                controller.setStage(pollStage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                pollStage.setScene(scene);
                pollStage.setTitle("Créer un sondage");
                pollStage.showAndWait();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de création de sondage", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                        "Impossible de charger la vue de création de sondage.");
            }
        }

        /**
         * Gère la clôture d'une réunion
         */
        @FXML
        private void handleCloseMeeting() {
            Alert confirmationAlert = new Alert(Alert.AlertType.CONFIRMATION);
            confirmationAlert.setTitle("Clôture de réunion");
            confirmationAlert.setHeaderText("Clôturer la réunion");
            confirmationAlert.setContentText("Êtes-vous sûr de vouloir clôturer cette réunion ?\n" +
                    "Tous les participants seront déconnectés.");

            Optional<ButtonType> result = confirmationAlert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                models.closeMeeting();
                statusLabel.setText("Demande de clôture de réunion envoyée...");
            }
        }

        /**
         * Gère le départ d'une réunion
         */
        @FXML
        private void handleLeaveMeeting() {
            // Quitter la réunion
            models.leaveMeeting();

            // Désenregistrer les gestionnaires d'événements
            models.unregisterEventHandler(ProtocolConstants.CHAT_MSG, this::handleChatMessage);
            models.unregisterEventHandler(ProtocolConstants.USER_JOINED, this::handleUserJoined);
            models.unregisterEventHandler(ProtocolConstants.USER_LEFT, this::handleUserLeft);
            models.unregisterEventHandler(ProtocolConstants.SPEAK_REQUEST, this::handleSpeakRequest);
            models.unregisterEventHandler(ProtocolConstants.SPEAK_GRANTED, this::handleSpeakGranted);
            models.unregisterEventHandler(ProtocolConstants.SPEAK_DENIED, this::handleSpeakDenied);
            models.unregisterEventHandler(ProtocolConstants.POLL, this::handlePoll);
            models.unregisterEventHandler(ProtocolConstants.POLL_RESULTS, this::handlePollResults);
            models.unregisterEventHandler(ProtocolConstants.MEETING_CLOSED, this::handleMeetingClosed);
            models.unregisterEventHandler("DISCONNECTED", this::handleDisconnection);

            // Retourner au dashboard
            returnToDashboard();
        }

        /**
         * Gère la fermeture d'une réunion
         */
        private void handleMeetingClosed(String payload) {
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.INFORMATION, "Réunion terminée", "La réunion est terminée",
                        "L'organisateur a mis fin à la réunion.");

                // Désenregistrer les gestionnaires d'événements et revenir au dashboard
                models.unregisterEventHandler(ProtocolConstants.CHAT_MSG, this::handleChatMessage);
                models.unregisterEventHandler(ProtocolConstants.USER_JOINED, this::handleUserJoined);
                models.unregisterEventHandler(ProtocolConstants.USER_LEFT, this::handleUserLeft);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_REQUEST, this::handleSpeakRequest);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_GRANTED, this::handleSpeakGranted);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_DENIED, this::handleSpeakDenied);
                models.unregisterEventHandler(ProtocolConstants.POLL, this::handlePoll);
                models.unregisterEventHandler(ProtocolConstants.POLL_RESULTS, this::handlePollResults);
                models.unregisterEventHandler(ProtocolConstants.MEETING_CLOSED, this::handleMeetingClosed);
                models.unregisterEventHandler("DISCONNECTED", this::handleDisconnection);

                returnToDashboard();
            });
        }

        /**
         * Gère une déconnexion
         */
        private void handleDisconnection(String payload) {
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.ERROR, "Déconnexion", "Connexion perdue",
                        "La connexion au serveur a été perdue. Vous allez être redirigé vers l'écran de connexion.");

                // Désenregistrer les gestionnaires d'événements
                models.unregisterEventHandler(ProtocolConstants.CHAT_MSG, this::handleChatMessage);
                models.unregisterEventHandler(ProtocolConstants.USER_JOINED, this::handleUserJoined);
                models.unregisterEventHandler(ProtocolConstants.USER_LEFT, this::handleUserLeft);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_REQUEST, this::handleSpeakRequest);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_GRANTED, this::handleSpeakGranted);
                models.unregisterEventHandler(ProtocolConstants.SPEAK_DENIED, this::handleSpeakDenied);
                models.unregisterEventHandler(ProtocolConstants.POLL, this::handlePoll);
                models.unregisterEventHandler(ProtocolConstants.POLL_RESULTS, this::handlePollResults);
                models.unregisterEventHandler(ProtocolConstants.MEETING_CLOSED, this::handleMeetingClosed);
                models.unregisterEventHandler("DISCONNECTED", this::handleDisconnection);

                // Retourner à l'écran de login
                returnToLogin();
            });
        }

        /**
         * Retourne au tableau de bord
         */
        private void returnToDashboard() {
            try {
                // Choisir la vue en fonction du rôle de l'utilisateur
                String fxmlPath = models.getCurrentUser().isAdmin() ?
                        "/view/AdminDashboardView.fxml" : "/view/OrganizerDashboardView.fxml";

                FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
                Parent root = loader.load();

                // Configurer le contrôleur
                if (models.getCurrentUser().isAdmin()) {
                    AdminDashboardController controller = loader.getController();
                    controller.setStage(stage);
                } else {
                    OrganizerDashboardController controller = loader.getController();
                    controller.setStage(stage);
                }

                // Configurer la scène
                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                stage.setScene(scene);
                stage.setTitle("BMO - Bureau de Médiation en Ligne");

                // Demander la liste des réunions
                models.requestMeetings();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du retour au dashboard", e);
                returnToLogin();
            }
        }

        /**
         * Retourne à l'écran de login
         */
        private void returnToLogin() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/LoginView.fxml"));
                Parent root = loader.load();

                LoginController controller = loader.getController();
                controller.setStage(stage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                stage.setScene(scene);
                stage.setTitle("BMO - Connexion");

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de login", e);
                Platform.exit();
            }
        }

        /**
         * Affiche une alerte
         */
        private void showAlert(Alert.AlertType type, String title, String header, String content) {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
        }

        /**
         * Gère le changement d'onglet
         */
        @FXML
        private void handleTabChanged() {
            if (chatTab.isSelected()) {
                chatTab.setStyle("");
            }
        }
    }

    /**
     * Contrôleur pour la création d'une réunion
     */
    public static class CreateMeetingController {
        @FXML private TextField titleField;
        @FXML private TextArea agendaField;
        @FXML private DatePicker datePicker;
        @FXML private TextField timeField;
        @FXML private Spinner<Integer> durationSpinner;
        @FXML private ComboBox<String> typeComboBox;
        @FXML private Button createButton;
        @FXML private Button cancelButton;
        @FXML private ListView<Models.User> invitedUsersListView;
        @FXML private ListView<Models.User> availableUsersListView;
        @FXML private Button addUserButton;
        @FXML private Button removeUserButton;
        @FXML private Label errorLabel;

        private Models models;
        private Stage stage;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les champs
            datePicker.setValue(LocalDate.now());
            timeField.setText(LocalTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));

            // Configurer le spinner de durée
            SpinnerValueFactory<Integer> valueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(15, 240, 60, 15);
            durationSpinner.setValueFactory(valueFactory);

            // Configurer le combo box de type
            typeComboBox.getItems().addAll("Standard", "Privée", "Démocratique");
            typeComboBox.setValue("Standard");

            // Configurer les boutons
            createButton.disableProperty().bind(
                    titleField.textProperty().isEmpty()
            );

            // Masquer l'erreur
            errorLabel.setVisible(false);

            // Charger la liste des utilisateurs disponibles
            models.requestUsers();

            // Configurer les listes d'utilisateurs
            setupUserLists();
        }

        /**
         * Configure les listes d'utilisateurs
         */
        private void setupUserLists() {
            // Configurer la liste des utilisateurs disponibles
            FilteredList<Models.User> filteredUsers = new FilteredList<>(models.getUsers(), user -> true);
            availableUsersListView.setItems(filteredUsers);
            availableUsersListView.setCellFactory(lv -> new ListCell<Models.User>() {
                @Override
                protected void updateItem(Models.User user, boolean empty) {
                    super.updateItem(user, empty);
                    if (empty || user == null) {
                        setText(null);
                    } else {
                        setText(user.getName() + (user.isAdmin() ? " (Admin)" : ""));
                    }
                }
            });

            // Configurer la liste des utilisateurs invités
            invitedUsersListView.setItems(FXCollections.observableArrayList());
            invitedUsersListView.setCellFactory(lv -> new ListCell<Models.User>() {
                @Override
                protected void updateItem(Models.User user, boolean empty) {
                    super.updateItem(user, empty);
                    if (empty || user == null) {
                        setText(null);
                    } else {
                        setText(user.getName() + (user.isAdmin() ? " (Admin)" : ""));
                    }
                }
            });

            // Configurer les boutons d'ajout et de suppression
            addUserButton.disableProperty().bind(Bindings.isEmpty(availableUsersListView.getSelectionModel().getSelectedItems()));
            removeUserButton.disableProperty().bind(Bindings.isEmpty(invitedUsersListView.getSelectionModel().getSelectedItems()));
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère l'ajout d'un utilisateur à la liste des invités
         */
        @FXML
        private void handleAddUser() {
            Models.User selectedUser = availableUsersListView.getSelectionModel().getSelectedItem();
            if (selectedUser != null) {
                invitedUsersListView.getItems().add(selectedUser);
                availableUsersListView.getItems().remove(selectedUser);
            }
        }

        /**
         * Gère la suppression d'un utilisateur de la liste des invités
         */
        @FXML
        private void handleRemoveUser() {
            Models.User selectedUser = invitedUsersListView.getSelectionModel().getSelectedItem();
            if (selectedUser != null) {
                invitedUsersListView.getItems().remove(selectedUser);
                availableUsersListView.getItems().add(selectedUser);
            }
        }

        /**
         * Gère la création d'une réunion
         */
        @FXML
        private void handleCreate() {
            // Récupérer les données du formulaire
            String title = titleField.getText().trim();
            String agenda = agendaField.getText().trim();
            LocalDate date = datePicker.getValue();
            String timeText = timeField.getText().trim();
            int duration = durationSpinner.getValue();
            String type = typeComboBox.getValue();

            // Valider les données
            if (title.isEmpty()) {
                showError("Le titre est obligatoire.");
                return;
            }

            if (date == null) {
                showError("La date est obligatoire.");
                return;
            }

            LocalTime time;
            try {
                time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e) {
                showError("Format d'heure invalide. Utilisez le format HH:mm.");
                return;
            }

            // Créer l'objet réunion
            LocalDateTime dateTime = LocalDateTime.of(date, time);
            Models.Meeting meeting = new Models.Meeting(0, title, agenda, dateTime, duration, type, "Planifiée", models.getCurrentUser().getId());

            // Créer la réunion
            if (models.createMeeting(meeting)) {
                // Si la création a réussi, fermer la fenêtre
                stage.close();
            } else {
                showError("Erreur lors de la création de la réunion.");
            }
        }

        /**
         * Gère l'annulation de la création
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }

    /**
     * Contrôleur pour la modification d'une réunion
     */
    public static class EditMeetingController {
        @FXML private TextField titleField;
        @FXML private TextArea agendaField;
        @FXML private DatePicker datePicker;
        @FXML private TextField timeField;
        @FXML private Spinner<Integer> durationSpinner;
        @FXML private ComboBox<String> typeComboBox;
        @FXML private ComboBox<String> statusComboBox;
        @FXML private Button saveButton;
        @FXML private Button cancelButton;
        @FXML private Label errorLabel;

        private Models models;
        private Stage stage;
        private Models.Meeting meeting;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer le spinner de durée
            SpinnerValueFactory<Integer> valueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(15, 240, 60, 15);
            durationSpinner.setValueFactory(valueFactory);

            // Configurer le combo box de type
            typeComboBox.getItems().addAll("Standard", "Privée", "Démocratique");

            // Configurer le combo box de statut
            statusComboBox.getItems().addAll("Planifiée", "Ouverte", "Terminée");

            // Configurer les boutons
            saveButton.disableProperty().bind(
                    titleField.textProperty().isEmpty()
            );

            // Masquer l'erreur
            errorLabel.setVisible(false);
        }

        /**
         * Définit la réunion à modifier
         * @param meeting La réunion
         */
        public void setMeeting(Models.Meeting meeting) {
            this.meeting = meeting;

            // Remplir les champs avec les données de la réunion
            titleField.setText(meeting.getTitle());
            agendaField.setText(meeting.getAgenda());
            datePicker.setValue(meeting.getDatetime().toLocalDate());
            timeField.setText(meeting.getDatetime().toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")));
            durationSpinner.getValueFactory().setValue(meeting.getDuration());
            typeComboBox.setValue(meeting.getType());
            statusComboBox.setValue(meeting.getStatus());
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère la sauvegarde des modifications
         */
        @FXML
        private void handleSave() {
            // Récupérer les données du formulaire
            String title = titleField.getText().trim();
            String agenda = agendaField.getText().trim();
            LocalDate date = datePicker.getValue();
            String timeText = timeField.getText().trim();
            int duration = durationSpinner.getValue();
            String type = typeComboBox.getValue();
            String status = statusComboBox.getValue();

            // Valider les données
            if (title.isEmpty()) {
                showError("Le titre est obligatoire.");
                return;
            }

            if (date == null) {
                showError("La date est obligatoire.");
                return;
            }

            LocalTime time;
            try {
                time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern("HH:mm"));
            } catch (Exception e) {
                showError("Format d'heure invalide. Utilisez le format HH:mm.");
                return;
            }

            // Mettre à jour l'objet réunion
            LocalDateTime dateTime = LocalDateTime.of(date, time);

            // Créer la commande de mise à jour
            StringBuilder command = new StringBuilder("UPDATE_MEETING");
            command.append("|").append(meeting.getId());
            command.append("|").append(title);
            command.append("|").append(agenda);
            command.append("|").append(dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            command.append("|").append(duration);
            command.append("|").append(type);
            command.append("|").append(status);

            // Envoyer la commande
            if (models.sendMessage(command.toString())) {
                // Si la mise à jour a réussi, fermer la fenêtre
                stage.close();
            } else {
                showError("Erreur lors de la mise à jour de la réunion.");
            }
        }

        /**
         * Gère l'annulation des modifications
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }

    /**
     * Contrôleur pour l'ajout d'un utilisateur
     */
    public static class AddUserController {
        @FXML private TextField loginField;
        @FXML private PasswordField passwordField;
        @FXML private PasswordField confirmPasswordField;
        @FXML private TextField nameField;
        @FXML private CheckBox adminCheckbox;
        @FXML private Button addButton;
        @FXML private Button cancelButton;
        @FXML private Label errorLabel;

        private Models models;
        private Stage stage;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les boutons
            addButton.disableProperty().bind(
                    loginField.textProperty().isEmpty()
                            .or(passwordField.textProperty().isEmpty())
                            .or(confirmPasswordField.textProperty().isEmpty())
                            .or(nameField.textProperty().isEmpty())
            );

            // Masquer l'erreur
            errorLabel.setVisible(false);
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère l'ajout d'un utilisateur
         */
        @FXML
        private void handleAdd() {
            // Récupérer les données du formulaire
            String login = loginField.getText().trim();
            String password = passwordField.getText();
            String confirmPassword = confirmPasswordField.getText();
            String name = nameField.getText().trim();
            boolean isAdmin = adminCheckbox.isSelected();

            // Valider les données
            if (login.isEmpty() || password.isEmpty() || name.isEmpty()) {
                showError("Tous les champs sont obligatoires.");
                return;
            }

            if (!password.equals(confirmPassword)) {
                showError("Les mots de passe ne correspondent pas.");
                return;
            }

            // Créer l'objet utilisateur
            Models.User user = new Models.User(0, login, password, name, isAdmin);

            // Créer l'utilisateur
            if (models.createUser(user)) {
                // Si la création a réussi, fermer la fenêtre
                stage.close();
            } else {
                showError("Erreur lors de la création de l'utilisateur.");
            }
        }

        /**
         * Gère l'annulation de l'ajout
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }

    /**
     * Contrôleur pour la modification d'un utilisateur
     */
    public static class EditUserController {
        @FXML private TextField loginField;
        @FXML private PasswordField passwordField;
        @FXML private PasswordField confirmPasswordField;
        @FXML private TextField nameField;
        @FXML private CheckBox adminCheckbox;
        @FXML private Button saveButton;
        @FXML private Button cancelButton;
        @FXML private Label errorLabel;
        @FXML private CheckBox changePasswordCheckbox;
        @FXML private VBox passwordContainer;

        private Models models;
        private Stage stage;
        private Models.User user;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les boutons
            saveButton.disableProperty().bind(
                    loginField.textProperty().isEmpty()
                            .or(nameField.textProperty().isEmpty())
                            .or(Bindings.when(changePasswordCheckbox.selectedProperty())
                                    .then(passwordField.textProperty().isEmpty()
                                            .or(confirmPasswordField.textProperty().isEmpty()))
                                    .otherwise(false))
            );

            // Configurer le changement de mot de passe
            passwordContainer.visibleProperty().bind(changePasswordCheckbox.selectedProperty());
            passwordContainer.managedProperty().bind(changePasswordCheckbox.selectedProperty());

            // Masquer l'erreur
            errorLabel.setVisible(false);
        }

        /**
         * Définit l'utilisateur à modifier
         * @param user L'utilisateur
         */
        public void setUser(Models.User user) {
            this.user = user;

            // Remplir les champs avec les données de l'utilisateur
            loginField.setText(user.getLogin());
            nameField.setText(user.getName());
            adminCheckbox.setSelected(user.isAdmin());

            // Ne pas afficher le mot de passe
            changePasswordCheckbox.setSelected(false);
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère la sauvegarde des modifications
         */
        @FXML
        private void handleSave() {
            // Récupérer les données du formulaire
            String login = loginField.getText().trim();
            String name = nameField.getText().trim();
            boolean isAdmin = adminCheckbox.isSelected();

            // Valider les données
            if (login.isEmpty() || name.isEmpty()) {
                showError("Le login et le nom sont obligatoires.");
                return;
            }

            // Vérifier le mot de passe si nécessaire
            if (changePasswordCheckbox.isSelected()) {
                String password = passwordField.getText();
                String confirmPassword = confirmPasswordField.getText();

                if (password.isEmpty()) {
                    showError("Le mot de passe est obligatoire.");
                    return;
                }

                if (!password.equals(confirmPassword)) {
                    showError("Les mots de passe ne correspondent pas.");
                    return;
                }
            }

            // Créer la commande de mise à jour
            StringBuilder command = new StringBuilder("UPDATE_USER");
            command.append("|").append(user.getId());
            command.append("|").append(login);
            command.append("|").append(name);
            command.append("|").append(isAdmin ? "ADMIN" : "USER");

            if (changePasswordCheckbox.isSelected()) {
                command.append("|").append(passwordField.getText());
            }

            // Envoyer la commande
            if (models.sendMessage(command.toString())) {
                // Si la mise à jour a réussi, fermer la fenêtre
                stage.close();
            } else {
                showError("Erreur lors de la mise à jour de l'utilisateur.");
            }
        }

        /**
         * Gère l'annulation des modifications
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }

    /**
     * Contrôleur pour le profil utilisateur
     */
    public static class UserProfileController {
        @FXML private Label loginLabel;
        @FXML private Label nameLabel;
        @FXML private Label roleLabel;
        @FXML private Button changePasswordButton;
        @FXML private Button closeButton;
        @FXML private ImageView userPhoto;
        @FXML private Button uploadPhotoButton;

        private Models models;
        private Stage stage;
        private Models.User user;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();
        }

        /**
         * Définit l'utilisateur à afficher
         * @param user L'utilisateur
         */
        public void setUser(Models.User user) {
            this.user = user;

            // Remplir les champs avec les données de l'utilisateur
            loginLabel.setText(user.getLogin());
            nameLabel.setText(user.getName());
            roleLabel.setText(user.isAdmin() ? "Administrateur" : "Utilisateur");
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère le changement de mot de passe
         */
        @FXML
        private void handleChangePassword() {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/ChangePasswordView.fxml"));
                Parent root = loader.load();

                Stage passwordStage = new Stage();
                passwordStage.initModality(Modality.APPLICATION_MODAL);
                passwordStage.initOwner(stage);

                ChangePasswordController controller = loader.getController();
                controller.setUser(user);
                controller.setStage(passwordStage);

                Scene scene = new Scene(root);
                scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

                passwordStage.setScene(scene);
                passwordStage.setTitle("Changer le mot de passe");
                passwordStage.showAndWait();

            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Erreur lors du chargement de la vue de changement de mot de passe", e);
                showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur d'affichage",
                        "Impossible de charger la vue de changement de mot de passe.");
            }
        }

        /**
         * Gère l'envoi d'une photo de profil
         */
        @FXML
        private void handleUploadPhoto() {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Sélectionner une photo de profil");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif")
            );

            File file = fileChooser.showOpenDialog(stage);
            if (file != null) {
                try {
                    // Lire le fichier
                    byte[] fileContent = Files.readAllBytes(file.toPath());

                    // Créer la commande d'envoi
                    StringBuilder command = new StringBuilder("UPLOAD_PHOTO");
                    command.append("|").append(user.getId());

                    // Convertir le fichier en Base64
                    String base64 = Base64.getEncoder().encodeToString(fileContent);
                    command.append("|").append(base64);

                    // Ajouter le type MIME
                    String mimeType = getFileMimeType(file.getName());
                    command.append("|").append(mimeType);

                    // Envoyer la commande
                    models.sendMessage(command.toString());

                    // Mettre à jour l'affichage
                    Image image = new Image(file.toURI().toString());
                    userPhoto.setImage(image);

                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Erreur lors de la lecture du fichier", e);
                    showAlert(Alert.AlertType.ERROR, "Erreur", "Erreur de fichier",
                            "Impossible de lire le fichier.");
                }
            }
        }

        /**
         * Détermine le type MIME d'un fichier à partir de son extension
         */
        private String getFileMimeType(String filename) {
            String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            switch (extension) {
                case "png":
                    return "image/png";
                case "jpg":
                case "jpeg":
                    return "image/jpeg";
                case "gif":
                    return "image/gif";
                default:
                    return "application/octet-stream";
            }
        }

        /**
         * Gère la fermeture de la vue
         */
        @FXML
        private void handleClose() {
            stage.close();
        }

        /**
         * Affiche une alerte
         */
        private void showAlert(Alert.AlertType type, String title, String header, String content) {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
        }
    }

    /**
     * Contrôleur pour le changement de mot de passe
     */
    public static class ChangePasswordController {
        @FXML private PasswordField currentPasswordField;
        @FXML private PasswordField newPasswordField;
        @FXML private PasswordField confirmPasswordField;
        @FXML private Button saveButton;
        @FXML private Button cancelButton;
        @FXML private Label errorLabel;

        private Models models;
        private Stage stage;
        private Models.User user;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les boutons
            saveButton.disableProperty().bind(
                    currentPasswordField.textProperty().isEmpty()
                            .or(newPasswordField.textProperty().isEmpty())
                            .or(confirmPasswordField.textProperty().isEmpty())
            );

            // Masquer l'erreur
            errorLabel.setVisible(false);
        }

        /**
         * Définit l'utilisateur
         * @param user L'utilisateur
         */
        public void setUser(Models.User user) {
            this.user = user;
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère la sauvegarde du nouveau mot de passe
         */
        @FXML
        private void handleSave() {
            // Récupérer les données du formulaire
            String currentPassword = currentPasswordField.getText();
            String newPassword = newPasswordField.getText();
            String confirmPassword = confirmPasswordField.getText();

            // Valider les données
            if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
                showError("Tous les champs sont obligatoires.");
                return;
            }

            if (!newPassword.equals(confirmPassword)) {
                showError("Les nouveaux mots de passe ne correspondent pas.");
                return;
            }

            // Créer la commande de changement de mot de passe
            StringBuilder command = new StringBuilder("CHANGE_PASSWORD");
            command.append("|").append(user.getId());
            command.append("|").append(currentPassword);
            command.append("|").append(newPassword);

            // Envoyer la commande
            models.sendMessage(command.toString());

            // Enregistrer un gestionnaire pour la réponse
            models.registerEventHandler("PASSWORD_CHANGED", this::handlePasswordChanged);
            models.registerEventHandler("PASSWORD_CHANGE_FAILED", this::handlePasswordChangeFailed);
        }

        /**
         * Gère le succès du changement de mot de passe
         */
        private void handlePasswordChanged(String payload) {
            Platform.runLater(() -> {
                showAlert(Alert.AlertType.INFORMATION, "Succès", "Mot de passe changé",
                        "Votre mot de passe a été changé avec succès.");

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler("PASSWORD_CHANGED", this::handlePasswordChanged);
                models.unregisterEventHandler("PASSWORD_CHANGE_FAILED", this::handlePasswordChangeFailed);

                stage.close();
            });
        }

        /**
         * Gère l'échec du changement de mot de passe
         */
        private void handlePasswordChangeFailed(String payload) {
            Platform.runLater(() -> {
                showError(payload);

                // Désenregistrer les gestionnaires
                models.unregisterEventHandler("PASSWORD_CHANGED", this::handlePasswordChanged);
                models.unregisterEventHandler("PASSWORD_CHANGE_FAILED", this::handlePasswordChangeFailed);
            });
        }

        /**
         * Gère l'annulation du changement de mot de passe
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }

        /**
         * Affiche une alerte
         */
        private void showAlert(Alert.AlertType type, String title, String header, String content) {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            alert.showAndWait();
        }
    }

    /**
     * Contrôleur pour la création d'un sondage
     */
    public static class CreatePollController {
        @FXML private TextField questionField;
        @FXML private ListView<String> optionsListView;
        @FXML private TextField optionField;
        @FXML private Button addOptionButton;
        @FXML private Button removeOptionButton;
        @FXML private Button createButton;
        @FXML private Button cancelButton;
        @FXML private Label errorLabel;

        private Models models;
        private Stage stage;

        /**
         * Initialise le contrôleur
         */
        @FXML
        public void initialize() {
            models = Models.getInstance();

            // Configurer les boutons
            createButton.disableProperty().bind(
                    questionField.textProperty().isEmpty()
                            .or(Bindings.size(optionsListView.getItems()).lessThan(2))
            );

            removeOptionButton.disableProperty().bind(
                    Bindings.isEmpty(optionsListView.getSelectionModel().getSelectedItems())
            );

            addOptionButton.disableProperty().bind(
                    optionField.textProperty().isEmpty()
            );

            // Masquer l'erreur
            errorLabel.setVisible(false);
        }

        /**
         * Définit la scène
         * @param stage La scène
         */
        public void setStage(Stage stage) {
            this.stage = stage;
        }

        /**
         * Gère l'ajout d'une option
         */
        @FXML
        private void handleAddOption() {
            String option = optionField.getText().trim();
            if (!option.isEmpty()) {
                optionsListView.getItems().add(option);
                optionField.clear();
                optionField.requestFocus();
            }
        }

        /**
         * Gère la suppression d'une option
         */
        @FXML
        private void handleRemoveOption() {
            String selectedOption = optionsListView.getSelectionModel().getSelectedItem();
            if (selectedOption != null) {
                optionsListView.getItems().remove(selectedOption);
            }
        }

        /**
         * Gère la création d'un sondage
         */
        @FXML
        private void handleCreate() {
            // Récupérer les données du formulaire
            String question = questionField.getText().trim();
            List<String> options = new ArrayList<>(optionsListView.getItems());

            // Valider les données
            if (question.isEmpty()) {
                showError("La question est obligatoire.");
                return;
            }

            if (options.size() < 2) {
                showError("Il faut au moins deux options.");
                return;
            }

            // Créer le sondage
            if (models.createPoll(question, options)) {
                // Si la création a réussi, fermer la fenêtre
                stage.close();
            } else {
                showError("Erreur lors de la création du sondage.");
            }
        }

        /**
         * Gère l'annulation de la création
         */
        @FXML
        private void handleCancel() {
            stage.close();
        }

        /**
         * Affiche une erreur
         */
        private void showError(String message) {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
    }
    /**
     * Classe utilitaire pour la configuration
     */
    private static class ConfigUtils {
        private static final Logger LOGGER = Logger.getLogger(ConfigUtils.class.getName());

        /**
         * Récupère l'adresse du serveur depuis les préférences ou utilise localhost par défaut
         */
        public static String retrieveServerHost() {
            try {
                Preferences prefs = Preferences.userNodeForPackage(Controllers.class);
                return prefs.get("server.host", "localhost");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de la récupération de l'hôte du serveur", e);
                return "localhost";
            }
        }

        /**
         * Récupère le port du serveur depuis les préférences ou utilise 12345 par défaut
         */
        public static int retrieveServerPort() {
            try {
                Preferences prefs = Preferences.userNodeForPackage(Controllers.class);
                return prefs.getInt("server.port", 12345);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de la récupération du port du serveur", e);
                return 12345;
            }
        }

        /**
         * Enregistre l'adresse du serveur dans les préférences
         */
        public static void saveServerHost(String host) {
            try {
                Preferences prefs = Preferences.userNodeForPackage(Controllers.class);
                prefs.put("server.host", host);
                prefs.flush();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de l'enregistrement de l'hôte du serveur", e);
            }
        }

        /**
         * Enregistre le port du serveur dans les préférences
         */
        public static void saveServerPort(int port) {
            try {
                Preferences prefs = Preferences.userNodeForPackage(Controllers.class);
                prefs.putInt("server.port", port);
                prefs.flush();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de l'enregistrement du port du serveur", e);
            }
        }
    }
}
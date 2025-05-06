package com.bmo.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Utilitaire pour l'exportation de la base de données BMO.
 */
public class ExportUtil {
    private static final Logger LOGGER = Logger.getLogger(ExportUtil.class.getName());
    private static final String DEFAULT_EXPORT_PATH = "server/Bmo-app.sql";
    private static final String BACKUP_DIR = "server/backups";
    private static final int DEFAULT_BACKUP_RETENTION_DAYS = 7;
    private static final int DEFAULT_EXPORT_INTERVAL_HOURS = 24;

    private final DataSource dataSource;
    private final Properties dbProperties;
    private final String exportPath;
    private final ScheduledExecutorService scheduler;
    private final int backupRetentionDays;

    /**
     * Crée une instance d'ExportUtil avec un chemin d'export par défaut
     */
    public ExportUtil(DataSource dataSource, Properties dbProperties) {
        this(dataSource, dbProperties, DEFAULT_EXPORT_PATH);
    }

    /**
     * Crée une instance d'ExportUtil avec un chemin d'export spécifié
     */
    public ExportUtil(DataSource dataSource, Properties dbProperties, String exportPath) {
        this.dataSource = dataSource;
        this.dbProperties = dbProperties;
        this.exportPath = exportPath;
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.backupRetentionDays = DEFAULT_BACKUP_RETENTION_DAYS;

        // Créer le répertoire de backups s'il n'existe pas
        createBackupDirectory();
    }

    /**
     * Exporte la structure et les données de la base vers un fichier SQL.
     */
    public boolean exportDatabase() {
        LOGGER.info("Début de l'export de la base de données vers " + exportPath);

        try {
            // Récupérer les informations de connexion
            String dbHost = dbProperties.getProperty("db.host", "localhost");
            String dbPort = dbProperties.getProperty("db.port", "3306");
            String dbName = extractDatabaseNameFromUrl(dbProperties.getProperty("db.url"));
            String dbUser = dbProperties.getProperty("db.user");
            String dbPassword = dbProperties.getProperty("db.password");

            // Vérifier que les paramètres sont présents
            if (dbName == null || dbUser == null || dbPassword == null) {
                LOGGER.severe("Paramètres de connexion BDD incomplets pour l'export");
                return false;
            }

            // Créer un fichier temporaire pour le mot de passe
            File tempPasswordFile = createTempPasswordFile(dbPassword);

            try {
                // Construire la commande mysqldump
                ProcessBuilder processBuilder = new ProcessBuilder(
                        "mysqldump",
                        "--host=" + dbHost,
                        "--port=" + dbPort,
                        "--user=" + dbUser,
                        "--password=" + dbPassword,
                        "--add-drop-table",
                        "--complete-insert",
                        "--comments",
                        "--dump-date",
                        "--triggers",
                        "--routines",
                        "--set-charset",
                        dbName
                );

                // Rediriger la sortie vers le fichier d'export
                processBuilder.redirectOutput(new File(exportPath));

                // Démarrer le processus
                Process process = processBuilder.start();

                // Attendre la fin du processus avec timeout
                if (process.waitFor(5, TimeUnit.MINUTES)) {
                    int exitCode = process.exitValue();
                    if (exitCode == 0) {
                        LOGGER.info("Export de la base de données réussi");
                        createBackup();
                        return true;
                    } else {
                        // En cas d'erreur, récupérer la sortie d'erreur
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getErrorStream()))) {
                            StringBuilder errorOutput = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errorOutput.append(line).append("\n");
                            }
                            LOGGER.severe("Échec de l'export avec code " + exitCode + ": " + errorOutput);
                        }
                    }
                } else {
                    process.destroyForcibly();
                    LOGGER.severe("Timeout lors de l'export de la base de données");
                }
            } finally {
                // Supprimer le fichier temporaire du mot de passe
                if (tempPasswordFile != null) {
                    tempPasswordFile.delete();
                }
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de l'export de la base de données", e);
        }

        return false;
    }

    /**
     * Extrait le nom de la base de données à partir de l'URL JDBC
     */
    private String extractDatabaseNameFromUrl(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        // Format typique: jdbc:mysql://host:port/database_name
        int lastSlashIndex = jdbcUrl.lastIndexOf('/');
        if (lastSlashIndex != -1 && lastSlashIndex < jdbcUrl.length() - 1) {
            // Extraire le nom de la base et supprimer les paramètres éventuels
            String dbName = jdbcUrl.substring(lastSlashIndex + 1);
            int paramStartIndex = dbName.indexOf('?');
            return paramStartIndex != -1 ? dbName.substring(0, paramStartIndex) : dbName;
        }
        return null;
    }

    /**
     * Crée un fichier temporaire contenant le mot de passe pour mysqldump
     */
    private File createTempPasswordFile(String password) throws IOException {
        File tempFile = File.createTempFile("mysql_pwd", ".tmp");
        tempFile.deleteOnExit();

        try (FileOutputStream out = new FileOutputStream(tempFile)) {
            out.write(("[client]\npassword=" + password).getBytes());
        }

        return tempFile;
    }

    /**
     * Crée le répertoire de backups s'il n'existe pas
     */
    private void createBackupDirectory() {
        File backupDir = new File(BACKUP_DIR);
        if (!backupDir.exists()) {
            if (backupDir.mkdirs()) {
                LOGGER.info("Répertoire de backups créé: " + BACKUP_DIR);
            } else {
                LOGGER.warning("Impossible de créer le répertoire de backups: " + BACKUP_DIR);
            }
        }
    }

    /**
     * Crée une copie de sauvegarde du fichier d'export actuel
     */
    private void createBackup() {
        try {
            File sourceFile = new File(exportPath);
            if (sourceFile.exists()) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
                String timestamp = dateFormat.format(new Date());
                String backupFileName = BACKUP_DIR + "/bmo_backup_" + timestamp + ".sql";

                Files.copy(sourceFile.toPath(), Paths.get(backupFileName), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Backup créé: " + backupFileName);

                // Nettoyer les anciens backups
                cleanupOldBackups();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Impossible de créer le backup", e);
        }
    }

    /**
     * Supprime les backups plus anciens que la période de rétention
     */
    private void cleanupOldBackups() {
        try {
            File backupDir = new File(BACKUP_DIR);
            if (!backupDir.exists() || !backupDir.isDirectory()) return;

            File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".sql"));
            if (files == null) return;

            long cutoffTime = System.currentTimeMillis() - (backupRetentionDays * 24L * 60L * 60L * 1000L);

            for (File file : files) {
                if (file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        LOGGER.info("Ancien backup supprimé: " + file.getName());
                    } else {
                        LOGGER.warning("Impossible de supprimer l'ancien backup: " + file.getName());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors du nettoyage des anciens backups", e);
        }
    }

    /**
     * Démarre l'export automatique périodique
     */
    public void scheduleAutomaticExport() {
        scheduleAutomaticExport(DEFAULT_EXPORT_INTERVAL_HOURS);
    }

    /**
     * Démarre l'export automatique périodique avec intervalle personnalisé
     */
    public void scheduleAutomaticExport(int intervalHours) {
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        exportDatabase();
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Erreur lors de l'export automatique", e);
                    }
                },
                intervalHours, intervalHours, TimeUnit.HOURS
        );

        LOGGER.info("Export automatique programmé toutes les " + intervalHours + " heures");
    }

    /**
     * Arrête l'export automatique
     */
    public void stopAutomaticExport() {
        scheduler.shutdown();
        LOGGER.info("Export automatique arrêté");
    }

    /**
     * Export après modification de la structure de la base de données
     */
    public boolean exportAfterSchemaChange(Connection connection) {
        LOGGER.info("Exportation après modification du schéma...");
        return exportDatabase();
    }

    /**
     * Teste la connexion à la base de données
     */
    public boolean testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Échec du test de connexion à la base de données", e);
            return false;
        }
    }
}
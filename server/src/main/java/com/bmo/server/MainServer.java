package com.bmo.server;

import com.bmo.server.Controllers.ClientHandler;
import com.bmo.server.Controllers.SettingsController;
import com.mysql.cj.jdbc.MysqlConnectionPoolDataSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Classe principale du serveur BMO.
 * Initialise et gère le serveur, les connexions clients et les ressources système.
 */
public class MainServer {
    private static final Logger LOGGER = Logger.getLogger(MainServer.class.getName());
    private static final String DEFAULT_CONFIG_PATH = "server/src/main/resources/application.properties";

    private ServerSocket serverSocket;
    private boolean running = false;
    private ExecutorService threadPool;
    private final Properties config = new Properties();
    private DataSource dataSource;
    private ExportUtil exportUtil;
    private SettingsController settingsController;
    private int port;
    private String bindAddress;
    private int maxConnections;
    private int connectionTimeout;
    private boolean tlsEnabled;
    private boolean networkDiagnosticsRun = false;

    /**
     * Initialise et démarre le serveur BMO
     *
     * @param configPath Le chemin vers le fichier de configuration
     * @throws Exception En cas d'erreur lors de l'initialisation
     */
    public MainServer(String configPath) throws Exception {
        // Configurer les logs
        setupLogging();

        LOGGER.info("Initialisation du serveur BMO");

        // Charger la configuration
        loadConfiguration(configPath);

        // Initialiser les composants
        initializeDataSource();
        Models.initDataSource(dataSource);

        // Initialiser l'utilitaire d'export
        exportUtil = new ExportUtil(dataSource, config);

        // Initialiser le gestionnaire de paramètres
        settingsController = new Controllers.SettingsController(dataSource);

        // Vérifier la base de données
        checkDatabase();
    }

    /**
     * Charge les paramètres de configuration du fichier spécifié
     *
     * @param configPath Chemin vers le fichier de configuration
     * @throws IOException En cas d'erreur lors de la lecture du fichier
     */
    private void loadConfiguration(String configPath) throws IOException {
        LOGGER.info("Chargement de la configuration depuis: " + configPath);

        try (FileInputStream inputStream = new FileInputStream(configPath)) {
            config.load(inputStream);
        }

        // Récupérer les paramètres réseau
        bindAddress = config.getProperty("server.ip", "0.0.0.0");
        port = Integer.parseInt(config.getProperty("server.port", "12345"));
        maxConnections = Integer.parseInt(config.getProperty("server.maxConnections", "100"));
        connectionTimeout = Integer.parseInt(config.getProperty("server.timeoutSeconds", "300")) * 1000;
        tlsEnabled = Boolean.parseBoolean(config.getProperty("tls.enabled", "false"));

        // Validation des paramètres réseau
        if (!"0.0.0.0".equals(bindAddress) && !"localhost".equals(bindAddress) && !bindAddress.equals("127.0.0.1")) {
            try {
                InetAddress.getByName(bindAddress);
            } catch (UnknownHostException e) {
                LOGGER.warning("Adresse de liaison invalide: " + bindAddress + ". Utilisation de 0.0.0.0 à la place.");
                bindAddress = "0.0.0.0";
            }
        }

        LOGGER.info("Configuration chargée: bindAddress=" + bindAddress + ", port=" + port +
                ", maxConnections=" + maxConnections + ", connectionTimeout=" + connectionTimeout/1000 + "s");
    }

    /**
     * Configure le système de journalisation
     *
     * @throws IOException En cas d'erreur lors de la création du dossier de logs
     */
    private void setupLogging() throws IOException {
        // Créer le dossier de logs s'il n'existe pas
        Path logDir = Paths.get("logs");
        if (!Files.exists(logDir)) {
            Files.createDirectories(logDir);
        }

        // Configurer le fichier de log
        FileHandler fileHandler = new FileHandler("logs/server_%g.log", 5242880, 5, true);
        fileHandler.setFormatter(new SimpleFormatter() {
            @Override
            public synchronized String format(java.util.logging.LogRecord lr) {
                return String.format("[%1$tF %1$tT] [%2$-7s] [%3$s] %4$s %n",
                        new java.util.Date(lr.getMillis()),
                        lr.getLevel().getName(),
                        lr.getLoggerName(),
                        lr.getMessage()
                );
            }
        });
        LOGGER.addHandler(fileHandler);
        LOGGER.setLevel(Level.INFO);

        // Configurer le niveau de log pour les classes réseau
        Logger.getLogger("sun.net").setLevel(Level.FINE);
        Logger.getLogger("javax.net").setLevel(Level.FINE);
    }

    /**
     * Initialise le pool de connexions à la base de données avec MySQL Connector/J
     *
     * @throws Exception En cas d'erreur lors de l'initialisation de la source de données
     */
    private void initializeDataSource() throws Exception {
        LOGGER.info("Initialisation de la source de données MySQL");

        // Extraction et correction des configurations de base de données
        String jdbcUrl = config.getProperty("db.url");
        if (jdbcUrl.startsWith("db.url=")) {
            jdbcUrl = jdbcUrl.substring(7); // Enlever le préfixe "db.url="
            LOGGER.warning("Détection d'un préfixe incorrect dans db.url. Valeur corrigée: " + jdbcUrl);
        }

        String username = config.getProperty("db.user");
        String password = config.getProperty("db.password");
        int maxPoolSize = Integer.parseInt(config.getProperty("db.pool.max", "20"));

        // Utiliser MysqlConnectionPoolDataSource pour un simple pooling
        MysqlConnectionPoolDataSource mysqlDS = new MysqlConnectionPoolDataSource();

        // Configurer les paramètres de connexion
        mysqlDS.setUrl(jdbcUrl);
        mysqlDS.setUser(username);
        mysqlDS.setPassword(password);

        // Configurer les paramètres supplémentaires
        mysqlDS.setUseSSL(Boolean.parseBoolean(config.getProperty("db.useSSL", "false")));
        mysqlDS.setAllowPublicKeyRetrieval(true);
        mysqlDS.setAutoReconnect(true);
        mysqlDS.setCachePrepStmts(true);
        mysqlDS.setPrepStmtCacheSize(250);
        mysqlDS.setPrepStmtCacheSqlLimit(2048);
        mysqlDS.setConnectTimeout(30000);

        dataSource = mysqlDS;
        LOGGER.info("Source de données MySQL initialisée - URL: " + jdbcUrl);

        // Tester la connexion
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(5)) {
                LOGGER.info("Test de connexion à la base de données réussi");
            } else {
                LOGGER.severe("Test de connexion à la base de données échoué");
                throw new SQLException("Échec du test de connexion à la base de données");
            }
        }
    }

    /**
     * Vérifie l'existence et l'intégrité de la base de données
     */
    private void checkDatabase() {
        LOGGER.info("Vérification de la base de données");

        try {
            // Tester la connexion
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(5)) {
                    LOGGER.info("Connexion à la base de données réussie");

                    // Vérifier si un compte admin existe déjà
                    if (!Models.adminExists()) {
                        LOGGER.info("Création du compte administrateur par défaut");
                        Models.createDefaultAdminAccount("admin", "123", "Administrateur");
                    }
                } else {
                    LOGGER.severe("Échec de la connexion à la base de données");
                    throw new Exception("Impossible de se connecter à la base de données");
                }
            }

            // Démarrer l'export automatique
            boolean autoExportEnabled = Boolean.parseBoolean(config.getProperty("db.autoExport", "true"));
            if (autoExportEnabled) {
                int exportInterval = Integer.parseInt(config.getProperty("db.exportInterval", "24"));
                exportUtil.scheduleAutomaticExport(exportInterval);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur lors de la vérification de la base de données", e);
        }
    }

    /**
     * Démarrer le serveur et attendre les connexions clients
     */
    public void start() {
        if (running) {
            LOGGER.warning("Le serveur est déjà en cours d'exécution");
            return;
        }

        try {
            // Initialiser le pool de threads
            threadPool = Executors.newFixedThreadPool(maxConnections);

            // Créer le socket serveur (normal ou SSL selon configuration)
            if (tlsEnabled) {
                initializeSSLServerSocket();
            } else {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(bindAddress, port));
                serverSocket.setSoTimeout(10000); // Timeout pour permettre l'arrêt propre
            }

            running = true;

            // Afficher les informations réseau et effectuer un diagnostic
            displayNetworkInfo();

            // Boucle principale d'acceptation des connexions
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(connectionTimeout);

                    if (running) {
                        LOGGER.info("Nouvelle connexion cliente depuis " + clientSocket.getInetAddress() + ":" +
                                clientSocket.getPort() + " vers " + clientSocket.getLocalAddress() + ":" +
                                clientSocket.getLocalPort());
                        ClientHandler clientHandler = new ClientHandler(clientSocket, dataSource);
                        threadPool.execute(clientHandler);
                    } else {
                        clientSocket.close();
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout acceptConnection, simplement continuer pour vérifier la condition running
                } catch (IOException e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Erreur lors de l'acceptation de connexion cliente", e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Erreur lors du démarrage du serveur", e);
        } finally {
            cleanup();
        }
    }

    /**
     * Affiche les informations réseau et effectue un diagnostic
     */
    private void displayNetworkInfo() {
        try {
            // Informations sur l'interface réseau
            LOGGER.info("Serveur BMO démarré sur " + bindAddress + ":" + port +
                    (tlsEnabled ? " (avec TLS)" : ""));

            // Afficher les adresses IP disponibles pour les connexions
            LOGGER.info("Pour vous connecter au serveur BMO depuis une autre machine sur le réseau, utilisez:");
            try {
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                boolean foundAddress = false;

                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = networkInterfaces.nextElement();

                    // Ignorer les interfaces non actives ou loopback
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                        continue;
                    }

                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress address = inetAddresses.nextElement();

                        // Ignorer les adresses loopback et IPv6
                        if (address.isLoopbackAddress() || address instanceof Inet6Address) {
                            continue;
                        }

                        LOGGER.info("  - telnet " + address.getHostAddress() + " " + port);
                        foundAddress = true;
                    }
                }

                if (!foundAddress) {
                    LOGGER.info("  - Aucune adresse réseau valide trouvée. Vérifiez votre connexion réseau.");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Impossible de lister les interfaces réseau", e);
            }

            // Vérification du pare-feu pour Windows
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                LOGGER.info("Système d'exploitation Windows détecté. Vérifiez que le port " + port +
                        " est autorisé dans le pare-feu Windows:");
                LOGGER.info("  - Panneau de configuration > Système et sécurité > Pare-feu Windows > Paramètres avancés");
                LOGGER.info("  - Règles de trafic entrant > Nouvelle règle > Port > TCP > Port spécifique: " + port);
            } else if (os.contains("linux") || os.contains("unix")) {
                LOGGER.info("Système d'exploitation Linux/Unix détecté. Vérifiez que le port " + port +
                        " est autorisé dans votre pare-feu (ex: iptables, ufw, firewalld).");
                LOGGER.info("  - Pour iptables: sudo iptables -A INPUT -p tcp --dport " + port + " -j ACCEPT");
                LOGGER.info("  - Pour ufw: sudo ufw allow " + port + "/tcp");
            } else if (os.contains("mac")) {
                LOGGER.info("Système d'exploitation macOS détecté. Vérifiez les paramètres du pare-feu dans:");
                LOGGER.info("  - Préférences Système > Sécurité et confidentialité > Pare-feu > Options du pare-feu");
            }

            // Exécuter des tests de diagnostic réseau une seule fois si le serveur est lancé sur 0.0.0.0
            if (!networkDiagnosticsRun && "0.0.0.0".equals(bindAddress)) {
                networkDiagnosticsRun = true;
                runNetworkDiagnostics();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de l'affichage des informations réseau", e);
        }
    }

    /**
     * Exécute des diagnostics réseau pour aider au dépannage
     */
    private void runNetworkDiagnostics() {
        try {
            LOGGER.info("Exécution de diagnostics réseau...");

            // Vérifier si le port est déjà utilisé
            try {
                Socket testSocket = new Socket();
                testSocket.connect(new InetSocketAddress("localhost", port), 1000);
                testSocket.close();
                LOGGER.warning("Le port " + port + " est déjà utilisé par une autre application. " +
                        "Ceci est anormal car notre serveur vient de démarrer sur ce port.");
            } catch (ConnectException e) {
                LOGGER.info("Le port " + port + " est disponible et fonctionne correctement localement.");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors du test de disponibilité du port " + port, e);
            }

            // Vérifier la résolution DNS
            try {
                InetAddress localHost = InetAddress.getLocalHost();
                LOGGER.info("Nom de l'hôte local: " + localHost.getHostName());
                LOGGER.info("Adresse IP locale: " + localHost.getHostAddress());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de la récupération des informations d'hôte local", e);
            }

            // Si c'est un système de type Unix, essayer d'exécuter netstat
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux") || os.contains("unix") || os.contains("mac")) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("netstat", "-an");
                    Process p = pb.start();
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.contains(":" + port) && line.contains("LISTEN")) {
                                LOGGER.info("netstat confirme que le port " + port + " est en écoute: " + line);
                            }
                        }
                    }
                    p.waitFor(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Impossible d'exécuter netstat", e);
                }
            }

            LOGGER.info("Diagnostics réseau terminés.");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Erreur lors de l'exécution des diagnostics réseau", e);
        }
    }

    /**
     * Initialise un ServerSocket SSL/TLS pour les connexions sécurisées
     *
     * @throws IOException En cas d'erreur lors de l'initialisation
     */
    private void initializeSSLServerSocket() throws IOException {
        try {
            // Charger les paramètres TLS
            String keyStorePath = config.getProperty("tls.keyStore");
            String keyStorePassword = config.getProperty("tls.keyStorePassword");
            String keyStoreType = config.getProperty("tls.keyStoreType", "JKS");

            // Vérifier l'existence du keystore
            File keyStoreFile = new File(keyStorePath);
            if (!keyStoreFile.exists() || !keyStoreFile.isFile()) {
                LOGGER.severe("Keystore introuvable: " + keyStorePath);
                throw new IOException("Keystore introuvable: " + keyStorePath);
            }

            // Créer et initialiser le contexte SSL
            KeyStore keyStore = KeyStore.getInstance(keyStoreType);
            try (FileInputStream fis = new FileInputStream(keyStorePath)) {
                keyStore.load(fis, keyStorePassword.toCharArray());
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyStorePassword.toCharArray());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            // Créer le socket serveur SSL
            SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
            SSLServerSocket sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
            sslServerSocket.setReuseAddress(true);
            sslServerSocket.bind(new InetSocketAddress(bindAddress, port));
            sslServerSocket.setSoTimeout(10000);

            // Définir les suites de chiffrement et protocoles acceptés
            String[] enabledProtocols = config.getProperty("tls.enabledProtocols", "TLSv1.2,TLSv1.3").split(",");
            sslServerSocket.setEnabledProtocols(enabledProtocols);

            // Exiger l'authentification du client si configuré
            boolean clientAuth = Boolean.parseBoolean(config.getProperty("tls.clientAuth", "false"));
            if (clientAuth) {
                sslServerSocket.setNeedClientAuth(true);
            }

            serverSocket = sslServerSocket;
            LOGGER.info("Socket serveur SSL/TLS initialisé avec succès");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Échec de l'initialisation du socket serveur SSL/TLS", e);
            throw new IOException("Échec de l'initialisation du socket serveur SSL/TLS", e);
        }
    }

    /**
     * Arrête le serveur et libère les ressources
     */
    public void stop() {
        LOGGER.info("Arrêt du serveur BMO...");
        running = false;

        // Nettoyer les ressources
        cleanup();

        LOGGER.info("Serveur BMO arrêté");
    }

    /**
     * Nettoie les ressources du serveur
     */
    private void cleanup() {
        // Fermer le socket serveur
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Erreur lors de la fermeture du socket serveur", e);
            }
        }

        // Arrêter le pool de threads
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Arrêter l'export automatique
        if (exportUtil != null) {
            exportUtil.stopAutomaticExport();
        }

        // Effectuer un export de la base de données avant de fermer
        if (exportUtil != null) {
            try {
                exportUtil.exportDatabase();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Erreur lors de l'export de la base de données à la fermeture", e);
            }
        }

        LOGGER.info("Ressources nettoyées");
    }

    /**
     * Crée une sauvegarde de la base de données
     *
     * @return true si la sauvegarde a été effectuée avec succès
     */
    public boolean backupDatabase() {
        return exportUtil.exportDatabase();
    }

    /**
     * Point d'entrée principal
     *
     * @param args Arguments de la ligne de commande
     */
    public static void main(String[] args) {
        try {
            // Afficher une bannière de démarrage
            displayBanner();

            // Déterminer le chemin du fichier de configuration
            String configPath = DEFAULT_CONFIG_PATH;
            boolean debugMode = false;

            // Analyser les arguments
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--config") && i + 1 < args.length) {
                    configPath = args[i + 1];
                    i++; // Sauter le prochain argument
                } else if (args[i].equals("--debug")) {
                    debugMode = true;
                    // Activer les logs de débogage
                    Logger.getLogger("com.bmo.server").setLevel(Level.FINE);
                }
            }

            // Vérifier que le fichier de configuration existe
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                LOGGER.severe("Fichier de configuration introuvable: " + configPath);

                // Créer un fichier de configuration par défaut si possible
                Path configDir = Paths.get(configPath).getParent();
                if (Files.exists(configDir) || Files.createDirectories(configDir) != null) {
                    createDefaultConfigFile(configPath);
                    LOGGER.info("Un fichier de configuration par défaut a été créé à: " + configPath);
                } else {
                    LOGGER.severe("Impossible de créer le fichier de configuration par défaut");
                    System.exit(1);
                }
            }

            // Créer et démarrer le serveur
            MainServer server = new MainServer(configPath);

            // Ajouter un hook d'arrêt pour la fermeture propre
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOGGER.info("Signal d'arrêt reçu (Ctrl+C ou arrêt du système)");
                server.stop();
            }));

            // Démarrer le serveur
            server.start();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Erreur lors du démarrage du serveur", e);
            System.exit(1);
        }
    }

    /**
     * Crée un fichier de configuration par défaut
     * @param configPath Chemin du fichier de configuration
     * @throws IOException En cas d'erreur lors de la création du fichier
     */
    private static void createDefaultConfigFile(String configPath) throws IOException {
        Properties defaultConfig = new Properties();
        defaultConfig.setProperty("server.ip", "0.0.0.0");
        defaultConfig.setProperty("server.port", "12345");
        defaultConfig.setProperty("server.maxConnections", "100");
        defaultConfig.setProperty("server.timeoutSeconds", "300");
        defaultConfig.setProperty("tls.enabled", "false");
        defaultConfig.setProperty("db.url", "jdbc:mysql://localhost:3306/bmo_db?useSSL=false&serverTimezone=UTC");
        defaultConfig.setProperty("db.user", "bmo_user");
        defaultConfig.setProperty("db.password", "bmo_password");
        defaultConfig.setProperty("db.pool.max", "20");
        defaultConfig.setProperty("db.useSSL", "false");
        defaultConfig.setProperty("db.autoExport", "true");
        defaultConfig.setProperty("db.exportInterval", "24");

        try (FileOutputStream fos = new FileOutputStream(configPath)) {
            defaultConfig.store(fos, "Configuration par défaut du serveur BMO");
        }
    }

    /**
     * Affiche une bannière de démarrage
     */
    private static void displayBanner() {
        System.out.println("\n" +
                "  ____  __  __  ___   \n" +
                " | __ )|  \\/  |/ _ \\ \n" +
                " |  _ \\| |\\/| | | | |\n" +
                " | |_) | |  | | |_| |\n" +
                " |____/|_|  |_|\\___/ \n" +
                "                     \n" +
                " Bureau de Médiation en Ligne - Serveur v1.0\n" +
                " (c) 2025 BMO Team\n"
        );
    }
}
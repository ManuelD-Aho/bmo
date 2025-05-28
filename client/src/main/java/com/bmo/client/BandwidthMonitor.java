package com.bmo.client;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Moniteur de bande passante pour l'application BMO
 */
public class BandwidthMonitor {
    // Compteurs de trafic
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);

    // Dernières valeurs pour le calcul de la vitesse
    private long lastBytesReceived = 0;
    private long lastBytesSent = 0;
    private long lastTimestamp = System.currentTimeMillis();

    // Statistiques de vitesse en bytes/seconde
    private double downloadSpeed = 0;
    private double uploadSpeed = 0;

    private final Timer statsTimer;
    private BiConsumer<Double, Double> speedUpdateCallback;

    /**
     * Constructeur
     *
     * @param updateInterval Intervalle en ms entre les mises à jour des statistiques
     */
    public BandwidthMonitor(long updateInterval) {
        statsTimer = new Timer("BandwidthMonitor", true);
        statsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                updateStats();
            }
        }, updateInterval, updateInterval);
    }

    /**
     * Met à jour les statistiques de vitesse
     */
    private void updateStats() {
        long now = System.currentTimeMillis();
        long currentBytesReceived = bytesReceived.get();
        long currentBytesSent = bytesSent.get();

        long timeElapsed = now - lastTimestamp;

        if (timeElapsed > 0) {
            downloadSpeed = (currentBytesReceived - lastBytesReceived) * 1000.0 / timeElapsed;
            uploadSpeed = (currentBytesSent - lastBytesSent) * 1000.0 / timeElapsed;

            lastBytesReceived = currentBytesReceived;
            lastBytesSent = currentBytesSent;
            lastTimestamp = now;

            if (speedUpdateCallback != null) {
                speedUpdateCallback.accept(downloadSpeed, uploadSpeed);
            }
        }
    }

    /**
     * Enregistre la réception de données
     *
     * @param bytes Nombre d'octets reçus
     */
    public void addBytesReceived(long bytes) {
        bytesReceived.addAndGet(bytes);
    }

    /**
     * Enregistre l'envoi de données
     *
     * @param bytes Nombre d'octets envoyés
     */
    public void addBytesSent(long bytes) {
        bytesSent.addAndGet(bytes);
    }

    /**
     * Définit le callback pour les mises à jour de vitesse
     *
     * @param callback Le callback à appeler avec les vitesses de download et upload en bytes/seconde
     */
    public void setSpeedUpdateCallback(BiConsumer<Double, Double> callback) {
        this.speedUpdateCallback = callback;
    }

    /**
     * Obtient le total d'octets reçus
     */
    public long getTotalBytesReceived() {
        return bytesReceived.get();
    }

    /**
     * Obtient le total d'octets envoyés
     */
    public long getTotalBytesSent() {
        return bytesSent.get();
    }

    /**
     * Obtient la vitesse de téléchargement actuelle en bytes/seconde
     */
    public double getDownloadSpeed() {
        return downloadSpeed;
    }

    /**
     * Obtient la vitesse d'envoi actuelle en bytes/seconde
     */
    public double getUploadSpeed() {
        return uploadSpeed;
    }

    /**
     * Arrête le moniteur
     */
    public void shutdown() {
        if (statsTimer != null) {
            statsTimer.cancel();
        }
    }
}
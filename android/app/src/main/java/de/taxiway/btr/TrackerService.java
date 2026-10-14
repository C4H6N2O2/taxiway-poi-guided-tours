package de.taxiway.btr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vordergrund-Dienst: liest GPS auch bei ausgeschaltetem Bildschirm und meldet die Position
 * an Bushtalk Radio – genau wie der offizielle X-Plane-Client (POST /api/track).
 */
public class TrackerService extends Service {

    public static final String ACTION_STOP = "de.taxiway.btr.STOP";
    private static final String CHANNEL = "tracking";
    private static final int NOTIF_ID = 1;
    private static final long STALE_MS = 30_000;       // ältere Fixes werden nicht gesendet
    private static final long GPS_PREFERRED_MS = 15_000; // so lange gilt GPS als "aktuell" vor WLAN/Mobilfunk

    // Status für die Oberfläche (einfach & robust: die Activity liest diese Werte jede Sekunde)
    public static volatile boolean running = false;
    public static volatile int sentCount = 0;
    public static volatile long lastSentAt = 0;
    public static volatile String lastStatus = "";
    public static volatile Location lastFix = null;

    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private volatile boolean sending = false;
    private long lastAttempt = 0;
    private long lastGpsAt = 0;
    private Location prevForBearing = null;

    private final LocationListener gpsListener = new Listener(true);
    private final LocationListener netListener = new Listener(false);

    private class Listener implements LocationListener {
        private final boolean gps;
        Listener(boolean gps) { this.gps = gps; }

        @Override public void onLocationChanged(Location loc) { handleFix(loc, gps); }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {
            if (gps) lastStatus = "GPS ist ausgeschaltet";
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;

        createChannel();
        Notification n = buildNotification("Startet…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            // z. B. Neustart durch das System ohne sichtbare App: Android 14 verbietet das für Standortdienste
            lastStatus = "Bitte App öffnen und Start drücken";
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "taxiway:send");
        wakeLock.setReferenceCounted(false);

        running = true;
        sentCount = 0;
        lastSentAt = 0;
        lastFix = null;
        lastStatus = "Warte auf GPS…";

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        long intervalMs = Prefs.intervalSec(this) * 1000L;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, gpsListener, Looper.getMainLooper());
            } else {
                lastStatus = "GPS ist ausgeschaltet – bitte in den Schnelleinstellungen aktivieren";
            }
            // Fallback für Tunnel/Zug: WLAN/Mobilfunk, aber seltener
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Math.max(intervalMs, 15_000L), 0f, netListener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            lastStatus = "Keine Standort-Berechtigung";
            stopSelf();
        }
        return START_STICKY;
    }

    private void handleFix(Location loc, boolean fromGps) {
        long now = System.currentTimeMillis();
        if (fromGps) {
            lastGpsAt = now;
        } else if (now - lastGpsAt < GPS_PREFERRED_MS) {
            return; // frisches GPS vorhanden – ungenauere Netzposition ignorieren
        }
        if (!loc.hasBearing() && prevForBearing != null && prevForBearing.distanceTo(loc) > 10) {
            float b = prevForBearing.bearingTo(loc);
            loc.setBearing(b < 0 ? b + 360 : b);
        }
        if (prevForBearing == null || prevForBearing.distanceTo(loc) > 10) prevForBearing = loc;
        lastFix = loc;
        maybeSend();
    }

    private void maybeSend() {
        final Location fix = lastFix;
        if (fix == null || sending) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < Prefs.intervalSec(this) * 1000L - 300) return;
        if (now - fix.getTime() > STALE_MS) { lastStatus = "GPS veraltet – sende nichts"; return; }

        final String token = Prefs.token(this);
        if (token.isEmpty()) { lastStatus = "Nicht angemeldet"; return; }

        lastAttempt = now;
        sending = true;
        wakeLock.acquire(15_000);   // CPU während der Anfrage wach halten, danach wieder schlafen lassen
        final String body = payload(fix);
        net.execute(() -> {
            try {
                int code = post(Prefs.API + "/api/track", body, token);
                if (code == 200 || code == 201) {
                    sentCount++;
                    lastSentAt = System.currentTimeMillis();
                    lastStatus = String.format(Locale.GERMANY, "läuft · ±%.0f m%s",
                            fix.getAccuracy(), LocationManager.GPS_PROVIDER.equals(fix.getProvider()) ? " (GPS)" : " (Netz)");
                } else if (code == 401) {
                    lastStatus = "Sitzung abgelaufen – bitte neu anmelden";
                    Prefs.setToken(this, "");
                } else {
                    lastStatus = "BTR-Server antwortet " + code;
                }
            } catch (Exception e) {
                lastStatus = "Kein Netz – versuche weiter";
            } finally {
                sending = false;
                if (wakeLock.isHeld()) wakeLock.release();
                updateNotification();
            }
        });
    }

    private String payload(Location l) {
        double knots = l.hasSpeed() ? l.getSpeed() * 1.94384 : 0;
        double hdg = l.hasBearing() ? l.getBearing() : 0;
        return String.format(Locale.US,
                "{\"PLANE_LATITUDE\":%.7f,\"PLANE_LONGITUDE\":%.7f,\"ALTITUDE_ABOVE_GROUND\":%d," +
                "\"GROUND_VELOCITY\":%.1f,\"MAGNETIC_COMPASS\":%.1f,\"ATC_ID\":\"%s\",\"SIM_ON_GROUND\":%s}",
                l.getLatitude(), l.getLongitude(), Prefs.altFt(this), knots, hdg,
                Prefs.tail(this).replace("\"", ""), Prefs.onGround(this) ? "true" : "false");
    }

    /** Einfacher JSON-POST. Gibt den HTTP-Status zurück, Antwort-Body landet in out[0] (falls übergeben). */
    static int post(String url, String json, String token, String... out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-BTR-CLIENT-NAME", Prefs.CLIENT_NAME);
            c.setRequestProperty("X-BTR-CLIENT-VERSION", Prefs.CLIENT_VERSION);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                is.close();
                if (out.length > 0) out[0] = sb.toString();
            }
            return code;
        } finally {
            c.disconnect();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Tracking", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TrackerService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Taxiway – " + Prefs.tail(this) + " sendet an BTR")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pOpen)
                .addAction(android.R.drawable.ic_media_pause, "Stoppen", pStop)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && running) nm.notify(NOTIF_ID, buildNotification(sentCount + " gesendet · " + lastStatus));
    }

    @Override
    public void onDestroy() {
        running = false;
        lastStatus = "gestoppt";
        if (lm != null) {
            lm.removeUpdates(gpsListener);
            lm.removeUpdates(netListener);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        net.shutdownNow();
        super.onDestroy();
    }

    // ---------- Einstellungen ----------
    static final class Prefs {
        static final String API = "https://bushtalkradio.com";
        static final String CLIENT_NAME = "ROADTRIP-ANDROID";
        static final String CLIENT_VERSION = "1.0.0";

        static SharedPreferences sp(Context c) { return c.getSharedPreferences("taxiway", Context.MODE_PRIVATE); }
        static String token(Context c)   { return sp(c).getString("token", ""); }
        static void setToken(Context c, String t) { sp(c).edit().putString("token", t).apply(); }
        static String user(Context c)    { return sp(c).getString("user", ""); }
        static String tail(Context c)    { return sp(c).getString("tail", "AUTO-1"); }
        static int altFt(Context c)      { return sp(c).getInt("alt", 1000); }
        static int intervalSec(Context c){ return sp(c).getInt("interval", 5); }
        static boolean onGround(Context c){ return sp(c).getBoolean("ground", false); }
    }
}

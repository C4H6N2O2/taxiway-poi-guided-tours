package de.taxiway.btr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bedienoberfläche: Start/Stopp, Status, Einstellungen. Die BTR-Karte (mit den Ansagen)
 * läuft im Standardbrowser (z. B. Firefox) – dort ist der Login vorhanden und alle Funktionen gehen.
 */
public class MainActivity extends Activity {

    private static final String MAP_URL = "https://bushtalkradio.com/map";
    private static final int REQ_PERMS = 1;
    private static final int BG = Color.rgb(11, 15, 20), CARD = Color.rgb(20, 26, 34),
            TEXT = Color.rgb(230, 237, 243), MUTED = Color.rgb(139, 152, 167),
            ACCENT = Color.rgb(245, 165, 36), RED = Color.rgb(248, 81, 73),
            OK = Color.rgb(63, 185, 80), WARN = Color.rgb(210, 153, 34), BTN = Color.rgb(34, 43, 54);

    private TextView stateLine, bigSpeed, detail, account;
    private Button startStop;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean openMapAfterStart = false;

    private final Runnable refresher = new Runnable() {
        @Override public void run() { refreshStatus(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Taxiway");
        title.setTextColor(TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Dein Auto als Flugzeug bei Bushtalk Radio");
        sub.setTextColor(MUTED);
        sub.setTextSize(14);
        sub.setPadding(0, 0, 0, dp(16));
        root.addView(sub);

        // --- Statuskarte ---
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(CARD, 16));
        stateLine = text(15, TEXT, true);
        bigSpeed = text(44, TEXT, true);
        bigSpeed.setPadding(0, dp(6), 0, 0);
        TextView unit = text(13, MUTED, false);
        unit.setText("km/h");
        detail = text(14, MUTED, false);
        detail.setPadding(0, dp(10), 0, 0);
        account = text(13, MUTED, false);
        account.setPadding(0, dp(8), 0, 0);
        card.addView(stateLine);
        card.addView(bigSpeed);
        card.addView(unit);
        card.addView(detail);
        card.addView(account);
        root.addView(card, matchWrap(0));

        // --- Knöpfe ---
        startStop = button("Start", ACCENT, Color.BLACK, 18);
        startStop.setOnClickListener(v -> toggleTracking());
        root.addView(startStop, fixed(60, 16));

        Button map = button("BTR-Karte im Browser öffnen", BTN, TEXT, 16);
        map.setOnClickListener(v -> openMapInBrowser());
        root.addView(map, fixed(52, 10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button settings = button("Optionen", BTN, TEXT, 15);
        settings.setOnClickListener(v -> showSettings());
        Button battery = button("Akku-Ausnahme", BTN, TEXT, 15);
        battery.setOnClickListener(v -> askBatteryExemption());
        row.addView(settings, weight(1f, 0, 5));
        row.addView(battery, weight(1f, 5, 0));
        root.addView(row, matchWrap(10));

        TextView hint = text(13, MUTED, false);
        hint.setText("So geht's: Start drücken → Karte im Browser öffnen → dort angemeldet bleiben. " +
                "Die App sendet deine Position im Hintergrund weiter, auch bei ausgeschaltetem Bildschirm. " +
                "Die Ansagen spielt der Browser ab. Tipp: BTR-Radio leise mitlaufen lassen, dann bleibt der Browser aktiv.");
        hint.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(hint);

        setContentView(scroll);
        if (TrackerService.Prefs.token(this).isEmpty()) showSettings();
    }

    // ---------- Tracking ----------
    private void toggleTracking() {
        if (TrackerService.running) {
            startService(new Intent(this, TrackerService.class).setAction(TrackerService.ACTION_STOP));
            return;
        }
        if (TrackerService.Prefs.token(this).isEmpty()) { showSettings(); return; }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), REQ_PERMS); return; }
        startTracker();
    }

    private void startTracker() {
        Intent i = new Intent(this, TrackerService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        if (TrackerService.Prefs.sp(this).getBoolean("autoMap", true)) {
            openMapAfterStart = true;
            ui.postDelayed(() -> { if (openMapAfterStart) { openMapAfterStart = false; openMapInBrowser(); } }, 1200);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startTracker();
        } else if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Nur ungefährer Standort erlaubt – bitte „Genau“ wählen, sonst stimmen die POIs nicht.", Toast.LENGTH_LONG).show();
            startTracker();
        } else {
            Toast.makeText(this, "Ohne Standort-Berechtigung geht es nicht.", Toast.LENGTH_LONG).show();
        }
    }

    /** Öffnet die Karte im Standardbrowser des Handys (z. B. Firefox) – dort ist der BTR-Login vorhanden. */
    private void openMapInBrowser() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(MAP_URL));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "Kein Browser gefunden", Toast.LENGTH_LONG).show(); }
    }

    private void refreshStatus() {
        boolean on = TrackerService.running;
        startStop.setText(on ? "Stopp" : "Start");
        startStop.setBackground(rounded(on ? RED : ACCENT, 14));
        startStop.setTextColor(on ? Color.WHITE : Color.BLACK);

        Location f = TrackerService.lastFix;
        String st = TrackerService.lastStatus;
        if (!on) {
            stateLine.setText("● Bereit");
            stateLine.setTextColor(MUTED);
            bigSpeed.setText("–");
            detail.setText("Start drücken, dann darf der Bildschirm aus.");
        } else {
            boolean good = st.startsWith("läuft");
            stateLine.setText("● " + st);
            stateLine.setTextColor(good ? OK : WARN);
            if (f != null) {
                long age = (System.currentTimeMillis() - f.getTime()) / 1000;
                bigSpeed.setText(String.format(Locale.GERMANY, "%.0f", f.hasSpeed() ? f.getSpeed() * 3.6 : 0.0));
                long sentAgo = TrackerService.lastSentAt == 0 ? -1 : (System.currentTimeMillis() - TrackerService.lastSentAt) / 1000;
                detail.setText(String.format(Locale.GERMANY, "%d gesendet%s · GPS-Fix vor %d s\n%.4f, %.4f",
                        TrackerService.sentCount, sentAgo >= 0 ? " (zuletzt vor " + sentAgo + " s)" : "",
                        age, f.getLatitude(), f.getLongitude()));
            } else {
                bigSpeed.setText("–");
                detail.setText("Warte auf das erste GPS-Signal…");
            }
        }
        String user = TrackerService.Prefs.user(this);
        account.setText(String.format(Locale.GERMANY, "%s · %s · %d ft · alle %d s",
                user.isEmpty() ? "nicht angemeldet" : user, TrackerService.Prefs.tail(this),
                TrackerService.Prefs.altFt(this), TrackerService.Prefs.intervalSec(this)));
    }

    // ---------- Einstellungen & Login ----------
    private void showSettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        sv.addView(f);

        EditText user = field(f, "BTR-Benutzername", TrackerService.Prefs.user(this), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(f, "BTR-Passwort (nur zum Anmelden, wird nicht gespeichert)", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText tail = field(f, "Kennung (ATC_ID)", TrackerService.Prefs.tail(this), InputType.TYPE_CLASS_TEXT);
        EditText alt = field(f, "Gemeldete Höhe (ft)", String.valueOf(TrackerService.Prefs.altFt(this)), InputType.TYPE_CLASS_NUMBER);
        EditText interval = field(f, "Sende-Intervall (Sekunden, 5–60)", String.valueOf(TrackerService.Prefs.intervalSec(this)), InputType.TYPE_CLASS_NUMBER);
        CheckBox ground = new CheckBox(this);
        ground.setText("Als „am Boden“ melden");
        ground.setChecked(TrackerService.Prefs.onGround(this));
        f.addView(ground);
        CheckBox autoMap = new CheckBox(this);
        autoMap.setText("Nach dem Start automatisch die Karte im Browser öffnen");
        autoMap.setChecked(TrackerService.Prefs.sp(this).getBoolean("autoMap", true));
        f.addView(autoMap);

        new AlertDialog.Builder(this)
                .setTitle("Optionen")
                .setView(sv)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (d, w) -> {
                    int a = parse(alt.getText().toString(), 1000, 0, 60000);
                    int iv = parse(interval.getText().toString(), 5, 5, 60);
                    String t = tail.getText().toString().trim();
                    TrackerService.Prefs.sp(this).edit()
                            .putString("tail", t.isEmpty() ? "AUTO-1" : t)
                            .putInt("alt", a).putInt("interval", iv)
                            .putBoolean("ground", ground.isChecked())
                            .putBoolean("autoMap", autoMap.isChecked())
                            .apply();
                    String u = user.getText().toString().trim(), p = pass.getText().toString();
                    if (!u.isEmpty() && !p.isEmpty()) login(u, p);
                    else Toast.makeText(this, TrackerService.running ? "Gespeichert – gilt ab dem nächsten Start." : "Gespeichert.", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void login(String u, String p) {
        Toast.makeText(this, "Melde bei BTR an…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                JSONObject req = new JSONObject().put("username", u).put("password", p);
                String[] out = new String[1];
                int code = TrackerService.post(TrackerService.Prefs.API + "/api/authenticate", req.toString(), null, out);
                if (code == 200 && out[0] != null) {
                    JSONObject r = new JSONObject(out[0]);
                    String token = r.optString("id_token", "");
                    if (token.isEmpty()) throw new Exception("Antwort ohne Token");
                    TrackerService.Prefs.sp(this).edit().putString("token", token)
                            .putString("user", r.optString("username", u)).apply();
                    msg = "Angemeldet als " + r.optString("username", u);
                } else {
                    msg = "Login abgelehnt (" + code + ") – Zugangsdaten prüfen";
                }
            } catch (Exception e) {
                msg = "Login fehlgeschlagen: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }

    // Xiaomi/HyperOS beendet Hintergrund-Apps aggressiv – Ausnahme von der Akku-Optimierung anfragen
    @SuppressLint("BatteryLife")
    private void askBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Akku-Optimierung ist schon aus. Bei Xiaomi zusätzlich: App-Info → Akku → „Keine Einschränkungen“ + Autostart.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } else {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        }
    }

    // ---------- Lebenszyklus ----------
    @Override protected void onResume() { super.onResume(); ui.post(refresher); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); }

    // ---------- kleine UI-Helfer ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams fixed(int heightDp, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weight(float w, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), w);
        lp.setMargins(dp(leftDp), 0, dp(rightDp), 0);
        return lp;
    }

    private Button button(String t, int bg, int fg, int sp) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(sp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(bg, 14));
        b.setTextColor(fg);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12);
        l.setPadding(0, dp(10), 0, 0);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(value);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private static int parse(String s, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(s.trim()))); }
        catch (Exception e) { return def; }
    }
}

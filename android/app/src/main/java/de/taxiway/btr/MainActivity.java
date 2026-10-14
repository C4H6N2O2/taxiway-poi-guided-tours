package de.taxiway.btr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String MAP_URL = "https://bushtalkradio.com/map";
    private static final int REQ_PERMS = 1;
    private static final int BG = Color.rgb(11, 15, 20), CARD = Color.rgb(20, 26, 34),
            TEXT = Color.rgb(230, 237, 243), MUTED = Color.rgb(139, 152, 167),
            ACCENT = Color.rgb(245, 165, 36), RED = Color.rgb(248, 81, 73);

    /**
     * Rüstet APIs nach, die Chrome hat, eine App-WebView aber nicht. Fehlen sie, stürzt die
     * BTR-Seite (Next.js) mit "Application error: a client-side exception" ab.
     */
    private static final String POLYFILL = "(function(){try{" +
            "if(!('Notification' in window)){var N=function(){};N.permission='denied';" +
            "N.requestPermission=function(cb){if(cb)cb('denied');return Promise.resolve('denied');};window.Notification=N;}" +
            "if(!navigator.share){navigator.share=function(){return Promise.reject(new Error('share not supported'));};}" +
            "if(!navigator.canShare){navigator.canShare=function(){return false;};}" +
            "if(!('wakeLock' in navigator)){try{Object.defineProperty(navigator,'wakeLock',{configurable:true,value:{request:function(){" +
            "return Promise.resolve({released:false,type:'screen',release:function(){return Promise.resolve();},addEventListener:function(){},removeEventListener:function(){}});}}});}catch(e){}}" +
            "if(navigator.permissions&&navigator.permissions.query){var q=navigator.permissions.query.bind(navigator.permissions);" +
            "navigator.permissions.query=function(d){return q(d).catch(function(){return {state:'denied',onchange:null,addEventListener:function(){},removeEventListener:function(){}};});};}" +
            "window.addEventListener('error',function(e){console.error('[JS] '+(e.message||e)+' @'+(e.filename||'')+':'+(e.lineno||''));});" +
            "window.addEventListener('unhandledrejection',function(e){var r=e.reason;console.error('[Promise] '+(r&&(r.stack||r.message)||r));});" +
            "}catch(e){console.error('[Polyfill] '+e);}})();";

    private final ArrayDeque<String> webErrors = new ArrayDeque<>();
    private WebView web;
    private TextView status;
    private Button startStop;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override public void run() { refreshStatus(); ui.postDelayed(this, 1000); }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // --- Kopfleiste ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(CARD);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));

        status = new TextView(this);
        status.setTextColor(TEXT);
        status.setTextSize(14);
        bar.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        startStop = button("Start", ACCENT, Color.BLACK);
        startStop.setOnClickListener(v -> toggleTracking());
        Button settings = button("Einstellungen", Color.rgb(34, 43, 54), TEXT);
        settings.setOnClickListener(v -> showSettings());
        Button battery = button("Akku", Color.rgb(34, 43, 54), TEXT);
        battery.setOnClickListener(v -> askBatteryExemption());
        row.addView(startStop, weight(1.2f));
        row.addView(settings, weight(1.4f));
        row.addView(battery, weight(0.9f));
        bar.addView(row);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- BTR-Karte: hier einmal bei BTR anmelden, dann laufen die Ansagen ---
        WebView.setWebContentsDebuggingEnabled(true);   // erlaubt Fehlersuche per chrome://inspect
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setMediaPlaybackRequiresUserGesture(false);   // Ansagen dürfen ohne Antippen starten
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        // Als normales Chrome ausgeben – manche Seiten verhalten sich in "WebViews" anders
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // Fehlende Browser-Funktionen nachrüsten, BEVOR die Seite ihre Skripte startet
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, POLYFILL, Collections.singleton("*"));
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("https") || scheme.equals("http")) return false;   // Login-Flows etc. in der App lassen
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                // Fallback für ältere WebViews ohne Document-Start-Skripte
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(POLYFILL, null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // Die Karte bekommt KEINEN Handy-Standort – die Position kommt ausschließlich über /api/track
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }
            @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }

            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    String line = m.messageLevel().name().charAt(0) + " " + m.message()
                            + (m.sourceId() == null || m.sourceId().isEmpty() ? "" : "  (" + shortSrc(m.sourceId()) + ":" + m.lineNumber() + ")");
                    synchronized (webErrors) {
                        webErrors.addLast(line);
                        while (webErrors.size() > 25) webErrors.removeFirst();
                    }
                }
                return true;
            }
        });
        // Antippen der Statuszeile zeigt die Fehler der Karte
        status.setOnClickListener(v -> showWebErrors());
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        if (b != null) web.restoreState(b); else web.loadUrl(MAP_URL);

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

    private void refreshStatus() {
        boolean on = TrackerService.running;
        startStop.setText(on ? "Stopp" : "Start");
        startStop.setBackgroundColor(on ? RED : ACCENT);
        startStop.setTextColor(on ? Color.WHITE : Color.BLACK);

        StringBuilder s = new StringBuilder();
        String user = TrackerService.Prefs.user(this);
        s.append(TrackerService.Prefs.tail(this)).append(user.isEmpty() ? " · nicht angemeldet" : " · " + user);
        if (on) {
            s.append("\n").append(TrackerService.lastStatus);
            Location f = TrackerService.lastFix;
            if (f != null) {
                long age = (System.currentTimeMillis() - f.getTime()) / 1000;
                s.append(String.format(Locale.GERMANY, "\n%.0f km/h · %d gesendet · Fix vor %d s",
                        f.hasSpeed() ? f.getSpeed() * 3.6 : 0.0, TrackerService.sentCount, age));
            }
        } else {
            s.append("\nBereit – Start drücken, dann darf der Bildschirm aus.");
        }
        status.setText(s.toString());
    }

    // ---------- Fehleranzeige der Karte ----------
    private static String shortSrc(String src) {
        int i = src.lastIndexOf('/');
        String s = i >= 0 ? src.substring(i + 1) : src;
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private void showWebErrors() {
        StringBuilder sb = new StringBuilder();
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        sb.append("WebView: ").append(wv == null ? "?" : wv.packageName + " " + wv.versionName).append("\n\n");
        synchronized (webErrors) {
            if (webErrors.isEmpty()) sb.append("Keine Fehler protokolliert.");
            for (String e : webErrors) sb.append(e).append("\n\n");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Karte – Fehlerprotokoll")
                .setView(sv)
                .setPositiveButton("Karte neu laden", (d, w) -> { synchronized (webErrors) { webErrors.clear(); } web.loadUrl(MAP_URL); })
                .setNeutralButton("Kopieren", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Taxiway Fehler", sb.toString()));
                    Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Schließen", null)
                .show();
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
        CheckBox keepOn = new CheckBox(this);
        keepOn.setText("Bildschirm anlassen, solange die App offen ist");
        keepOn.setChecked(TrackerService.Prefs.sp(this).getBoolean("keepOn", false));
        f.addView(keepOn);

        new AlertDialog.Builder(this)
                .setTitle("Einstellungen")
                .setView(sv)
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Speichern", (d, w) -> {
                    int a = parse(alt.getText().toString(), 1000, 0, 60000);
                    int iv = parse(interval.getText().toString(), 5, 5, 60);
                    TrackerService.Prefs.sp(this).edit()
                            .putString("tail", tail.getText().toString().trim().isEmpty() ? "AUTO-1" : tail.getText().toString().trim())
                            .putInt("alt", a).putInt("interval", iv)
                            .putBoolean("ground", ground.isChecked())
                            .putBoolean("keepOn", keepOn.isChecked())
                            .apply();
                    applyKeepOn();
                    String u = user.getText().toString().trim(), p = pass.getText().toString();
                    if (!u.isEmpty() && !p.isEmpty()) login(u, p);
                    else Toast.makeText(this, "Gespeichert. Änderungen gelten beim nächsten Start.", Toast.LENGTH_SHORT).show();
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

    private void applyKeepOn() {
        if (TrackerService.Prefs.sp(this).getBoolean("keepOn", false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------- Lebenszyklus ----------
    // Absichtlich KEIN web.onPause()/pauseTimers(): die Karte soll im Hintergrund weiter Ansagen abspielen.
    @Override protected void onResume() { super.onResume(); applyKeepOn(); ui.post(refresher); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresher); CookieManager.getInstance().flush(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);   // nicht beenden, nur in den Hintergrund
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(refresher);
        web.destroy();
        super.onDestroy();
    }

    // ---------- kleine UI-Helfer ----------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), w);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private Button button(String t, int bg, int fg) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(bg);
        b.setTextColor(fg);
        b.setPadding(dp(4), 0, dp(4), 0);
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

package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TCSCalendar";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Credentials autologin (passés depuis le JS)
    private String  autoLoginUser  = null;
    private String  autoLoginPass  = null;
    private boolean confirmExit    = false;
    private long    lastBackPress  = 0;
    private boolean onTCSPage      = false; // true = WebView affiche le site TCS

    // ── File picker manuel ────────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] out = null;
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) out = new Uri[]{uri};
            }
            filePathCallback.onReceiveValue(out);
            filePathCallback = null;
        });

    // ── Téléchargement direct sans DownloadManager ────────────────────────────
    // Appelé depuis le JavascriptInterface quand le site déclenche un téléchargement
    private void downloadXmlDirectly(final String urlStr, final String cookies) {
        Toast.makeText(this, "⬇️ Récupération du planning…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                Log.d(TAG, "Downloading: " + urlStr);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                int code = conn.getResponseCode();
                Log.d(TAG, "HTTP " + code);

                if (code != 200) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Erreur HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }

                // Lire la réponse
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                conn.disconnect();

                String xml = bos.toString("UTF-8");
                Log.d(TAG, "XML downloaded, length=" + xml.length());

                // Vérifier que c'est bien du XML
                String trimmed = xml.trim();
                if (!trimmed.startsWith("<") || trimmed.toLowerCase().startsWith("<!doctype html")) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ La réponse n'est pas un XML valide",
                        Toast.LENGTH_LONG).show());
                    return;
                }

                // Injecter directement dans la page
                final String safe = xml
                    .replace("\\", "\\\\")
                    .replace("`",  "\\`")
                    .replace("$",  "\\$");

                mainHandler.post(() -> loadHomeAndInject(safe));

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur téléchargement : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Charger index.html puis injecter le XML ───────────────────────────────
    private void loadHomeAndInject(final String safeXml) {
        onTCSPage = false;
        // Cacher le bouton flottant RHTime
        webView.evaluateJavascript(
            "if(typeof hideRHTimeExportBtn==='function') hideRHTimeExportBtn();", null);
        webView.stopLoading();
        webView.clearHistory();
        webView.loadUrl("file:///android_asset/index.html");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!url.contains("android_asset")) return;
                webView.setWebViewClient(defaultClient());

                mainHandler.postDelayed(() -> {
                    String js =
                        "(function(){" +
                        "try{" +
                        "  var evs=parseXML(`" + safeXml + "`);" +
                        "  if(!evs||evs.length===0){" +
                        "    Android.showToast('⚠️ Aucun événement trouvé');" +
                        "    return;" +
                        "  }" +
                        "  events=evs;" +
                        // FIX: On ajoute l'appel pour apprendre les codes automatiquement !
                        "  if(typeof registerCodes === 'function') registerCodes(evs);" +
                        "  showPreview(evs);" +
                        "  Android.onXmlLoaded(evs.length);" +
                        "}catch(e){" +
                        "  Android.showToast('Erreur: '+e.message);" +
                        "}" +
                        "})();";
                    webView.evaluateJavascript(js, res -> Log.d(TAG, "JS: " + res));
                }, 800);
            }
        });
    }

    private WebViewClient defaultClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Bouton flottant : extraire le token de session RHTime depuis l'URL
                if (url.contains("tcs.eqso.be")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/RHTIME_Planning/([^/?]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(url);
                    if (m.find()) {
                        final String token = m.group(1);
                        Log.d(TAG, "RHTime session token: " + token);
                        // Afficher le bouton flottant avec le token
                        mainHandler.postDelayed(() ->
                            view.evaluateJavascript(
                                "if(typeof showRHTimeExportBtn==='function') showRHTimeExportBtn('" + token + "');",
                                null), 500);
                    } else {
                        // Page login ou autre → cacher le bouton
                        mainHandler.post(() ->
                            view.evaluateJavascript(
                                "if(typeof hideRHTimeExportBtn==='function') hideRHTimeExportBtn();",
                                null));
                    }
                } else {
                    // Page index.html → cacher le bouton
                    mainHandler.post(() ->
                        view.evaluateJavascript(
                            "if(typeof hideRHTimeExportBtn==='function') hideRHTimeExportBtn();",
                            null));
                }

                // Injecter l'autologin uniquement sur la page de login TCS
                // (pas sur index.html ni sur les pages déjà connectées)
                if (autoLoginUser != null && autoLoginPass != null
                        && url.contains("tcs.eqso.be")
                        && !url.contains("RHTime/RHTIME_Planning")) {

                    Log.d(TAG, "Injecting autologin on: " + url);

                    // Échapper les valeurs pour JS
                    String safeUser = autoLoginUser.replace("\\", "\\\\").replace("'", "\\'");
                    String safePass = autoLoginPass.replace("\\", "\\\\").replace("'", "\\'");

                    // Script universel : trouve tous les inputs visibles,
                    // le 1er text = login, le 1er password = mdp, puis cherche le bouton submit
                    String js =
                        "(function(){" +
                        "var MAX=10,tries=0;" +
                        "function fill(){" +
                        "  tries++;" +
                        // Tous les inputs texte visibles non désactivés
                        "  var all=[].slice.call(document.querySelectorAll('input'));" +
                        "  var texts=all.filter(function(i){" +
                        "    return (i.type==='text'||i.type==='email'||i.type==='')" +
                        "      &&!i.disabled&&!i.readOnly" +
                        "      &&i.offsetParent!==null;" +  // visible
                        "  });" +
                        "  var passes=all.filter(function(i){" +
                        "    return i.type==='password'&&!i.disabled&&i.offsetParent!==null;" +
                        "  });" +
                        "  if(texts.length===0||passes.length===0){" +
                        "    if(tries<MAX)setTimeout(fill,600);" +
                        "    return;" +
                        "  }" +
                        "  var u=texts[0],p=passes[0];" +
                        "  u.value='" + safeUser + "';" +
                        "  p.value='" + safePass + "';" +
                        // Déclencher les événements pour que WinDev détecte le changement
                        "  ['input','change','keyup','blur'].forEach(function(ev){" +
                        "    u.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "    p.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "  });" +
                        // Chercher le bouton submit
                        "  var btn=document.querySelector(" +
                        "    'input[type=submit],button[type=submit]');" +
                        "  if(!btn){" +
                        // Fallback : chercher un élément cliquable contenant "connect" ou "login"
                        "    var elems=[].slice.call(document.querySelectorAll('a,button,input[type=button],[onclick]'));" +
                        "    btn=elems.find(function(e){" +
                        "      var t=(e.textContent||e.value||'').toLowerCase();" +
                        "      return t.indexOf('connect')>=0||t.indexOf('login')>=0" +
                        "        ||t.indexOf('valider')>=0||t.indexOf('ok')===t.trim().length-2;" +
                        "    })||null;" +
                        "  }" +
                        "  if(btn){" +
                        "    setTimeout(function(){btn.click();},500);" +
                        "  } else {" +
                        // Dernier recours : soumettre le formulaire parent
                        "    var f=u.closest('form');" +
                        "    if(f)setTimeout(function(){f.submit();},500);" +
                        "  }" +
                        "}" +
                        "setTimeout(fill,800);" +
                        "})();";

                    mainHandler.postDelayed(() ->
                        view.evaluateJavascript(js, res -> Log.d(TAG, "AutoLogin JS: " + res)),
                        500);
                }
            }
        };
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setupWebView();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebViewClient(defaultClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb,
                                             FileChooserParams p) {
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/xml","application/xml","*/*"});
                filePickerLauncher.launch(Intent.createChooser(i, "Choisir XML"));
                return true;
            }
        });

        // Intercepter les téléchargements → télécharger en Java directement
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            Log.d(TAG, "Download intercepted: " + url);
            // Récupérer les cookies actuels de la WebView
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(url);
            if (cookies == null) cookies = "";
            downloadXmlDirectly(url, cookies != null ? cookies : "");
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        // Priorité 1 : panels (codes, settings, rename) ouverts → fermer
        // Priorité 2 : WebView peut reculer → goBack
        // Priorité 3 : confirm exit activé → double press
        // Priorité 4 : quitter
        webView.evaluateJavascript(
            "(function(){" +
            "  if(document.getElementById('deletePanel')&&document.getElementById('deletePanel').classList.contains('open'))return 'delete';" +
            "  if(document.getElementById('codesPanel')&&document.getElementById('codesPanel').classList.contains('open'))return 'codes';" +
            "  if(document.getElementById('renamePanel')&&document.getElementById('renamePanel').classList.contains('open'))return 'rename';" +
            "  if(document.getElementById('sPanel')&&document.getElementById('sPanel').classList.contains('open'))return 'settings';" +
            "  return 'none';" +
            "})()",
            result -> {
                if ("\"delete\"".equals(result)) {
                    mainHandler.post(() -> webView.evaluateJavascript("closeDeletePanel();", null));
                } else if ("\"codes\"".equals(result)) {
                    mainHandler.post(() -> webView.evaluateJavascript("closeCodesPanel();", null));
                } else if ("\"rename\"".equals(result)) {
                    mainHandler.post(() -> webView.evaluateJavascript("closeRenamePanel();", null));
                } else if ("\"settings\"".equals(result)) {
                    mainHandler.post(() -> webView.evaluateJavascript("closeSettings();", null));
                } else if (onTCSPage && webView.canGoBack()) {
                    mainHandler.post(() -> webView.goBack());
                } else if (onTCSPage) {
                    // Plus d'historique sur TCS → retour à l'accueil
                    onTCSPage = false;
                    mainHandler.post(() -> {
                        webView.stopLoading();
                        webView.clearHistory();
                        webView.loadUrl("file:///android_asset/index.html");
                    });
                } else if (confirmExit) {
                    long now = System.currentTimeMillis();
                    if (now - lastBackPress < 2000) {
                        mainHandler.post(() -> MainActivity.super.onBackPressed());
                    } else {
                        lastBackPress = now;
                        mainHandler.post(() ->
                            webView.evaluateJavascript(
                                "toast('Appuyez encore une fois pour quitter','info',2000);", null));
                    }
                } else {
                    mainHandler.post(() -> MainActivity.super.onBackPressed());
                }
            }
        );
    }

    // ── Bridge JS ↔ Android ───────────────────────────────────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "onXmlLoaded count=" + count);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void onIcsExported() { }

        @JavascriptInterface
        public void saveAndOpenICS(String icsContent, String filename, boolean openCal) {
            mainHandler.post(() -> {
                try {
                    File dir = new File(getCacheDir(), "ics");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, filename);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(icsContent.getBytes(StandardCharsets.UTF_8));
                    }
                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", f);
                    Intent intent = openCal
                        ? new Intent(Intent.ACTION_VIEW)
                        : new Intent(Intent.ACTION_SEND);
                    if (openCal) {
                        intent.setDataAndType(uri, "text/calendar");
                    } else {
                        intent.setType("text/calendar");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.putExtra(Intent.EXTRA_SUBJECT, "Horaire TCS");
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(Intent.createChooser(intent,
                        openCal ? "Ouvrir avec…" : "Partager…"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Erreur ICS : " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String msg) {
            mainHandler.post(() ->
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void setConfirmExit(boolean value) {
            confirmExit = value;
            Log.d(TAG, "confirmExit=" + value);
        }

        @JavascriptInterface
        public void setAutoLoginCredentials(String user, String pass) {
            autoLoginUser = user;
            autoLoginPass = pass;
            Log.d(TAG, "AutoLogin credentials set for user: " + user);
        }

        @JavascriptInterface
        public void loadTCSPage() {
            mainHandler.post(() -> {
                onTCSPage = true;
                webView.stopLoading();
                webView.loadUrl("https://tcs.eqso.be/RHTime");
            });
        }

        @JavascriptInterface
        public void downloadXmlFromUrl(String url) {
            // Téléchargement direct de l'URL XML RHTime (comme HttpURLConnection)
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(url);
            final String finalCookies = (cookies != null) ? cookies : "";
            downloadXmlDirectly(url, finalCookies);
            // Réinitialiser le bouton après déclenchement (sera fait via loadHomeAndInject)
        }

        @JavascriptInterface
        public String getAppVersion() { return "2.3"; }
    }
}
package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TCSCalendar";

    // WebView principale : affiche toujours index.html
    private WebView webView;
    // WebView cachée : connexion RHTime en arrière-plan (auto-import)
    private WebView hiddenWebView;

    private Button  btnExportXml  = null;
    private String  rhtimeToken   = null;   // token session (WebView principale/flottante)
    private boolean onTCSPage     = false;
    private boolean confirmExit   = false;
    private long    lastBackPress = 0;

    // Autologin (WebView principale)
    private String  autoLoginUser = null;
    private String  autoLoginPass = null;

    // Smart auto-import (WebView cachée)
    private int     importMonth    = 0;
    private int     importYear     = 0;
    private boolean importActive   = false;
    private boolean loginDone      = false;  // étape 1 : login OK
    private boolean monthNavDone   = false;  // étape 2 : mois chargé
    private String  hiddenToken    = null;   // token extrait depuis la hidden WebView

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── File picker ───────────────────────────────────────────────────────────
    private ValueCallback<Uri[]> filePathCallback;

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

    // ── onCreate ─────────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled","JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView      = findViewById(R.id.webView);
        btnExportXml = findViewById(R.id.btnExportXml);

        // Bouton flottant → export XML depuis la page RHTime visible
        btnExportXml.setOnClickListener(v -> {
            if (rhtimeToken == null) return;
            String xmlUrl = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
                + rhtimeToken + "/export.xml?WD_ACTION_=EXPORTXML&A9";
            String cookies = CookieManager.getInstance().getCookie(xmlUrl);
            downloadXmlDirectly(xmlUrl, cookies != null ? cookies : "");
            btnExportXml.setEnabled(false);
            btnExportXml.setText("Recuperation...");
        });

        setupMainWebView();
        setupHiddenWebView();
    }

    @Override
    protected void onDestroy() {
        if (hiddenWebView != null) {
            hiddenWebView.destroy();
            hiddenWebView = null;
        }
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEBVIEW PRINCIPALE — affiche index.html + site RHTime (bouton flottant)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private void setupMainWebView() {
        configureWebViewSettings(webView);
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebViewClient(mainDefaultClient());
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
        webView.loadUrl("file:///android_asset/index.html");
    }

    private WebViewClient mainDefaultClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                // Bouton flottant : token dans l'URL ou dans le HTML
                if (url.contains("tcs.eqso.be")) {
                    Matcher m = Pattern
                        .compile("/RHTIME_Planning/([^/?&#]+)", Pattern.CASE_INSENSITIVE)
                        .matcher(url);
                    if (m.find()) {
                        rhtimeToken = m.group(1);
                        showExportButton();
                    } else {
                        mainHandler.postDelayed(() ->
                            view.evaluateJavascript(
                                "(function(){var h=document.documentElement.innerHTML||'';" +
                                "var m=h.match(/\\/RHTIME_Planning\\/([A-Za-z0-9_\\-]{8,})/i);" +
                                "return m?m[1]:'';})() ",
                                result -> {
                                    String token = result.replace("\"","").trim();
                                    if (!token.isEmpty() && !token.equals("null")) {
                                        rhtimeToken = token;
                                        showExportButton();
                                    } else {
                                        hideExportButton();
                                    }
                                }), 800);
                    }
                } else {
                    hideExportButton();
                }
                // Autologin
                if (autoLoginUser != null && autoLoginPass != null
                        && url.contains("tcs.eqso.be")
                        && !url.contains("RHTime/RHTIME_Planning")) {
                    injectAutologin(view, autoLoginUser, autoLoginPass);
                }
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEBVIEW CACHÉE — auto-import en arrière-plan
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private void setupHiddenWebView() {
        hiddenWebView = new WebView(this);
        configureWebViewSettings(hiddenWebView);
        // Partager les cookies avec la WebView principale
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWebView, true);

        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!importActive) return;
                Log.d(TAG, "Hidden WV page: " + url);
                if (!url.contains("tcs.eqso.be")) return;

                if (!loginDone && !url.contains("RHTIME_Planning")) {
                    // Encore sur la page de login → injecter les credentials
                    Log.d(TAG, "Hidden WV: injecting autologin");
                    injectAutologin(view, autoLoginUser, autoLoginPass);
                } else {
                    // Sur une page RHTime avec planning (après login ou après navigation mois)
                    // onHiddenTokenReady gère les états loginDone / monthNavDone
                    mainHandler.postDelayed(() -> extractHiddenToken(view, url), 500);
                }
            }
        });
    }

    private void extractHiddenToken(WebView view, String url) {
        // Essai dans l'URL
        Matcher m = Pattern
            .compile("/RHTIME_Planning/([^/?&#]+)", Pattern.CASE_INSENSITIVE)
            .matcher(url);
        if (m.find()) {
            hiddenToken = m.group(1);
            Log.d(TAG, "Hidden token (URL): " + hiddenToken);
            onHiddenTokenReady(view, url);
            return;
        }
        // Essai dans le HTML
        mainHandler.postDelayed(() ->
            view.evaluateJavascript(
                "(function(){var h=document.documentElement.innerHTML||'';" +
                "var m=h.match(/\\/RHTIME_Planning\\/([A-Za-z0-9_\\-]{8,})/i);" +
                "return m?m[1]:'';})() ",
                result -> {
                    String token = result.replace("\"","").trim();
                    if (!token.isEmpty() && !token.equals("null")) {
                        hiddenToken = token;
                        Log.d(TAG, "Hidden token (HTML): " + token);
                        onHiddenTokenReady(view, url);
                    } else {
                        Log.d(TAG, "No token yet on: " + url);
                        // Réessayer l'autologin seulement si pas encore connecté
                        if (!loginDone) {
                            injectAutologin(view, autoLoginUser, autoLoginPass);
                        }
                        // Si déjà connecté mais pas de token : attendre le prochain onPageFinished
                    }
                }), 1000);
    }

    private void onHiddenTokenReady(WebView view, String url) {
        if (!importActive) return;

        if (!loginDone) {
            // Connexion OK
            loginDone = true;
            notifyJS("impStep1Done");
            Log.d(TAG, "Step 1 done. Navigating to month " + importMonth + "/" + importYear);
            mainHandler.postDelayed(() -> navigateHiddenToMonth(view), 1500);
        } else if (!monthNavDone) {
            // Mois chargé
            monthNavDone = true;
            notifyJS("impStep2Done");
            Log.d(TAG, "Step 2 done. Triggering export");
            mainHandler.postDelayed(() -> triggerHiddenExport(), 1500);
        }
    }

    private void navigateHiddenToMonth(WebView view) {
        if (!importActive || hiddenToken == null) return;

        // Méthode 1 : injecter JS pour changer le sélecteur de mois ET d'année
        String js =
            "(function(){" +
            "var selects=[].slice.call(document.querySelectorAll('select'));" +

            // Chercher le select de mois : options avec valeurs 1-12
            "var mSel=selects.find(function(s){" +
            "  var opts=[].slice.call(s.options);" +
            "  return opts.length>=12&&opts.some(function(o){" +
            "    var v=parseInt(o.value);return v>=1&&v<=12;" +
            "  });" +
            "});" +

            // Chercher le select d'année : options avec valeurs > 2020
            "var ySel=selects.find(function(s){" +
            "  var opts=[].slice.call(s.options);" +
            "  return opts.some(function(o){" +
            "    var v=parseInt(o.value);return v>2020&&v<2035;" +
            "  });" +
            "});" +

            "var ok=false;" +
            "if(mSel){" +
            // Essayer par value d'abord, puis par index
            "  var target=" + importMonth + ";" +
            "  var found=false;" +
            "  [].slice.call(mSel.options).forEach(function(o,i){" +
            "    if(parseInt(o.value)===target){mSel.selectedIndex=i;found=true;}" +
            "  });" +
            "  if(!found)mSel.selectedIndex=" + (importMonth-1) + ";" +
            "  mSel.dispatchEvent(new Event('change',{bubbles:true}));" +
            "  ok=true;" +
            "}" +
            "if(ySel){" +
            "  var ytarget=" + importYear + ";" +
            "  [].slice.call(ySel.options).forEach(function(o,i){" +
            "    if(parseInt(o.value)===ytarget||o.text.indexOf('" + importYear + "')>=0)" +
            "      {ySel.selectedIndex=i;}" +
            "  });" +
            "  ySel.dispatchEvent(new Event('change',{bubbles:true}));" +
            "  ok=true;" +
            "}" +

            // Cliquer le bouton Charger / Valider
            "var btns=[].slice.call(document.querySelectorAll('button,input[type=button],input[type=submit],[onclick]'));" +
            "var loadBtn=btns.find(function(b){" +
            "  var t=(b.textContent||b.value||b.title||'').toLowerCase();" +
            "  return t.indexOf('charg')>=0||t.indexOf('valid')>=0||t.indexOf('appl')>=0||t.indexOf('ok')>=0||t.indexOf('go')>=0;" +
            "});" +
            "if(loadBtn){setTimeout(function(){loadBtn.click();},400);ok=true;}" +

            "return ok?'ok':'not_found';" +
            "})()";

        view.evaluateJavascript(js, result -> {
            Log.d(TAG, "navigateToMonth JS result: " + result);
            if ("\"not_found\"".equals(result)) {
                // Fallback : URL avec paramètres
                String fallback = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
                    + hiddenToken + "?Mois=" + importMonth + "&Annee=" + importYear;
                Log.d(TAG, "Fallback URL: " + fallback);
                mainHandler.post(() -> hiddenWebView.loadUrl(fallback));
            }
            // onPageFinished se redéclenchera quand le nouveau mois sera chargé
        });
    }

    private void triggerHiddenExport() {
        if (!importActive || hiddenToken == null) return;
        importActive = false;
        String xmlUrl = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
            + hiddenToken + "/export.xml?WD_ACTION_=EXPORTXML&A9";
        // Utiliser les cookies de la hidden WebView
        String cookies = CookieManager.getInstance().getCookie(xmlUrl);
        Log.d(TAG, "Triggering export: " + xmlUrl);
        downloadXmlDirectly(xmlUrl, cookies != null ? cookies : "");
        notifyJS("impStep3Done");
    }

    // Notifier le JS de index.html (WebView principale)
    private void notifyJS(String fnName) {
        mainHandler.post(() ->
            webView.evaluateJavascript(
                "if(typeof " + fnName + "==='function')" + fnName + "();", null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AUTOLOGIN commun aux deux WebViews
    // ═══════════════════════════════════════════════════════════════════════

    private void injectAutologin(WebView view, String user, String pass) {
        if (user == null || pass == null) return;
        String safeUser = user.replace("\\", "\\\\").replace("'", "\\'");
        String safePass = pass.replace("\\", "\\\\").replace("'", "\\'");
        String js =
            "(function(){var MAX=10,tries=0;" +
            "function fill(){tries++;" +
            "var all=[].slice.call(document.querySelectorAll('input'));" +
            "var texts=all.filter(function(i){return(i.type==='text'||i.type==='email'||i.type==='')" +
            "&&!i.disabled&&!i.readOnly&&i.offsetParent!==null;});" +
            "var passes=all.filter(function(i){return i.type==='password'&&!i.disabled&&i.offsetParent!==null;});" +
            "if(texts.length===0||passes.length===0){if(tries<MAX)setTimeout(fill,600);return;}" +
            "var u=texts[0],p=passes[0];" +
            "u.value='" + safeUser + "';p.value='" + safePass + "';" +
            "['input','change','keyup','blur'].forEach(function(ev){" +
            "u.dispatchEvent(new Event(ev,{bubbles:true}));" +
            "p.dispatchEvent(new Event(ev,{bubbles:true}));});" +
            "var btn=document.querySelector('input[type=submit],button[type=submit]');" +
            "if(!btn){var elems=[].slice.call(document.querySelectorAll('a,button,input[type=button],[onclick]'));" +
            "btn=elems.find(function(e){var t=(e.textContent||e.value||'').toLowerCase();" +
            "return t.indexOf('connect')>=0||t.indexOf('login')>=0||t.indexOf('valider')>=0;})||null;}" +
            "if(btn){setTimeout(function(){btn.click();},500);}" +
            "else{var f=u.closest('form');if(f)setTimeout(function(){f.submit();},500);}}" +
            "setTimeout(fill,800);})()";
        mainHandler.postDelayed(() ->
            view.evaluateJavascript(js, res -> Log.d(TAG, "Autologin: " + res)), 300);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DOWNLOAD XML DIRECT
    // ═══════════════════════════════════════════════════════════════════════

    private void downloadXmlDirectly(final String urlStr, final String cookies) {
        new Thread(() -> {
            try {
                Log.d(TAG, "Downloading XML: " + urlStr);
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
                if (code != 200) {
                    mainHandler.post(() -> {
                        notifyJS("impError('Erreur HTTP " + code + "')");
                        Toast.makeText(this, "Erreur HTTP " + code, Toast.LENGTH_LONG).show();
                        resetExportButton();
                    });
                    return;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close(); conn.disconnect();
                String xml = bos.toString("UTF-8");
                if (!xml.trim().startsWith("<") || xml.trim().toLowerCase().startsWith("<!doctype")) {
                    mainHandler.post(() -> {
                        webView.evaluateJavascript("if(typeof impError==='function')impError('Reponse invalide, reconnectez-vous');", null);
                        Toast.makeText(this, "Reponse non XML", Toast.LENGTH_LONG).show();
                        resetExportButton();
                    });
                    return;
                }
                final String safe = xml.replace("\\","\\\\").replace("`","\\`").replace("$","\\$");
                mainHandler.post(() -> loadHomeAndInject(safe));
            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                mainHandler.post(() -> {
                    webView.evaluateJavascript("if(typeof impError==='function')impError('" + e.getMessage() + "');", null);
                    Toast.makeText(this, "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    resetExportButton();
                });
            }
        }).start();
    }

    private void resetExportButton() {
        if (btnExportXml != null) {
            btnExportXml.setEnabled(true);
            btnExportXml.setText("Exporter ce mois");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOAD HOME + INJECT XML
    // ═══════════════════════════════════════════════════════════════════════

    private void loadHomeAndInject(final String safeXml) {
        onTCSPage = false;
        rhtimeToken = null;
        mainHandler.post(() -> {
            if (btnExportXml != null) btnExportXml.setVisibility(android.view.View.GONE);
        });
        // Arrêter la hidden WebView
        if (hiddenWebView != null) hiddenWebView.stopLoading();
        loginDone = false; monthNavDone = false; hiddenToken = null;

        webView.stopLoading();
        webView.clearHistory();
        webView.loadUrl("file:///android_asset/index.html");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!url.contains("android_asset")) return;
                webView.setWebViewClient(mainDefaultClient());
                mainHandler.postDelayed(() -> {
                    String js =
                        "(function(){try{" +
                        "var evs=parseXML(`" + safeXml + "`);" +
                        "if(!evs||evs.length===0){Android.showToast('Aucun evenement trouve');return;}" +
                        "events=evs;" +
                        "if(typeof registerCodes==='function')registerCodes(evs);" +
                        "showPreview(evs);" +
                        "Android.onXmlLoaded(evs.length);" +
                        "}catch(e){Android.showToast('Erreur: '+e.message);}})();";
                    webView.evaluateJavascript(js, res -> Log.d(TAG, "Inject: " + res));
                }, 800);
            }
        });
    }

    // ── Show/Hide export button ───────────────────────────────────────────
    private void showExportButton() {
        mainHandler.postDelayed(() -> {
            if (btnExportXml != null) {
                btnExportXml.setVisibility(android.view.View.VISIBLE);
                btnExportXml.setEnabled(true);
                btnExportXml.setText("Exporter ce mois");
            }
        }, 300);
    }

    private void hideExportButton() {
        rhtimeToken = null;
        mainHandler.post(() -> {
            if (btnExportXml != null) btnExportXml.setVisibility(android.view.View.GONE);
        });
    }

    // ── WebView settings ─────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebViewSettings(WebView wv) {
        WebSettings s = wv.getSettings();
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
    }

    // ── Back button ───────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "(function(){" +
            "if(document.getElementById('deletePanel')&&document.getElementById('deletePanel').classList.contains('open'))return 'delete';" +
            "if(document.getElementById('codesPanel')&&document.getElementById('codesPanel').classList.contains('open'))return 'codes';" +
            "if(document.getElementById('renamePanel')&&document.getElementById('renamePanel').classList.contains('open'))return 'rename';" +
            "if(document.getElementById('sPanel')&&document.getElementById('sPanel').classList.contains('open'))return 'settings';" +
            "if(document.getElementById('credModal')&&document.getElementById('credModal').classList.contains('open'))return 'cred';" +
            "return 'none';})()",
            result -> {
                if ("\"delete\"".equals(result))   mainHandler.post(() -> webView.evaluateJavascript("closeDeletePanel();",null));
                else if ("\"codes\"".equals(result))    mainHandler.post(() -> webView.evaluateJavascript("closeCodesPanel();",null));
                else if ("\"rename\"".equals(result))   mainHandler.post(() -> webView.evaluateJavascript("closeRenamePanel();",null));
                else if ("\"settings\"".equals(result)) mainHandler.post(() -> webView.evaluateJavascript("closeSettings();",null));
                else if ("\"cred\"".equals(result))     mainHandler.post(() -> webView.evaluateJavascript("closeCredModal();",null));
                else if (onTCSPage && webView.canGoBack()) mainHandler.post(() -> webView.goBack());
                else if (onTCSPage) {
                    onTCSPage = false;
                    hideExportButton();
                    mainHandler.post(() -> {
                        webView.stopLoading(); webView.clearHistory();
                        webView.loadUrl("file:///android_asset/index.html");
                    });
                } else if (importActive) {
                    // Annuler l'import en cours
                    mainHandler.post(() -> webView.evaluateJavascript("cancelImport();",null));
                } else if (confirmExit) {
                    long now = System.currentTimeMillis();
                    if (now - lastBackPress < 2000) mainHandler.post(() -> MainActivity.super.onBackPressed());
                    else {
                        lastBackPress = now;
                        mainHandler.post(() -> webView.evaluateJavascript(
                            "toast('Appuyez encore une fois pour quitter','info',2000);",null));
                    }
                } else mainHandler.post(() -> MainActivity.super.onBackPressed());
            });
    }

    // ── JavaScript Bridge ─────────────────────────────────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public void startAutoImport(String user, String pass, int month, int year) {
            autoLoginUser  = user;
            autoLoginPass  = pass;
            importMonth    = month;
            importYear     = year;
            importActive   = true;
            loginDone      = false;
            monthNavDone   = false;
            hiddenToken    = null;
            Log.d(TAG, "startAutoImport " + month + "/" + year);
            mainHandler.post(() -> {
                // Charger RHTime dans la WebView CACHÉE — la principale reste sur index.html
                hiddenWebView.stopLoading();
                hiddenWebView.clearHistory();
                hiddenWebView.loadUrl("https://tcs.eqso.be/RHTime");
            });
        }

        @JavascriptInterface
        public void cancelAutoImport() {
            importActive = false; loginDone = false; monthNavDone = false;
            hiddenToken = null;
            mainHandler.post(() -> {
                if (hiddenWebView != null) hiddenWebView.stopLoading();
            });
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
        public void openExternalBrowser(String url) {
            mainHandler.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Impossible d'ouvrir le navigateur", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                count + " evenements charges !", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void onIcsExported() {}

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
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", f);
                    Intent intent = openCal ? new Intent(Intent.ACTION_VIEW)
                                            : new Intent(Intent.ACTION_SEND);
                    if (openCal) { intent.setDataAndType(uri, "text/calendar"); }
                    else { intent.setType("text/calendar"); intent.putExtra(Intent.EXTRA_STREAM, uri);
                           intent.putExtra(Intent.EXTRA_SUBJECT, "Horaire TCS"); }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(Intent.createChooser(intent, openCal ? "Ouvrir avec..." : "Partager..."));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Erreur ICS: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String msg) {
            mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void setConfirmExit(boolean value) { confirmExit = value; }

        @JavascriptInterface
        public void setAutoLoginCredentials(String user, String pass) {
            autoLoginUser = user; autoLoginPass = pass;
        }

        @JavascriptInterface
        public String getAppVersion() { return "5.0"; }
    }
}

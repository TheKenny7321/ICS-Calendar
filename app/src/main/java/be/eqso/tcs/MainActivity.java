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

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Button  btnExportXml  = null;
    private String  rhtimeToken   = null;
    private boolean onTCSPage     = false;
    private boolean confirmExit   = false;
    private long    lastBackPress = 0;
    private String  autoLoginUser = null;
    private String  autoLoginPass = null;
    // Smart auto-import
    private int     importMonth   = 0;
    private int     importYear    = 0;
    private boolean importActive  = false;
    private boolean monthNavigated = false; // true = déjà navigué vers le bon mois

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── File picker ───────────────────────────────────────────────────────────
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView      = findViewById(R.id.webView);
        btnExportXml = findViewById(R.id.btnExportXml);

        btnExportXml.setOnClickListener(v -> {
            if (rhtimeToken == null) return;
            String xmlUrl = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
                + rhtimeToken + "/export.xml?WD_ACTION_=EXPORTXML&A9";
            String cookies = CookieManager.getInstance().getCookie(xmlUrl);
            downloadXmlDirectly(xmlUrl, cookies != null ? cookies : "");
            btnExportXml.setEnabled(false);
            btnExportXml.setText("Recuperation...");
        });

        setupWebView();
    }

    // ── Show / Hide export button ─────────────────────────────────────────────
    // ── Auto-import flow ─────────────────────────────────────────────────────
    private void handleAutoImportPageLoaded(WebView view, String url) {
        if (!importActive) return;

        if (!monthNavigated) {
            // Étape 1 done → notifier JS, puis naviguer vers le mois voulu
            view.evaluateJavascript("if(typeof impStep1Done==='function')impStep1Done();", null);
            Log.d(TAG, "Auto-import: navigating to " + importMonth + "/" + importYear);
            mainHandler.postDelayed(() -> navigateToMonth(view), 1500);
        } else {
            // Étape 2 done → le mois est chargé, déclencher l'export XML
            view.evaluateJavascript("if(typeof impStep2Done==='function')impStep2Done();", null);
            Log.d(TAG, "Auto-import: triggering XML export");
            mainHandler.postDelayed(() -> triggerAutoExport(), 1000);
        }
    }

    private void navigateToMonth(WebView view) {
        if (!importActive) return;
        int month = importMonth;
        int year  = importYear;

        // Script JS universel pour changer le mois/année sur RHTime (WinDev)
        // WinDev utilise généralement des SELECT ou des inputs cachés avec des ids spécifiques
        String js =
            "(function(){" +
            "  var changed=false;" +
            // Chercher le sélecteur de mois — WinDev utilise souvent data-wdid ou des combos
            "  var selects=[].slice.call(document.querySelectorAll('select'));" +
            // Trouver le select qui contient des mois (options 1-12 ou noms de mois)
            "  var mSel=selects.find(function(s){" +
            "    var opts=[].slice.call(s.options);" +
            "    return opts.length>=12&&opts.some(function(o){" +
            "      var v=parseInt(o.value||o.text);" +
            "      return v>=1&&v<=12;" +
            "    });" +
            "  });" +
            // Trouver le select de l'année
            "  var ySel=selects.find(function(s){" +
            "    var opts=[].slice.call(s.options);" +
            "    return opts.some(function(o){" +
            "      var v=parseInt(o.value||o.text);" +
            "      return v>2020&&v<2030;" +
            "    });" +
            "  });" +
            "  if(mSel){" +
            "    mSel.value=" + month + ";" +
            "    if(!mSel.value){" + // si value numérique ne marche pas, essayer par index
            "      mSel.selectedIndex=" + (month-1) + ";" +
            "    }" +
            "    mSel.dispatchEvent(new Event('change',{bubbles:true}));" +
            "    changed=true;" +
            "  }" +
            "  if(ySel){" +
            "    ySel.value=" + year + ";" +
            "    ySel.dispatchEvent(new Event('change',{bubbles:true}));" +
            "    changed=true;" +
            "  }" +
            // Chercher et cliquer le bouton "Charger" / "Valider" / "OK"
            "  var btns=[].slice.call(document.querySelectorAll('button,input[type=button],input[type=submit],[onclick]'));" +
            "  var loadBtn=btns.find(function(b){" +
            "    var t=(b.textContent||b.value||b.getAttribute('onclick')||'').toLowerCase();" +
            "    return t.indexOf('charg')>=0||t.indexOf('valider')>=0||t.indexOf('ok')>=0||t.indexOf('appliquer')>=0;" +
            "  });" +
            "  if(loadBtn){setTimeout(function(){loadBtn.click();},300);changed=true;}" +
            "  return changed?'ok':'not_found';" +
            "})()";

        view.evaluateJavascript(js, result -> {
            Log.d(TAG, "navigateToMonth result: " + result);
            monthNavigated = true;
            if (""not_found"".equals(result)) {
                // Fallback : construire l'URL directement avec paramètres mois/année
                // RHTime accepte parfois ?Mois=X&Annee=Y dans l'URL
                mainHandler.post(() -> {
                    String fallbackUrl = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
                        + rhtimeToken + "?Mois=" + importMonth + "&Annee=" + importYear;
                    Log.d(TAG, "Trying fallback URL: " + fallbackUrl);
                    view.loadUrl(fallbackUrl);
                });
            }
            // onPageFinished se re-déclenchera quand la page du nouveau mois est chargée
        });
    }

    private void triggerAutoExport() {
        if (!importActive || rhtimeToken == null) return;
        importActive = false;
        String xmlUrl = "https://tcs.eqso.be/RHTime/RHTIME_Planning/"
            + rhtimeToken + "/export.xml?WD_ACTION_=EXPORTXML&A9";
        String cookies = CookieManager.getInstance().getCookie(xmlUrl);
        downloadXmlDirectly(xmlUrl, cookies != null ? cookies : "");
        // Notifier JS
        webView.evaluateJavascript("if(typeof impStep3Done==='function')impStep3Done();", null);
    }

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
            if (btnExportXml != null)
                btnExportXml.setVisibility(android.view.View.GONE);
        });
    }

    // ── Direct XML download ───────────────────────────────────────────────────
    private void downloadXmlDirectly(final String urlStr, final String cookies) {
        Toast.makeText(this, "Recuperation du planning...", Toast.LENGTH_SHORT).show();

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
                        "Erreur HTTP " + code, Toast.LENGTH_LONG).show());
                    mainHandler.post(this::resetExportButton);
                    return;
                }

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                conn.disconnect();

                String xml = bos.toString("UTF-8");
                String trimmed = xml.trim();
                if (!trimmed.startsWith("<") || trimmed.toLowerCase().startsWith("<!doctype html")) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "La reponse n'est pas un XML valide", Toast.LENGTH_LONG).show());
                    mainHandler.post(this::resetExportButton);
                    return;
                }

                final String safe = xml
                    .replace("\\", "\\\\")
                    .replace("`",  "\\`")
                    .replace("$",  "\\$");

                mainHandler.post(() -> loadHomeAndInject(safe));

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur: " + e.getMessage(), Toast.LENGTH_LONG).show());
                mainHandler.post(this::resetExportButton);
            }
        }).start();
    }

    private void resetExportButton() {
        if (btnExportXml != null) {
            btnExportXml.setEnabled(true);
            btnExportXml.setText("Exporter ce mois");
        }
    }

    // ── Load home + inject XML ────────────────────────────────────────────────
    private void loadHomeAndInject(final String safeXml) {
        onTCSPage = false;
        rhtimeToken = null;
        mainHandler.post(() -> {
            if (btnExportXml != null) btnExportXml.setVisibility(android.view.View.GONE);
        });
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
                        "    Android.showToast('Aucun evenement trouve');" +
                        "    return;" +
                        "  }" +
                        "  events=evs;" +
                        "  if(typeof registerCodes==='function')registerCodes(evs);" +
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

    // ── Default WebViewClient (with autologin + token detection) ─────────────
    private WebViewClient defaultClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // -- Token detection: try URL first --
                if (url.contains("tcs.eqso.be")) {
                    Matcher m = Pattern
                        .compile("/RHTIME_Planning/([^/?&#]+)", Pattern.CASE_INSENSITIVE)
                        .matcher(url);
                    if (m.find()) {
                        rhtimeToken = m.group(1);
                        Log.d(TAG, "Token in URL: " + rhtimeToken);
                        if (importActive) handleAutoImportPageLoaded(view, url);
                        else showExportButton();
                    } else {
                        // Token not in URL — search inside page HTML
                        mainHandler.postDelayed(() ->
                            view.evaluateJavascript(
                                "(function(){" +
                                "var h=document.documentElement.innerHTML||'';" +
                                "var m=h.match(/\\/RHTIME_Planning\\/([A-Za-z0-9_\\-]{8,})/i);" +
                                "return m?m[1]:'';"+
                                "})()",
                                result -> {
                                    String token = result.replace("\"", "").trim();
                                    if (!token.isEmpty() && !token.equals("null")) {
                                        rhtimeToken = token;
                                        Log.d(TAG, "Token in HTML: " + token);
                                        if (importActive) handleAutoImportPageLoaded(view, url);
                                        else showExportButton();
                                    } else {
                                        Log.d(TAG, "No token found on: " + url);
                                        hideExportButton();
                                    }
                                }), 800);
                    }
                } else {
                    hideExportButton();
                }

                // -- Autologin --
                if (autoLoginUser != null && autoLoginPass != null
                        && url.contains("tcs.eqso.be")
                        && !url.contains("RHTime/RHTIME_Planning")) {

                    Log.d(TAG, "Injecting autologin on: " + url);
                    String safeUser = autoLoginUser.replace("\\", "\\\\").replace("'", "\\'");
                    String safePass = autoLoginPass.replace("\\", "\\\\").replace("'", "\\'");

                    String js =
                        "(function(){" +
                        "var MAX=10,tries=0;" +
                        "function fill(){" +
                        "  tries++;" +
                        "  var all=[].slice.call(document.querySelectorAll('input'));" +
                        "  var texts=all.filter(function(i){" +
                        "    return(i.type==='text'||i.type==='email'||i.type==='')" +
                        "    &&!i.disabled&&!i.readOnly&&i.offsetParent!==null;" +
                        "  });" +
                        "  var passes=all.filter(function(i){" +
                        "    return i.type==='password'&&!i.disabled&&i.offsetParent!==null;" +
                        "  });" +
                        "  if(texts.length===0||passes.length===0){" +
                        "    if(tries<MAX)setTimeout(fill,600);return;" +
                        "  }" +
                        "  var u=texts[0],p=passes[0];" +
                        "  u.value='" + safeUser + "';" +
                        "  p.value='" + safePass + "';" +
                        "  ['input','change','keyup','blur'].forEach(function(ev){" +
                        "    u.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "    p.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "  });" +
                        "  var btn=document.querySelector('input[type=submit],button[type=submit]');" +
                        "  if(!btn){" +
                        "    var elems=[].slice.call(document.querySelectorAll('a,button,input[type=button],[onclick]'));" +
                        "    btn=elems.find(function(e){" +
                        "      var t=(e.textContent||e.value||'').toLowerCase();" +
                        "      return t.indexOf('connect')>=0||t.indexOf('login')>=0||t.indexOf('valider')>=0;" +
                        "    })||null;" +
                        "  }" +
                        "  if(btn){setTimeout(function(){btn.click();},500);}" +
                        "  else{var f=u.closest('form');if(f)setTimeout(function(){f.submit();},500);}" +
                        "}" +
                        "setTimeout(fill,800);" +
                        "})();";

                    mainHandler.postDelayed(() ->
                        view.evaluateJavascript(js, res -> Log.d(TAG, "Autologin: " + res)),
                        500);
                }
            }
        };
    }

    // ── Setup WebView ─────────────────────────────────────────────────────────
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

        webView.loadUrl("file:///android_asset/index.html");
    }

    // ── Back button ───────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
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
                    onTCSPage = false;
                    hideExportButton();
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

    // ── JavaScript Bridge ─────────────────────────────────────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "onXmlLoaded count=" + count);
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
                        openCal ? "Ouvrir avec..." : "Partager..."));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Erreur ICS: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        }

        @JavascriptInterface
        public void setAutoLoginCredentials(String user, String pass) {
            autoLoginUser = user;
            autoLoginPass = pass;
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
                    Toast.makeText(MainActivity.this,
                        "Impossible d'ouvrir le navigateur", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startAutoImport(String user, String pass, int month, int year) {
            autoLoginUser = user;
            autoLoginPass = pass;
            importMonth   = month;
            importYear    = year;
            importActive  = true;
            monthNavigated = false;
            Log.d(TAG, "startAutoImport " + month + "/" + year);
            mainHandler.post(() -> {
                onTCSPage = true;
                webView.stopLoading();
                webView.loadUrl("https://tcs.eqso.be/RHTime");
            });
        }

        @JavascriptInterface
        public void cancelAutoImport() {
            importActive  = false;
            importMonth   = 0;
            importYear    = 0;
            monthNavigated = false;
            autoLoginUser = null;
            autoLoginPass = null;
            mainHandler.post(() -> {
                onTCSPage = false;
                webView.stopLoading();
                webView.clearHistory();
                webView.loadUrl("file:///android_asset/index.html");
            });
        }

        @JavascriptInterface
        public String getAppVersion() { return "5.0"; }
    }
}

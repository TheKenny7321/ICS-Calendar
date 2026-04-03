package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG      = "TCSCalendar";
    private static final String XML_NAME = "tcs_export.xml"; // nom fixe, écrase toujours

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private long    pendingDownloadId = -1;
    private boolean polling           = false;
    private boolean xmlInjected       = false; // évite double injection si app revient au premier plan

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Dossier privé de l'app : getCacheDir()/tcs_xml/
    private File getXmlDir() {
        File dir = new File(getCacheDir(), "tcs_xml");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getXmlFile() {
        return new File(getXmlDir(), XML_NAME);
    }

    // ── File picker (import manuel) ───────────────────────────────────────────
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

    // ── Polling : vérifie que le téléchargement est terminé ──────────────────
    private final Runnable pollDownload = new Runnable() {
        @Override
        public void run() {
            if (!polling || pendingDownloadId < 0) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(pendingDownloadId);
            Cursor c = dm.query(q);

            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                c.close();

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    polling = false;
                    Log.d(TAG, "Download complete, reading XML");
                    readXmlAndInject();

                } else if (status == DownloadManager.STATUS_FAILED) {
                    polling = false;
                    Toast.makeText(MainActivity.this,
                        "❌ Téléchargement échoué", Toast.LENGTH_LONG).show();

                } else {
                    // Toujours en cours
                    if (c != null) c.close();
                    mainHandler.postDelayed(this, 1000);
                }
            } else {
                if (c != null) c.close();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    // ── Lire le XML depuis le dossier privé et injecter ───────────────────────
    private void readXmlAndInject() {
        new Thread(() -> {
            try {
                File xmlFile = getXmlFile();
                if (!xmlFile.exists()) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Fichier XML introuvable dans le dossier privé",
                        Toast.LENGTH_LONG).show());
                    return;
                }

                FileInputStream fis = new FileInputStream(xmlFile);
                byte[] buf = new byte[(int) xmlFile.length()];
                fis.read(buf);
                fis.close();
                String xml = new String(buf, StandardCharsets.UTF_8);
                Log.d(TAG, "XML read OK length=" + xml.length());

                // Échapper pour template literal JS
                final String safe = xml
                    .replace("\\", "\\\\")
                    .replace("`",  "\\`")
                    .replace("$",  "\\$");

                mainHandler.post(() -> navigateAndInject(safe));

            } catch (Exception e) {
                Log.e(TAG, "readXmlAndInject error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur lecture XML : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Naviguer vers la page principale et injecter le XML ──────────────────
    private void navigateAndInject(String safeXml) {
        xmlInjected = false;
        Log.d(TAG, "Loading app page");
        webView.loadUrl("file:///android_asset/index.html");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!url.contains("android_asset") || xmlInjected) return;
                Log.d(TAG, "App page ready, injecting XML in 800ms");

                // Restaurer le client par défaut
                webView.setWebViewClient(defaultClient());

                mainHandler.postDelayed(() -> {
                    xmlInjected = true;
                    String js =
                        "(function(){" +
                        "try{" +
                        "  var xml=`" + safeXml + "`;" +
                        "  var evs=parseXML(xml);" +
                        "  if(!evs||evs.length===0){" +
                        "    Android.showToast('⚠️ Aucun événement trouvé');" +
                        "    return;" +
                        "  }" +
                        "  events=evs;" +
                        "  showPreview(evs);" +
                        "  Android.onXmlLoaded(evs.length);" +
                        "}catch(e){" +
                        "  Android.showToast('Erreur JS: '+e.message);" +
                        "}" +
                        "})();";

                    webView.evaluateJavascript(js, result ->
                        Log.d(TAG, "JS result: " + result));
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

        // Si un XML en attente existe déjà au démarrage (ex: app re-ouverte), l'injecter
        File pending = getXmlFile();
        if (pending.exists() && !polling) {
            Log.d(TAG, "Found pending XML on start, injecting");
            mainHandler.postDelayed(() -> readXmlAndInject(), 1200);
        }
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

        // ── DownloadListener ─────────────────────────────────────────────────
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                // Supprimer l'ancien XML s'il existe (évite conflit)
                File old = getXmlFile();
                if (old.exists()) old.delete();

                // Télécharger dans le dossier privé de l'app avec nom fixe
                File dest = getXmlFile();
                Log.d(TAG, "Downloading to: " + dest.getAbsolutePath());

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle("Horaire TCS");
                req.setDescription("Téléchargement en cours...");
                // Dossier privé : pas de permission needed, pas visible dans Files
                req.setDestinationUri(Uri.fromFile(dest));
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                pendingDownloadId = dm.enqueue(req);
                polling = true;
                xmlInjected = false;

                Log.d(TAG, "Download enqueued id=" + pendingDownloadId);
                Toast.makeText(this, "⬇️ Téléchargement en cours…", Toast.LENGTH_SHORT).show();

                // Démarrer le polling
                mainHandler.removeCallbacks(pollDownload);
                mainHandler.postDelayed(pollDownload, 1500);

            } catch (Exception e) {
                Log.e(TAG, "Download setup error", e);
                Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ── JavaScript Bridge ─────────────────────────────────────────────────────
    public class AndroidBridge {

        /** Appelé après injection réussie → supprimer le XML */
        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "onXmlLoaded count=" + count);
            mainHandler.post(() ->
                Toast.makeText(MainActivity.this,
                    "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());
            deleteXml();
        }

        /** Appelé après export ICS → supprimer le XML si pas encore fait */
        @JavascriptInterface
        public void onIcsExported() {
            deleteXml();
        }

        private void deleteXml() {
            File f = getXmlFile();
            if (f.exists()) {
                boolean ok = f.delete();
                Log.d(TAG, "XML deleted=" + ok);
            }
        }

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
        public String getAppVersion() { return "1.5"; }
    }
}

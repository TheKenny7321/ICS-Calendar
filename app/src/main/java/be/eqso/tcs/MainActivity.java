package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
    private static final String XML_NAME = "tcs_export.xml";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private long    pendingDownloadId   = -1;
    private boolean polling             = false;
    private boolean xmlInjected         = false;
    private boolean webViewVisible      = false; // true = on est sur le site TCS

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Dossier privé (cache interne, pas besoin de permission) ──────────────
    private File getPrivateXmlDir() {
        File dir = new File(getCacheDir(), "tcs_xml");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File getPrivateXmlFile() {
        return new File(getPrivateXmlDir(), XML_NAME);
    }

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

    // ── Polling DownloadManager ───────────────────────────────────────────────
    private final Runnable pollDownload = new Runnable() {
        @Override
        public void run() {
            if (!polling || pendingDownloadId < 0) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(pendingDownloadId);
            Cursor c = dm.query(q);

            if (c == null) { mainHandler.postDelayed(this, 1000); return; }

            if (c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Récupérer le chemin public du fichier téléchargé
                    String publicPath = c.getString(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
                    c.close();
                    polling = false;
                    Log.d(TAG, "Download OK at: " + publicPath);
                    copyToPrivateAndProcess(publicPath);

                } else if (status == DownloadManager.STATUS_FAILED) {
                    c.close();
                    polling = false;
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Téléchargement échoué", Toast.LENGTH_LONG).show());

                } else {
                    c.close();
                    mainHandler.postDelayed(this, 1000);
                }
            } else {
                c.close();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    // ── Copier dans le dossier privé puis supprimer l'original ───────────────
    private void copyToPrivateAndProcess(final String publicPath) {
        new Thread(() -> {
            try {
                File src = new File(publicPath);

                // Attendre max 5s que le fichier soit accessible
                int tries = 0;
                while ((!src.exists() || src.length() == 0) && tries < 10) {
                    Thread.sleep(500);
                    tries++;
                }

                if (!src.exists()) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Fichier XML introuvable : " + publicPath,
                        Toast.LENGTH_LONG).show());
                    return;
                }

                // Copier vers le cache privé
                File dest = getPrivateXmlFile();
                if (dest.exists()) dest.delete();

                FileInputStream  fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                fis.close();
                fos.close();

                // Supprimer l'original dans Downloads
                src.delete();
                Log.d(TAG, "Copied to private, deleted original");

                // Lire et injecter
                readPrivateXmlAndInject();

            } catch (Exception e) {
                Log.e(TAG, "copyToPrivate error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur copie XML : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Lire le XML privé et injecter dans la page ───────────────────────────
    private void readPrivateXmlAndInject() {
        try {
            File f = getPrivateXmlFile();
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            fis.close();
            String xml = new String(buf, StandardCharsets.UTF_8);
            Log.d(TAG, "Private XML read OK, length=" + xml.length());

            final String safe = xml
                .replace("\\", "\\\\")
                .replace("`",  "\\`")
                .replace("$",  "\\$");

            // Fermer le navigateur TCS et charger la page principale
            mainHandler.post(() -> {
                webViewVisible = false;
                navigateAndInject(safe);
            });

        } catch (Exception e) {
            Log.e(TAG, "readPrivateXml error", e);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "Erreur lecture XML : " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    // ── Charger index.html puis injecter le XML via JS ────────────────────────
    private void navigateAndInject(String safeXml) {
        xmlInjected = false;
        Log.d(TAG, "navigateAndInject: loading index.html");

        // Forcer le retour à la page principale (ferme le "navigateur" TCS)
        webView.stopLoading();
        webView.clearHistory();  // empêche le retour-arrière vers TCS
        webView.loadUrl("file:///android_asset/index.html");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!url.contains("android_asset") || xmlInjected) return;
                Log.d(TAG, "index.html loaded, injecting in 800ms");
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

        // Si XML en attente au démarrage (app rouverte après download)
        File pending = getPrivateXmlFile();
        if (pending.exists()) {
            Log.d(TAG, "Pending XML found on start");
            mainHandler.postDelayed(() -> readPrivateXmlAndInject(), 1200);
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
                // Nom fixe pour éviter Export-1.xml, Export-2.xml, etc.
                String filename = "tcs_export.xml";

                // Supprimer l'éventuel résidu dans Downloads
                File old = new File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), filename);
                if (old.exists()) old.delete();

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType("text/xml");
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle("Horaire TCS");
                req.setDescription("Téléchargement en cours...");
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                // Télécharger dans Downloads public (seul endroit accepté par DownloadManager)
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename);

                // Annuler le download précédent s'il existe
                if (pendingDownloadId >= 0) {
                    DownloadManager dm0 = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm0.remove(pendingDownloadId);
                }

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                pendingDownloadId = dm.enqueue(req);
                polling    = true;
                xmlInjected = false;

                Log.d(TAG, "Download enqueued id=" + pendingDownloadId + " file=" + filename);
                Toast.makeText(this, "⬇️ Téléchargement en cours…", Toast.LENGTH_SHORT).show();

                // Lancer le polling
                mainHandler.removeCallbacks(pollDownload);
                mainHandler.postDelayed(pollDownload, 1500);

            } catch (Exception e) {
                Log.e(TAG, "DownloadListener error", e);
                Toast.makeText(this, "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack() && webViewVisible) {
            // Sur le site TCS → retour arrière dans le navigateur
            webView.goBack();
        } else if (webViewVisible) {
            // Sortir du navigateur TCS → retour à l'app
            webViewVisible = false;
            webView.stopLoading();
            webView.clearHistory();
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            super.onBackPressed();
        }
    }

    // ── JavaScript Bridge ─────────────────────────────────────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "onXmlLoaded count=" + count);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());
            deletePrivateXml();
        }

        @JavascriptInterface
        public void onIcsExported() {
            deletePrivateXml();
        }

        private void deletePrivateXml() {
            File f = getPrivateXmlFile();
            if (f.exists()) {
                boolean ok = f.delete();
                Log.d(TAG, "Private XML deleted=" + ok);
            }
        }

        @JavascriptInterface
        public void notifyWebViewOpen() {
            webViewVisible = true;
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
        public String getAppVersion() { return "1.6"; }
    }
}

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
    private long    pendingDownloadId = -1;
    private boolean polling           = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    // ── Polling : attend la fin du téléchargement ─────────────────────────────
    private final Runnable pollDownload = new Runnable() {
        @Override
        public void run() {
            if (!polling || pendingDownloadId < 0) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(pendingDownloadId);
            Cursor c = dm.query(q);

            if (c == null) { mainHandler.postDelayed(this, 1000); return; }

            if (!c.moveToFirst()) {
                c.close();
                mainHandler.postDelayed(this, 1000);
                return;
            }

            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String path = c.getString(
                    c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
                c.close();
                polling = false;
                Log.d(TAG, "Download done: " + path);
                // Copier en privé, supprimer l'original, puis charger l'écran principal
                copyThenShowHome(path);

            } else if (status == DownloadManager.STATUS_FAILED) {
                c.close();
                polling = false;
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "❌ Téléchargement échoué", Toast.LENGTH_LONG).show());

            } else {
                c.close();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    // ── Copier le XML en cache privé puis afficher l'écran principal ──────────
    private void copyThenShowHome(final String publicPath) {
        new Thread(() -> {
            try {
                // Attendre que le fichier soit disponible
                File src = new File(publicPath);
                for (int i = 0; i < 10 && (!src.exists() || src.length() == 0); i++) {
                    Thread.sleep(500);
                }
                if (!src.exists() || src.length() == 0) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Fichier XML non accessible", Toast.LENGTH_LONG).show());
                    return;
                }

                // Copier dans le cache privé de l'app
                File dest = new File(getCacheDir(), XML_NAME);
                if (dest.exists()) dest.delete();
                try (FileInputStream  in  = new FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                // Supprimer le fichier public
                src.delete();
                Log.d(TAG, "XML copied to cache, original deleted");

                // Lire le contenu
                FileInputStream fis = new FileInputStream(dest);
                byte[] bytes = new byte[(int) dest.length()];
                fis.read(bytes);
                fis.close();
                final String xml = new String(bytes, StandardCharsets.UTF_8);

                // Retour sur le thread UI : charger index.html puis injecter
                mainHandler.post(() -> loadHomeAndInject(xml));

            } catch (Exception e) {
                Log.e(TAG, "copyThenShowHome error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Charger index.html, attendre qu'elle soit prête, injecter le XML ──────
    private void loadHomeAndInject(final String xml) {
        // Échapper pour template literal JS
        final String safe = xml
            .replace("\\", "\\\\")
            .replace("`",  "\\`")
            .replace("$",  "\\$");

        // Empêcher le retour vers le site TCS
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
                // Remettre le client par défaut
                webView.setWebViewClient(defaultClient());
                // Délai pour laisser le JS s'initialiser
                mainHandler.postDelayed(() -> {
                    String js =
                        "(function(){" +
                        "try{" +
                        "  var evs=parseXML(`" + safe + "`);" +
                        "  if(!evs||evs.length===0){" +
                        "    Android.showToast('⚠️ Aucun événement trouvé');" +
                        "    return;" +
                        "  }" +
                        "  events=evs;" +
                        "  showPreview(evs);" +
                        "  Android.onXmlLoaded(evs.length);" +
                        "}catch(e){" +
                        "  Android.showToast('Erreur: '+e.message);" +
                        "}" +
                        "})();";
                    webView.evaluateJavascript(js, res -> Log.d(TAG, "JS result: " + res));
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

        // ── DownloadListener : intercepte l'export XML du site TCS ───────────
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                // Toujours le même nom → écrase le précédent, jamais de Export-1.xml
                String filename = XML_NAME;

                // Supprimer l'éventuel résidu dans Downloads
                File old = new File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), filename);
                if (old.exists()) old.delete();

                // Annuler un éventuel download précédent
                if (pendingDownloadId >= 0) {
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE))
                        .remove(pendingDownloadId);
                }

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType("text/xml");
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle("Horaire TCS");
                req.setDescription("Récupération du planning...");
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                pendingDownloadId = dm.enqueue(req);
                polling = true;

                Log.d(TAG, "Download started id=" + pendingDownloadId);
                Toast.makeText(this, "⬇️ Récupération du planning…", Toast.LENGTH_SHORT).show();

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
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ── JavaScript Bridge ─────────────────────────────────────────────────────
    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            // Supprimer le XML du cache privé
            File f = new File(getCacheDir(), XML_NAME);
            if (f.exists()) f.delete();
            Log.d(TAG, "XML deleted after load, count=" + count);
        }

        @JavascriptInterface
        public void onIcsExported() {
            File f = new File(getCacheDir(), XML_NAME);
            if (f.exists()) f.delete();
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
        public String getAppVersion() { return "1.7"; }
    }
}

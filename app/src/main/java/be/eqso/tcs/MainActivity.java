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

    private static final String TAG = "TCSCalendar";
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private long    pendingDownloadId   = -1;
    private String  pendingDownloadPath = null;
    private boolean polling             = false;

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

    // ── Polling DownloadManager ───────────────────────────────────────────────
    private final Runnable pollDownload = new Runnable() {
        @Override
        public void run() {
            if (pendingDownloadId < 0 || !polling) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(pendingDownloadId);
            Cursor c = dm.query(q);

            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Récupérer le chemin du fichier
                    String path = c.getString(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
                    String uri  = c.getString(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                    c.close();
                    polling = false;
                    pendingDownloadPath = path;
                    Log.d(TAG, "Download OK path=" + path + " uri=" + uri);
                    readAndInjectXml(path, uri);

                } else if (status == DownloadManager.STATUS_FAILED) {
                    c.close();
                    polling = false;
                    Toast.makeText(MainActivity.this,
                        "❌ Téléchargement échoué", Toast.LENGTH_LONG).show();

                } else {
                    // Encore en cours → repoll dans 1s
                    c.close();
                    mainHandler.postDelayed(this, 1000);
                }
            } else {
                if (c != null) c.close();
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    // ── Lire le XML et l'injecter ─────────────────────────────────────────────
    private void readAndInjectXml(String localPath, String localUri) {
        new Thread(() -> {
            try {
                String xml = null;

                // Essai 1 : chemin direct
                if (localPath != null) {
                    File f = new File(localPath);
                    if (f.exists()) {
                        FileInputStream fis = new FileInputStream(f);
                        byte[] buf = new byte[(int) f.length()];
                        fis.read(buf);
                        fis.close();
                        xml = new String(buf, StandardCharsets.UTF_8);
                        Log.d(TAG, "Read from path OK, len=" + xml.length());
                    }
                }

                // Essai 2 : Downloads folder par nom
                if (xml == null && localPath != null) {
                    File f2 = new File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                        new File(localPath).getName());
                    if (f2.exists()) {
                        FileInputStream fis = new FileInputStream(f2);
                        byte[] buf = new byte[(int) f2.length()];
                        fis.read(buf);
                        fis.close();
                        xml = new String(buf, StandardCharsets.UTF_8);
                        pendingDownloadPath = f2.getAbsolutePath();
                        Log.d(TAG, "Read from Downloads OK, len=" + xml.length());
                    }
                }

                // Essai 3 : content URI
                if (xml == null && localUri != null) {
                    InputStream is = getContentResolver()
                        .openInputStream(Uri.parse(localUri));
                    if (is != null) {
                        byte[] buf = is.readAllBytes();
                        is.close();
                        xml = new String(buf, StandardCharsets.UTF_8);
                        Log.d(TAG, "Read from URI OK, len=" + xml.length());
                    }
                }

                if (xml == null) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Impossible de lire le fichier XML téléchargé",
                        Toast.LENGTH_LONG).show());
                    return;
                }

                final String xmlFinal = xml;
                mainHandler.post(() -> navigateAndInject(xmlFinal));

            } catch (Exception e) {
                Log.e(TAG, "readAndInjectXml error", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Naviguer vers l'app puis injecter le XML via JS ──────────────────────
    private void navigateAndInject(String xml) {
        // Échapper pour template literal JS
        final String safe = xml
            .replace("\\", "\\\\")
            .replace("`",  "\\`")
            .replace("$",  "\\$");

        Log.d(TAG, "Navigating to app page");
        webView.loadUrl("file:///android_asset/index.html");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!url.contains("android_asset")) return;
                Log.d(TAG, "App page loaded, injecting JS in 800ms");

                // Remettre le client normal
                webView.setWebViewClient(defaultClient());

                // Laisser 800ms au JS de la page pour s'initialiser
                mainHandler.postDelayed(() -> {
                    String js = "(function(){"
                        + "try{"
                        + "  var xml=`" + safe + "`;"
                        + "  var evs=parseXML(xml);"
                        + "  if(!evs||evs.length===0){"
                        + "    Android.showToast('⚠️ Aucun événement trouvé');"
                        + "    return;"
                        + "  }"
                        + "  events=evs;"
                        + "  showPreview(evs);"
                        + "  Android.onXmlLoaded(evs.length);"
                        + "}catch(e){"
                        + "  Android.showToast('Erreur JS: '+e.message);"
                        + "}"
                        + "})();";

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

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            try {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                Log.d(TAG, "Download start: " + filename);

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle(filename);
                req.setDescription("Horaire TCS");
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                pendingDownloadId = dm.enqueue(req);
                polling = true;
                Log.d(TAG, "Polling started for id=" + pendingDownloadId);

                Toast.makeText(this,
                    "⬇️ Téléchargement en cours…", Toast.LENGTH_SHORT).show();

                // Démarrer le polling
                mainHandler.postDelayed(pollDownload, 1000);

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
                Toast.makeText(this, "Erreur : " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
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
            Log.d(TAG, "onXmlLoaded count=" + count);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());

            // Supprimer le XML
            if (pendingDownloadPath != null) {
                boolean ok = new File(pendingDownloadPath).delete();
                Log.d(TAG, "XML deleted=" + ok);
                pendingDownloadPath = null;
            }
        }

        @JavascriptInterface
        public void onIcsExported() {
            if (pendingDownloadPath != null) {
                new File(pendingDownloadPath).delete();
                pendingDownloadPath = null;
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
        public String getAppVersion() { return "1.4"; }
    }
}

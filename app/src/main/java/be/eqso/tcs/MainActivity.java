package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
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
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TCSCalendar";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastDownloadId = -1;
    private String lastDownloadedPath = null;
    private boolean appPageReady = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) results = new Uri[]{uri};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        });

    // ── Receiver : téléchargement terminé ────────────────────────────────────
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            Log.d(TAG, "Download received id=" + id + " lastId=" + lastDownloadId);
            if (id != lastDownloadId) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(id);
            Cursor c = dm.query(q);

            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                Log.d(TAG, "Download status=" + status);

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Récupérer le chemin — sur Android récent c'est un content:// URI
                    String localUri = c.getString(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                    String localPath = c.getString(
                        c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME));
                    Log.d(TAG, "localUri=" + localUri + " localPath=" + localPath);
                    c.close();
                    processDownloadedFile(localUri, localPath);
                } else {
                    c.close();
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Téléchargement échoué (status=" + status + ")",
                        Toast.LENGTH_LONG).show());
                }
            } else {
                Log.e(TAG, "Cursor null or empty");
                if (c != null) c.close();
            }
        }
    };

    // ── Lire le fichier XML et l'injecter dans le JS ──────────────────────────
    private void processDownloadedFile(String localUri, String localPath) {
        new Thread(() -> {
            try {
                String xmlContent = null;

                // Essai 1 : chemin fichier direct
                if (localPath != null && !localPath.isEmpty()) {
                    File f = new File(localPath);
                    if (f.exists() && f.canRead()) {
                        Log.d(TAG, "Reading from path: " + localPath);
                        FileInputStream fis = new FileInputStream(f);
                        byte[] buf = new byte[(int) f.length()];
                        fis.read(buf);
                        fis.close();
                        xmlContent = new String(buf, StandardCharsets.UTF_8);
                        lastDownloadedPath = localPath;
                    }
                }

                // Essai 2 : content URI via ContentResolver
                if (xmlContent == null && localUri != null) {
                    Log.d(TAG, "Reading from URI: " + localUri);
                    Uri uri = Uri.parse(localUri);
                    InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null) {
                        byte[] buf = is.readAllBytes();
                        is.close();
                        xmlContent = new String(buf, StandardCharsets.UTF_8);
                        lastDownloadedPath = localPath; // peut être null, on gère
                    }
                }

                if (xmlContent == null) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ Impossible de lire le fichier XML", Toast.LENGTH_LONG).show());
                    return;
                }

                Log.d(TAG, "XML read OK, length=" + xmlContent.length());

                // Préparer pour injection JS (échapper backticks et $)
                final String xmlEscaped = xmlContent
                    .replace("\\", "\\\\")
                    .replace("`", "\\`")
                    .replace("$", "\\$");

                mainHandler.post(() -> injectXmlIntoApp(xmlEscaped));

            } catch (Exception e) {
                Log.e(TAG, "Error reading XML", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    "Erreur lecture : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Charger la page principale et injecter le XML ────────────────────────
    private void injectXmlIntoApp(String xmlEscaped) {
        Log.d(TAG, "Loading app page and injecting XML");

        // Naviguer vers la page principale
        webView.loadUrl("file:///android_asset/index.html");

        // Remplacer le WebViewClient temporairement pour détecter quand la page est prête
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page finished: " + url);
                if (!url.contains("android_asset")) return;

                // Rétablir le WebViewClient normal
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        return false;
                    }
                });

                // Délai court pour que le JS de la page soit bien initialisé
                mainHandler.postDelayed(() -> {
                    String js =
                        "(function() {" +
                        "  try {" +
                        "    var xml = `" + xmlEscaped + "`;" +
                        "    var evs = parseXML(xml);" +
                        "    if (!evs || evs.length === 0) {" +
                        "      Android.showToast('⚠️ Aucun événement trouvé dans le XML');" +
                        "      return;" +
                        "    }" +
                        "    events = evs;" +
                        "    showPreview(evs);" +
                        "    Android.onXmlLoaded(evs.length);" +
                        "  } catch(e) {" +
                        "    Android.showToast('❌ Erreur JS : ' + e.message);" +
                        "  }" +
                        "})();";

                    webView.evaluateJavascript(js, value ->
                        Log.d(TAG, "JS injection result: " + value));
                }, 600);
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setupWebView();

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/xml", "application/xml", "*/*"});
                filePickerLauncher.launch(Intent.createChooser(intent, "Choisir le fichier XML"));
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                Log.d(TAG, "Download triggered: " + filename + " mime=" + mimeType);

                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.addRequestHeader("User-Agent", userAgent);
                req.setTitle(filename);
                req.setDescription("Horaire TCS...");
                req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                lastDownloadId = dm.enqueue(req);
                Log.d(TAG, "Download enqueued id=" + lastDownloadId);

                Toast.makeText(this, "⬇️ Téléchargement en cours…", Toast.LENGTH_SHORT).show();

            } catch (Exception e) {
                Log.e(TAG, "Download error", e);
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

    // ── Bridge Android ↔ JavaScript ──────────────────────────────────────────

    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "XML loaded, events=" + count);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());

            // Supprimer le fichier XML
            if (lastDownloadedPath != null) {
                File f = new File(lastDownloadedPath);
                if (f.exists()) {
                    boolean ok = f.delete();
                    Log.d(TAG, "XML deleted: " + ok + " path=" + lastDownloadedPath);
                }
                lastDownloadedPath = null;
            }
        }

        @JavascriptInterface
        public void onIcsExported() {
            // Sécurité : supprimer si pas encore fait
            if (lastDownloadedPath != null) {
                new File(lastDownloadedPath).delete();
                lastDownloadedPath = null;
            }
        }

        @JavascriptInterface
        public void saveAndOpenICS(String icsContent, String filename, boolean openCal) {
            mainHandler.post(() -> {
                try {
                    File cacheDir = new File(getCacheDir(), "ics");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File icsFile = new File(cacheDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(icsFile)) {
                        fos.write(icsContent.getBytes(StandardCharsets.UTF_8));
                    }
                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", icsFile);

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
                        openCal ? "Ouvrir avec…" : "Partager le planning…"));

                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void showToast(String message) {
            mainHandler.post(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public String getAppVersion() { return "1.3"; }
    }
}

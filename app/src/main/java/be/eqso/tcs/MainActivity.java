package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
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
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private long lastDownloadId = -1;

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

    // Receiver déclenché quand le DownloadManager a fini
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != lastDownloadId) return;

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(id);
            android.database.Cursor c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // Notifier le JS que le fichier est prêt
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "if(window.onXmlDownloaded) window.onXmlDownloaded();", null);
                        Toast.makeText(MainActivity.this,
                            "✅ XML téléchargé — choisissez-le dans l'app", Toast.LENGTH_LONG).show();
                    });
                }
                c.close();
            }
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setupWebView();

        // Enregistrer le receiver pour les téléchargements
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
        unregisterReceiver(downloadReceiver);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // User-agent normal pour que TCS ne bloque pas la WebView
        settings.setUserAgentString(
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
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/xml","application/xml","*/*"});
                filePickerLauncher.launch(Intent.createChooser(intent, "Choisir le fichier XML"));
                return true;
            }
        });

        // ── DownloadListener : intercepte tous les téléchargements de la WebView ──
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);

                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);

                // Transférer les cookies de session (indispensable pour TCS)
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) request.addRequestHeader("Cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);

                request.setDescription("Téléchargement de l'horaire TCS...");
                request.setTitle(filename);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, filename);

                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                lastDownloadId = dm.enqueue(request);

                Toast.makeText(MainActivity.this,
                    "⬇️ Téléchargement en cours…\nOuvrez l'app et choisissez le fichier quand c'est fini.",
                    Toast.LENGTH_LONG).show();

            } catch (Exception e) {
                Toast.makeText(MainActivity.this,
                    "Erreur téléchargement : " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        public void saveAndOpenICS(String icsContent, String filename, boolean openCal) {
            runOnUiThread(() -> {
                try {
                    File cacheDir = new File(getCacheDir(), "ics");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File icsFile = new File(cacheDir, filename);
                    try (FileOutputStream fos = new FileOutputStream(icsFile)) {
                        fos.write(icsContent.getBytes("UTF-8"));
                    }
                    Uri contentUri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", icsFile);

                    if (openCal) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(contentUri, "text/calendar");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(intent, "Ouvrir avec…"));
                    } else {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/calendar");
                        share.putExtra(Intent.EXTRA_STREAM, contentUri);
                        share.putExtra(Intent.EXTRA_SUBJECT, "Horaire TCS");
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                       Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(share, "Partager le planning…"));
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Erreur : " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String readFileContent(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return null;
                byte[] bytes = is.readAllBytes();
                is.close();
                return new String(bytes, "UTF-8");
            } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getAppVersion() { return "1.1"; }
    }
}

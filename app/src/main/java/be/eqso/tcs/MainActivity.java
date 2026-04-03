package be.eqso.tcs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Launcher pour le file picker (upload XML)
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

        // Bridge Java ↔ JavaScript
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Laisser tout charger dans la WebView
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                String[] mimetypes = {"text/xml", "application/xml", "*/*"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
                filePickerLauncher.launch(Intent.createChooser(intent, "Choisir le fichier XML"));
                return true;
            }
        });

        // Charger l'app (assets/index.html)
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── Bridge Android ↔ JavaScript ──────────────────────────────────────────

    public class AndroidBridge {

        /**
         * Appelé depuis JS pour sauvegarder le .ics et l'ouvrir avec Google Calendar
         * @param icsContent  contenu du fichier .ics
         * @param filename    nom du fichier (ex: horaire-tcs-2026-04.ics)
         * @param openCal     true = ouvrir dans Calendar, false = partager/sauvegarder
         */
        @JavascriptInterface
        public void saveAndOpenICS(String icsContent, String filename, boolean openCal) {
            runOnUiThread(() -> {
                try {
                    // Écrire dans le cache de l'app
                    File cacheDir = new File(getCacheDir(), "ics");
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    File icsFile = new File(cacheDir, filename);

                    try (FileOutputStream fos = new FileOutputStream(icsFile)) {
                        fos.write(icsContent.getBytes("UTF-8"));
                    }

                    Uri contentUri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        icsFile
                    );

                    if (openCal) {
                        // Ouvrir directement dans Google Calendar / app calendrier
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(contentUri, "text/calendar");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(intent, "Ouvrir avec…"));
                    } else {
                        // Partager (Share sheet Android)
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/calendar");
                        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Horaire TCS");
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(shareIntent, "Partager le planning…"));
                    }

                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        /**
         * Lire un fichier XML sélectionné par l'utilisateur
         * Appelé depuis JS via <input type=file> — le contenu est passé directement en JS,
         * cette méthode est un backup pour lire depuis content URI si besoin
         */
        @JavascriptInterface
        public String readFileContent(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return null;
                byte[] bytes = is.readAllBytes();
                is.close();
                return new String(bytes, "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
            );
        }

        @JavascriptInterface
        public String getAppVersion() {
            return "1.0";
        }
    }
}

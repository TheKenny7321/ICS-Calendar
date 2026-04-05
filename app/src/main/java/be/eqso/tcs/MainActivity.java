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
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TCSCalendar";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String  autoLoginUser  = null;
    private String  autoLoginPass  = null;
    private boolean confirmExit    = false;
    private long    lastBackPress  = 0;

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

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                is.close();
                conn.disconnect();

                String xml = bos.toString("UTF-8");
                Log.d(TAG, "XML downloaded, length=" + xml.length());

                String trimmed = xml.trim();
                if (!trimmed.startsWith("<") || trimmed.toLowerCase().startsWith("<!doctype html")) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this,
                        "❌ La réponse n'est pas un XML valide",
                        Toast.LENGTH_LONG).show());
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
                    "Erreur téléchargement : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void loadHomeAndInject(final String safeXml) {
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
                        "    return (i.type==='text'||i.type==='email'||i.type==='')" +
                        "      &&!i.disabled&&!i.readOnly" +
                        "      &&i.offsetParent!==null;" +
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
                        "  ['input','change','keyup','blur'].forEach(function(ev){" +
                        "    u.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "    p.dispatchEvent(new Event(ev,{bubbles:true}));" +
                        "  });" +
                        "  var btn=document.querySelector(" +
                        "    'input[type=submit],button[type=submit]');" +
                        "  if(!btn){" +
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
            Log.d(TAG, "Download intercepted: " + url);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(url);
            if (cookies == null) cookies = "";
            downloadXmlDirectly(url, cookies);
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "(function(){" +
            "  if(document.getElementById('codesPanel')&&document.getElementById('codesPanel').classList.contains('open'))return 'codes';" +
            "  if(document.getElementById('sPanel')&&document.getElementById('sPanel').classList.contains('open'))return 'settings';" +
            "  return 'none';" +
            "})()",
            result -> {

                String clean = result != null ? result.replace("\"", "") : "";

                if ("codes".equals(clean)) {
                    mainHandler.post(() ->
                        webView.evaluateJavascript("closeCodesPanel();", null));

                } else if ("settings".equals(clean)) {
                    mainHandler.post(() ->
                        webView.evaluateJavascript("closeSettings();", null));

                } else if (webView.canGoBack()) {
                    mainHandler.post(() -> webView.goBack());

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

    public class AndroidBridge {

        @JavascriptInterface
        public void onXmlLoaded(int count) {
            Log.d(TAG, "onXmlLoaded count=" + count);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                "✅ " + count + " événements chargés !", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface public void onIcsExported() {}

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
            mainHandler.post(() ->
                webView.loadUrl("https://tcs.eqso.be/RHTime"));
        }

        @JavascriptInterface
        public String getAppVersion() { return "2.0"; }
    }
}
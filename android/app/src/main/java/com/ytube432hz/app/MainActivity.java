package com.ytube432hz.app;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private String pitchCode = "";
    private String injectCode = "";

    // Same UA as the Electron app
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen, no action bar, black background
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        // Load JS assets
        pitchCode = readAsset("pitch-processor.js");
        injectCode = readAsset("inject.js");

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(UA);

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isAdUrl(url)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectScript(view);
            }
        });

        webView.loadUrl("https://m.youtube.com");
    }

    private void injectScript(WebView view) {
        if (pitchCode.isEmpty() || injectCode.isEmpty()) return;
        // Safely encode pitch processor code as a JSON string literal
        String quoted = JSONObject.quote(pitchCode);
        String js = "window.__PITCH_CODE__=" + quoted + ";\n" + injectCode;
        view.evaluateJavascript(js, null);
    }

    private static final String[] AD_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com",
        "imasdk.googleapis.com", "static.doubleclick.net"
    };

    private boolean isAdUrl(String url) {
        for (String host : AD_HOSTS) {
            if (url.contains(host)) return true;
        }
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            if (url.contains("/pagead/") || url.contains("/ptracking")
                || url.contains("/api/stats/ads") || url.contains("&ad_type=")
                || url.contains("/get_midroll_info")) return true;
        }
        return false;
    }

    private String readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            is.close();
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private long lastBackPress = 0;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            String url = webView.getUrl();
            boolean atHome = url == null
                || url.equals("https://m.youtube.com/")
                || url.equals("https://m.youtube.com")
                || url.startsWith("https://m.youtube.com/?");
            if (!atHome && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            // At home — double-tap back to exit
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                android.widget.Toast.makeText(this, "Tryk tilbage igen for at lukke", android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't pause WebView — keeps audio playing when screen locks or user switches app
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}

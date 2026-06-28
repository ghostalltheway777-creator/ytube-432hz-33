package com.ytube432hz.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.util.Rational;
import java.util.ArrayList;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private String pitchCode = "";
    private String injectCode = "";
    private PowerManager.WakeLock wakeLock;
    private long lastBackPress = 0;
    private AudioFocusRequest focusRequest;

    private static final String ACTION_PIP_TOGGLE = "com.ytube432hz.app.PIP_TOGGLE";
    private BroadcastReceiver pipReceiver;
    private boolean isPlaying = true;

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/125.0.0.0 Mobile Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // Keep CPU running when screen locks so audio continues
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTube432Hz::audio");
        wakeLock.acquire();

        // Notifikations-tilladelse (Android 13+) så foreground-servicens notifikation kan vises
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1);
        }

        // Hold lyd-fokus så systemet ikke pauser os i baggrunden
        requestAudioFocus();

        // Foreground-service holder processen i live når skærmen er låst
        PlaybackService.start(this);

        // Modtager for play/pause-knappen i PiP-vinduet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (ACTION_PIP_TOGGLE.equals(i.getAction()) && webView != null) {
                        webView.evaluateJavascript("window.__q432_toggle && window.__q432_toggle()", value -> {
                            isPlaying = "true".equals(value);
                            updatePipActions();
                        });
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_PIP_TOGGLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pipReceiver, filter);
            }
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        // Behold HOEJ renderer-prioritet OGSAA naar appen ikke er synlig (baggrund/PiP).
        // Uden dette saenker Android prioriteten -> lyden hakker i baggrunden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

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
                if (isAdUrl(request.getUrl().toString())) {
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

        // Back button — works on all Android versions including gesture navigation
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                android.webkit.WebBackForwardList history = webView.copyBackForwardList();
                int idx = history.getCurrentIndex();
                if (idx > 0) {
                    String prevUrl = history.getItemAtIndex(idx - 1).getUrl();
                    if (prevUrl != null && prevUrl.contains("youtube.com")) {
                        webView.goBack();
                        return;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPress < 2000) {
                    finish();
                } else {
                    lastBackPress = now;
                    Toast.makeText(MainActivity.this,
                        "Tryk tilbage igen for at lukke", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void requestAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build();
            am.requestAudioFocus(focusRequest);
        } else {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void injectScript(WebView view) {
        if (pitchCode.isEmpty() || injectCode.isEmpty()) return;
        String quoted = JSONObject.quote(pitchCode);
        view.evaluateJavascript("window.__PITCH_CODE__=" + quoted + ";\n" + injectCode, null);
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
        if (url.contains("youtube.com")) {
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

    // Auto-PiP: naar brugeren forlader appen (Home / app-skift), kroeb ned i et
    // lille vindue i hjoernet og bliv ved med at spille - praecis som YouTube.
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPipIfPossible();
    }

    private void enterPipIfPossible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try {
                enterPictureInPictureMode(buildPipParams());
            } catch (IllegalStateException | IllegalArgumentException e) {
                // PiP ikke muligt lige nu - ignorer stille
            }
        }
    }

    // Bygger PiP-vinduet med en play/pause-knap der afspejler nuvaerende tilstand
    private PictureInPictureParams buildPipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
            .setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int iconRes = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
            String label = isPlaying ? "Pause" : "Afspil";
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_PIP_TOGGLE).setPackage(getPackageName()), piFlags);
            RemoteAction action = new RemoteAction(
                Icon.createWithResource(this, iconRes), label, label, pi);
            ArrayList<RemoteAction> actions = new ArrayList<>();
            actions.add(action);
            b.setActions(actions);
        }
        return b.build();
    }

    private void updatePipActions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode()) {
            try { setPictureInPictureParams(buildPipParams()); } catch (IllegalStateException e) { /* ignore */ }
        }
    }

    // Fortael web-siden om appen er i baggrund, saa den kan auto-genoptage afspilning
    private void setWebBackground(boolean bg) {
        if (webView != null) {
            webView.evaluateJavascript("window.__appBackground=" + (bg ? "true" : "false"), null);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        setWebBackground(inPip);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Do NOT pause WebView — audio must keep playing in background
        setWebBackground(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        setWebBackground(false);
    }

    @Override
    protected void onDestroy() {
        if (pipReceiver != null) {
            try { unregisterReceiver(pipReceiver); } catch (IllegalArgumentException e) { /* ikke registreret */ }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopService(new android.content.Intent(this, PlaybackService.class));
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            am.abandonAudioFocusRequest(focusRequest);
        }
        webView.destroy();
        super.onDestroy();
    }
}

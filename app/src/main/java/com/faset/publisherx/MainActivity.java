package com.faset.publisherx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;

    // Target Facebook mobile site directly
    private static final String HOME_URL = "https://m.facebook.com";

    // Standard Android Chrome Mobile User-Agent (reduces FB blocks)
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            webView.loadUrl(intent.getData().toString());
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();

        // Core requirements
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // Persistent session / better compatibility
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Mobile Chrome User-Agent
        settings.setUserAgentString(MOBILE_UA);

        // Full cookie persistence (login stays after app restart)
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Keep normal http/https navigation inside WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }

                // Open external schemes (mailto, tel, intent, etc.)
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(ProgressBar.GONE);

                // Only inject on Facebook domains
                if (url != null && (url.contains("facebook.com") || url.contains("fb.com"))) {
                    injectAutomationScript(view);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(ProgressBar.GONE);
                } else {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                }
            }
        });
    }

    /**
     * Script injection engine.
     * Injects a minimal chrome.* mock so extension-style scripts do not crash,
     * then runs the core automation entry point.
     * Replace / extend the JS below with your actual posting logic.
     */
    private void injectAutomationScript(WebView view) {
        String script =
            "(function() {" +
            "  if (window.__FB_PUBLISHER_X_INJECTED__) return;" +
            "  window.__FB_PUBLISHER_X_INJECTED__ = true;" +

            // Minimal chrome.runtime mock (prevents immediate crashes)
            "  if (typeof chrome === 'undefined') { window.chrome = {}; }" +
            "  if (!chrome.runtime) {" +
            "    chrome.runtime = {" +
            "      id: 'fb-publisher-x-webview'," +
            "      sendMessage: function(msg, cb) { if (typeof cb === 'function') try { cb({}); } catch(e){} }," +
            "      connect: function() {" +
            "        return {" +
            "          name: 'keepalive'," +
            "          postMessage: function(){}," +
            "          onDisconnect: { addListener: function(){} }," +
            "          onMessage: { addListener: function(){} }" +
            "        };" +
            "      }," +
            "      onMessage: { addListener: function(){} }," +
            "      lastError: null," +
            "      getURL: function(p) { return p; }" +
            "    };" +
            "  }" +
            "  if (!chrome.storage) {" +
            "    chrome.storage = { local: { get: function(k,cb){ if(cb) cb({}); }, set: function(o,cb){ if(cb) cb(); } } };" +
            "  }" +

            // Marker that injection succeeded (visible in remote debugging / console)
            "  console.log('[FB Publisher X] Injection engine ready on: ' + location.href);" +

            // === PLACE YOUR CORE AUTOMATION LOGIC BELOW ===
            // Example: detect group page and log
            "  function isGroupPage() {" +
            "    return /facebook\\.com\\/groups\\/[^/?#]+/i.test(location.href);" +
            "  }" +
            "  if (isGroupPage()) {" +
            "    console.log('[FB Publisher X] Group page detected');" +
            "  }" +
            // =============================================

            "})();";

        view.evaluateJavascript(script, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            webView.loadUrl(intent.getData().toString());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Flush cookies so session survives app restart
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}

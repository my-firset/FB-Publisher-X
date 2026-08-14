package com.faset.publisherx;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Architecture:
 * - Native Java owns the queue, delays, group list, and session.
 * - Injected JS only performs pure DOM actions (fill text, click Post, detect result).
 * - Communication via AndroidBridge (JavascriptInterface).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FBPublisherX";
    private static final String HOME_URL = "https://m.facebook.com";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private ProgressBar progressBar;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Native queue state ──────────────────────────────────────────
    private final List<PostTask> queue = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPosting = false;
    private long delayBetweenPostsMs = 45000; // 45 seconds default

    public static class PostTask {
        public final String groupUrl;
        public final String text;
        public PostTask(String groupUrl, String text) {
            this.groupUrl = groupUrl;
            this.text = text;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);

        setupWebView();

        // Example queue (replace with your real groups / text later)
        // queue.add(new PostTask("https://m.facebook.com/groups/YOUR_GROUP_ID", "Hello from FB Publisher X"));

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            webView.loadUrl(intent.getData().toString());
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
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
        settings.setUserAgentString(MOBILE_UA);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Bridge: Java <-> JS
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(ProgressBar.GONE);
                if (url != null && (url.contains("facebook.com") || url.contains("fb.com"))) {
                    injectDomHelpers(view);

                    // If we navigated to a group for the current task, trigger the post
                    if (isPosting && currentIndex >= 0 && currentIndex < queue.size()) {
                        PostTask task = queue.get(currentIndex);
                        if (url.contains("/groups/") || url.contains(task.groupUrl)) {
                            mainHandler.postDelayed(() -> executePostOnPage(task.text), 2500);
                        }
                    }
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress == 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }
        });
    }

    /** Pure DOM helpers only – no queue, no delays, no chrome.* */
    private void injectDomHelpers(WebView view) {
        String js =
            "(function(){" +
            "  if (window.__FBX_DOM__) return;" +
            "  window.__FBX_DOM__ = true;" +

            // Fill the composer and click Post
            "  window.FBX_postText = function(text) {" +
            "    try {" +
            "      var selectors = [" +
            "        'div[contenteditable=\\'true\\']'," +
            "        'div[role=\\'textbox\\']'," +
            "        'textarea[name=\\'xc_message\\']'," +
            "        'textarea'" +
            "      ];" +
            "      var box = null;" +
            "      for (var i=0;i<selectors.length;i++){" +
            "        box = document.querySelector(selectors[i]);" +
            "        if (box) break;" +
            "      }" +
            "      if (!box) { AndroidBridge.onPostResult(false, 'composer_not_found'); return; }" +

            "      box.focus();" +
            "      if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {" +
            "        box.value = text;" +
            "        box.dispatchEvent(new Event('input', {bubbles:true}));" +
            "      } else {" +
            "        box.innerText = text;" +
            "        box.dispatchEvent(new InputEvent('input', {bubbles:true, data:text}));" +
            "      }" +

            "      setTimeout(function(){" +
            "        var btn = document.querySelector('button[type=\\'submit\\'], button[name=\\'view_post\\'], div[role=\\'button\\'][aria-label*=\\'Post\\'], div[role=\\'button\\'][aria-label*=\\'نشر\\]');" +
            "        if (!btn) {" +
            "          var buttons = document.querySelectorAll('button, div[role=\\'button\\']');" +
            "          for (var j=0;j<buttons.length;j++){" +
            "            var t = (buttons[j].innerText||'').trim().toLowerCase();" +
            "            if (t === 'post' || t === 'نشر' || t === 'share' || t === 'مشاركة') { btn = buttons[j]; break; }" +
            "          }" +
            "        }" +
            "        if (btn) {" +
            "          btn.click();" +
            "          setTimeout(function(){ AndroidBridge.onPostResult(true, 'clicked'); }, 3000);" +
            "        } else {" +
            "          AndroidBridge.onPostResult(false, 'post_button_not_found');" +
            "        }" +
            "      }, 1200);" +
            "    } catch(e) {" +
            "      AndroidBridge.onPostResult(false, String(e));" +
            "    }" +
            "  };" +

            "  console.log('[FBX] DOM helpers ready');" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    private void executePostOnPage(String text) {
        String safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        webView.evaluateJavascript("window.FBX_postText && window.FBX_postText('" + safe + "');", null);
    }

    /** Start the native queue */
    public void startQueue() {
        if (queue.isEmpty()) {
            Toast.makeText(this, "Queue is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        isPosting = true;
        currentIndex = -1;
        processNext();
    }

    private void processNext() {
        currentIndex++;
        if (currentIndex >= queue.size()) {
            isPosting = false;
            Log.i(TAG, "Queue finished");
            Toast.makeText(this, "Queue finished", Toast.LENGTH_LONG).show();
            return;
        }
        PostTask task = queue.get(currentIndex);
        Log.i(TAG, "Navigating to group " + (currentIndex + 1) + "/" + queue.size());
        webView.loadUrl(task.groupUrl);
        // onPageFinished will trigger the actual post
    }

    private void scheduleNextAfterDelay() {
        mainHandler.postDelayed(this::processNext, delayBetweenPostsMs);
    }

    /** Bridge called from JavaScript */
    public class AndroidBridge {
        @JavascriptInterface
        public void onPostResult(boolean success, String message) {
            Log.i(TAG, "Post result: success=" + success + " msg=" + message);
            mainHandler.post(() -> {
                Toast.makeText(MainActivity.this,
                        success ? "Posted OK" : "Failed: " + message,
                        Toast.LENGTH_SHORT).show();
                if (isPosting) {
                    scheduleNextAfterDelay();
                }
            });
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
        }
    }

    // ── Public helpers you can call from UI later ───────────────────
    public void addTask(String groupUrl, String text) {
        queue.add(new PostTask(groupUrl, text));
    }

    public void setDelaySeconds(int seconds) {
        delayBetweenPostsMs = Math.max(10, seconds) * 1000L;
    }

    public void clearQueue() {
        queue.clear();
        currentIndex = -1;
        isPosting = false;
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
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}

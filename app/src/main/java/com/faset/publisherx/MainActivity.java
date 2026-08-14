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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FBPublisherX";
    private static final String DASHBOARD_URL = "file:///android_asset/dashboard/index.html";
    private static final String FB_HOME = "https://m.facebook.com";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private ProgressBar progressBar;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<PostTask> queue = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPosting = false;
    private boolean showingDashboard = true;
    private long delayBetweenPostsMs = 45000;
    private String currentGroupLabel = "";

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
        webView.loadUrl(DASHBOARD_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(MOBILE_UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
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
                if (url == null) return;

                if (url.startsWith("file://")) {
                    showingDashboard = true;
                    return;
                }

                showingDashboard = false;
                if (url.contains("facebook.com") || url.contains("fb.com")) {
                    injectDomHelpers(view);
                    if (isPosting && currentIndex >= 0 && currentIndex < queue.size()) {
                        PostTask task = queue.get(currentIndex);
                        mainHandler.postDelayed(() -> executePostOnPage(task.text), 2800);
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

    private void injectDomHelpers(WebView view) {
        String js =
            "(function(){" +
            "  if (window.__FBX_DOM__) return;" +
            "  window.__FBX_DOM__ = true;" +
            "  window.FBX_postText = function(text) {" +
            "    try {" +
            "      var sels = ['div[contenteditable=true]','div[role=textbox]','textarea[name=xc_message]','textarea'];" +
            "      var box = null;" +
            "      for (var i=0;i<sels.length;i++){ box=document.querySelector(sels[i]); if(box) break; }" +
            "      if (!box) { AndroidBridge.onPostResult(false,'composer_not_found'); return; }" +
            "      box.focus();" +
            "      if (box.tagName==='TEXTAREA'||box.tagName==='INPUT'){" +
            "        box.value=text; box.dispatchEvent(new Event('input',{bubbles:true}));" +
            "      } else {" +
            "        box.innerText=text;" +
            "        box.dispatchEvent(new InputEvent('input',{bubbles:true,data:text}));" +
            "      }" +
            "      setTimeout(function(){" +
            "        var btn=document.querySelector(\"button[type=submit],button[name=view_post],div[role=button][aria-label*='Post'],div[role=button][aria-label*='نشر']\");" +
            "        if(!btn){" +
            "          var bs=document.querySelectorAll('button,div[role=button]');" +
            "          for(var j=0;j<bs.length;j++){" +
            "            var t=(bs[j].innerText||'').trim().toLowerCase();" +
            "            if(t==='post'||t==='نشر'||t==='share'||t==='مشاركة'){btn=bs[j];break;}" +
            "          }" +
            "        }" +
            "        if(btn){ btn.click(); setTimeout(function(){AndroidBridge.onPostResult(true,'clicked');},3500); }" +
            "        else { AndroidBridge.onPostResult(false,'post_button_not_found'); }" +
            "      },1400);" +
            "    } catch(e){ AndroidBridge.onPostResult(false,String(e)); }" +
            "  };" +
            "  console.log('[FBX] DOM helpers ready');" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    private void executePostOnPage(String text) {
        String safe = text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
        webView.evaluateJavascript("window.FBX_postText && window.FBX_postText('" + safe + "');", null);
    }

    private void processNext() {
        currentIndex++;
        if (currentIndex >= queue.size()) {
            isPosting = false;
            mainHandler.post(() -> {
                Toast.makeText(this, "انتهى الطابور", Toast.LENGTH_LONG).show();
                webView.loadUrl(DASHBOARD_URL);
            });
            return;
        }
        PostTask task = queue.get(currentIndex);
        currentGroupLabel = task.groupUrl;
        Log.i(TAG, "Group " + (currentIndex + 1) + "/" + queue.size() + " -> " + task.groupUrl);
        webView.loadUrl(task.groupUrl);
    }

    private void scheduleNextAfterDelay() {
        mainHandler.postDelayed(this::processNext, delayBetweenPostsMs);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void addTask(String groupUrl, String text) {
            queue.add(new PostTask(groupUrl, text));
            Log.i(TAG, "Task added: " + groupUrl);
        }

        @JavascriptInterface
        public void clearQueue() {
            queue.clear();
            currentIndex = -1;
            isPosting = false;
        }

        @JavascriptInterface
        public void setDelaySeconds(int seconds) {
            delayBetweenPostsMs = Math.max(15, seconds) * 1000L;
        }

        @JavascriptInterface
        public void startQueue() {
            mainHandler.post(() -> {
                if (queue.isEmpty()) {
                    Toast.makeText(MainActivity.this, "الطابور فارغ", Toast.LENGTH_SHORT).show();
                    return;
                }
                isPosting = true;
                currentIndex = -1;
                processNext();
            });
        }

        @JavascriptInterface
        public void stopQueue() {
            isPosting = false;
            mainHandler.post(() -> {
                Toast.makeText(MainActivity.this, "تم إيقاف النشر", Toast.LENGTH_SHORT).show();
                webView.loadUrl(DASHBOARD_URL);
            });
        }

        @JavascriptInterface
        public void openFacebook() {
            mainHandler.post(() -> webView.loadUrl(FB_HOME));
        }

        @JavascriptInterface
        public void detectSession() {
            mainHandler.post(() -> {
                // Simple heuristic: if we have FB cookies, consider logged in
                String cookies = CookieManager.getInstance().getCookie("https://m.facebook.com");
                boolean ok = cookies != null && (cookies.contains("c_user=") || cookies.contains("xs="));
                webView.evaluateJavascript("window.setSessionStatus && window.setSessionStatus(" + ok + ");", null);
                Toast.makeText(MainActivity.this, ok ? "الجلسة نشطة" : "غير مسجل الدخول", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void onDashboardReady() {
            Log.i(TAG, "Dashboard ready");
        }

        @JavascriptInterface
        public void onPostResult(boolean success, String message) {
            Log.i(TAG, "Post result: " + success + " / " + message);
            final String label = currentGroupLabel;
            mainHandler.post(() -> {
                String js = "window.onNativePostResult && window.onNativePostResult(" +
                        success + ",'" + message.replace("'", "\\'") + "','" +
                        (label != null ? label.replace("'", "\\'") : "") + "');";
                // If we are still on FB page, go back to dashboard after a short delay for next item
                if (isPosting) {
                    scheduleNextAfterDelay();
                } else {
                    webView.loadUrl(DASHBOARD_URL);
                    mainHandler.postDelayed(() -> webView.evaluateJavascript(js, null), 600);
                }
            });
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
        }
    }

    @Override
    public void onBackPressed() {
        if (!showingDashboard) {
            webView.loadUrl(DASHBOARD_URL);
            return;
        }
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

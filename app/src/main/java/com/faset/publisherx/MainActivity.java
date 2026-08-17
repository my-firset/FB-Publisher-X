package com.faset.publisherx;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publisher X — Independent Native Marketing Dashboard (Arabic)
 * v1.7: Professional UI polish + bulletproof text engine with page-state validation.
 * No Facebook scraping / automated data collection.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PublisherX";
    private static final String PREFS = "publisherx_prefs";
    /** Meta Graph API version used by the verified Page publishing path. */
    private static final String GRAPH_API_VERSION = "v26.0";
    private static final Pattern GROUP_URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.|mobile\\.)?facebook\\.com/groups/([a-zA-Z0-9._-]+)/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_ID_PATTERN = Pattern.compile(
            "(?<![a-zA-Z0-9./_-])(\\d{10,20})(?![a-zA-Z0-9])");

    private String lastLog = "";
    private String pageId = "";
    private String pageAccessToken = "";
    private String pageName = "";
    private BottomNavigationView bottomNav;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final List<String> groupUrls = new ArrayList<>();
    private final List<String> groupNames = new ArrayList<>();
    private final List<String> activityLogs = new ArrayList<>();
    final List<Uri> mediaUris = new ArrayList<>();
    private String postText = "";
    private int minDelaySec = 5;
    private int maxDelaySec = 10;
    private boolean isRunning = false;
    private int currentIndex = -1;
    private int postedCount = 0;
    private int failedCount = 0;
    private int successStreak = 0;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private Future<?> publishTask;
    private final List<Runnable> progressListeners = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bottomNav = findViewById(R.id.bottomNav);

        loadLocalData();
        setupBottomNav();

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new DashboardFragment())
                    .commit();
            bottomNav.setSelectedItemId(R.id.nav_dashboard);
        }
    }

    private void loadLocalData() {
        postText = prefs.getString("post_text", "");
        minDelaySec = Math.max(5, prefs.getInt("min_delay", 5));
        maxDelaySec = Math.max(minDelaySec, prefs.getInt("max_delay", 10));
        postedCount = prefs.getInt("posted_count", 0);
        failedCount = prefs.getInt("failed_count", 0);
        pageId = prefs.getString("page_id", "");
        pageAccessToken = prefs.getString("page_access_token", "");
        pageName = prefs.getString("page_name", "");

        groupUrls.clear();
        groupNames.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString("groups", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                groupUrls.add(o.optString("url", ""));
                groupNames.add(o.optString("name", o.optString("url", "")));
            }
        } catch (Exception ignored) {}

        activityLogs.clear();
        try {
            JSONArray logs = new JSONArray(prefs.getString("activity_logs", "[]"));
            for (int i = 0; i < logs.length(); i++) {
                activityLogs.add(logs.optString(i, ""));
            }
        } catch (Exception ignored) {}
    }

    private void saveGroups() {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < groupUrls.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("url", groupUrls.get(i));
                o.put("name", groupNames.get(i));
                arr.put(o);
            }
            prefs.edit().putString("groups", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void saveLogs() {
        try {
            JSONArray arr = new JSONArray();
            int start = Math.max(0, activityLogs.size() - 100);
            for (int i = start; i < activityLogs.size(); i++) {
                arr.put(activityLogs.get(i));
            }
            prefs.edit().putString("activity_logs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void addLog(String entry) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String full = "[" + ts + "] " + entry;
        activityLogs.add(full);
        lastLog = entry;
        saveLogs();
        notifyProgress();
    }

    /** SpinTax: {a|b|c} picks one option at random per occurrence. */
    public static String spinText(String input) {
        if (input == null || input.isEmpty()) return "";
        String result = input;
        Pattern p = Pattern.compile("\\{([^{}]+)\\}");
        Random rnd = new Random();
        for (int guard = 0; guard < 50; guard++) {
            Matcher m = p.matcher(result);
            if (!m.find()) break;
            String[] parts = m.group(1).split("\\|");
            String chosen = parts[rnd.nextInt(parts.length)].trim();
            result = result.substring(0, m.start()) + chosen + result.substring(m.end());
        }
        return result;
    }

    public static String extractCUser(String cookies) {
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            String p = part.trim();
            if (p.startsWith("c_user=")) {
                return p.substring("c_user=".length()).trim();
            }
        }
        return "";
    }

    /**
     * Pure local Smart Link Extractor.
     * Scans arbitrary pasted text and returns unique normalized Facebook group URLs.
     * Never contacts the network.
     */
    public static List<String> extractGroupLinks(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();

        Matcher m = GROUP_URL_PATTERN.matcher(raw);
        while (m.find()) {
            String id = m.group(1);
            if (id != null && !id.isEmpty()) {
                unique.add(normalizeGroupUrl(id));
            }
        }

        Matcher bare = BARE_ID_PATTERN.matcher(raw);
        while (bare.find()) {
            String id = bare.group(1);
            if (id != null && id.length() >= 10) {
                unique.add(normalizeGroupUrl(id));
            }
        }

        return new ArrayList<>(unique);
    }

    private static String normalizeGroupUrl(String idOrPath) {
        if (idOrPath == null) return "";
        String clean = idOrPath.trim();
        if (clean.startsWith("http")) return clean;
        if (clean.contains("facebook.com/groups/")) return clean.startsWith("http") ? clean : "https://" + clean;
        return "https://m.facebook.com/groups/" + clean;
    }

    public void logoutAccount() {
        prefs.edit()
                .remove("account_cookies")
                .remove("account_name")
                .remove("page_id")
                .remove("page_access_token")
                .remove("page_name")
                .apply();
        pageId = "";
        pageAccessToken = "";
        pageName = "";
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.flush();
        mediaUris.clear();
        addLog("تم تسجيل الخروج ومسح جلسة WebView وإعدادات الصفحة");
        Toast.makeText(this, R.string.toast_logout, Toast.LENGTH_SHORT).show();
        notifyProgress();
    }

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) f = new DashboardFragment();
            else if (id == R.id.nav_accounts) f = new AccountsFragment();
            else if (id == R.id.nav_groups) f = new GroupsFragment();
            else if (id == R.id.nav_campaign) f = new CampaignFragment();
            else f = new SettingsFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void openFacebookLogin() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        WebView loginWeb = new WebView(this);
        WebSettings ws = loginWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWeb, true);

        loginWeb.setWebViewClient(new WebViewClient());
        loginWeb.loadUrl("https://m.facebook.com/");

        MaterialButton doneBtn = new MaterialButton(this);
        doneBtn.setText(R.string.btn_done_login);
        doneBtn.setAllCaps(false);
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = android.view.Gravity.BOTTOM;
        btnLp.setMargins(24, 24, 24, 48);
        doneBtn.setLayoutParams(btnLp);

        root.addView(loginWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(doneBtn);
        dialog.setContentView(root);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        doneBtn.setOnClickListener(v -> {
            String cookies = CookieManager.getInstance().getCookie("https://m.facebook.com");
            if (cookies == null || cookies.trim().isEmpty()) {
                cookies = CookieManager.getInstance().getCookie("https://www.facebook.com");
            }
            if (cookies != null && !cookies.trim().isEmpty() && cookies.contains("c_user")) {
                prefs.edit().putString("account_cookies", cookies).apply();
                CookieManager cm = CookieManager.getInstance();
                for (String part : cookies.split(";")) {
                    String p = part.trim();
                    if (!p.isEmpty()) {
                        cm.setCookie("https://m.facebook.com", p);
                        cm.setCookie("https://www.facebook.com", p);
                    }
                }
                cm.flush();
                addLog("✓ تم تسجيل الدخول واستخراج الجلسة");
                Toast.makeText(this, R.string.toast_login_ok, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_login_empty, Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
            notifyProgress();
        });

        dialog.show();
    }

    public void savePageConnection(String id, String token) {
        pageId = id == null ? "" : id.trim();
        pageAccessToken = token == null ? "" : token.trim();
        pageName = "";
        prefs.edit()
                .putString("page_id", pageId)
                .putString("page_access_token", pageAccessToken)
                .remove("page_name")
                .apply();
        notifyProgress();
    }

    public boolean hasPageConnection() {
        return !pageId.isEmpty() && !pageAccessToken.isEmpty();
    }

    public String getPageConnectionLabel() {
        if (!hasPageConnection()) return getString(R.string.page_api_not_configured);
        if (!pageName.isEmpty()) return getString(R.string.page_api_connected, pageName);
        return getString(R.string.page_api_token_saved);
    }

    public interface PageVerificationCallback {
        void onComplete(boolean success, String message);
    }

    public void verifyPageConnection(PageVerificationCallback callback) {
        if (!hasPageConnection()) {
            if (callback != null) callback.onComplete(false, getString(R.string.page_api_missing_credentials));
            return;
        }
        networkExecutor.submit(() -> {
            FacebookPagesClient.PageResult result = FacebookPagesClient.verifyPage(pageId, pageAccessToken, GRAPH_API_VERSION);
            handler.post(() -> {
                if (result.success) {
                    pageName = result.name;
                    prefs.edit().putString("page_name", pageName).apply();
                    addLog("✓ تم التحقق من الصفحة عبر Meta API: " + pageName);
                } else {
                    addLog("✗ تعذر التحقق من الصفحة: " + result.message);
                }
                notifyProgress();
                if (callback != null) callback.onComplete(result.success, result.message);
            });
        });
    }

    /**
     * Publishes exactly one text post through Meta's Pages API and counts success
     * only when Meta returns a non-empty post ID. No DOM click is treated as success.
     */
    public void startPageCampaign(String text) {
        if (isRunning) {
            Toast.makeText(this, R.string.toast_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasPageConnection()) {
            Toast.makeText(this, R.string.page_api_missing_credentials, Toast.LENGTH_LONG).show();
            bottomNav.setSelectedItemId(R.id.nav_accounts);
            return;
        }
        if (!mediaUris.isEmpty()) {
            Toast.makeText(this, R.string.toast_media_not_supported, Toast.LENGTH_LONG).show();
            return;
        }

        postText = text.trim();
        prefs.edit().putString("post_text", postText).apply();
        isRunning = true;
        currentIndex = 0;
        addLog("بدء نشر فعلي عبر Meta Pages API…");
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        notifyProgress();

        final String message = spinText(postText);
        publishTask = networkExecutor.submit(() -> {
            FacebookPagesClient.PublishResult result = FacebookPagesClient.publishTextPost(
                    pageId, pageAccessToken, message, GRAPH_API_VERSION);
            handler.post(() -> finishPagePublish(result));
        });
    }

    private void finishPagePublish(FacebookPagesClient.PublishResult result) {
        if (!isRunning) return;
        isRunning = false;
        if (result.success) {
            postedCount++;
            prefs.edit().putInt("posted_count", postedCount).apply();
            addLog("✓ تم النشر الفعلي على " + (pageName.isEmpty() ? pageId : pageName)
                    + " — Post ID: " + result.postId);
            Toast.makeText(this, R.string.toast_real_publish_ok, Toast.LENGTH_LONG).show();
        } else {
            failedCount++;
            prefs.edit().putInt("failed_count", failedCount).apply();
            addLog("✗ لم يتم النشر عبر Meta API: " + result.message);
            Toast.makeText(this, getString(R.string.toast_real_publish_failed, result.message), Toast.LENGTH_LONG).show();
        }
        notifyProgress();
    }

    public void stopCampaign() {
        isRunning = false;
        if (publishTask != null) publishTask.cancel(true);
        addLog("تم الإيقاف بواسطة المستخدم");
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
        notifyProgress();
    }

    public void retryFailed() {
        if (isRunning) {
            Toast.makeText(this, "أوقف النشر أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (postText == null || postText.trim().isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show();
            return;
        }
        addLog("إعادة محاولة النشر الحقيقي عبر Meta API");
        Toast.makeText(this, R.string.toast_retry, Toast.LENGTH_SHORT).show();
        startPageCampaign(postText);
    }

    private void notifyProgress() {
        handler.post(() -> {
            for (Runnable r : new ArrayList<>(progressListeners)) {
                try { r.run(); } catch (Exception ignored) {}
            }
        });
    }

    public void addProgressListener(Runnable r) {
        if (r != null && !progressListeners.contains(r)) progressListeners.add(r);
    }

    public void removeProgressListener(Runnable r) {
        progressListeners.remove(r);
    }

    private int accountCount() {
        String n = prefs.getString("account_name", "");
        String c = prefs.getString("account_cookies", "");
        return (n.isEmpty() && c.isEmpty() && !hasPageConnection()) ? 0 : 1;
    }

    public String getActivityLogText() {
        if (activityLogs.isEmpty()) return getString(R.string.no_activity);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, activityLogs.size() - 30);
        for (int i = start; i < activityLogs.size(); i++) {
            sb.append(activityLogs.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    public static class GroupsAdapter extends RecyclerView.Adapter<GroupsAdapter.VH> {
        private final List<String> names;
        private final List<String> urls;
        private final OnLongClickListener longClickListener;

        public interface OnLongClickListener {
            void onLongClick(int position);
        }

        public GroupsAdapter(List<String> names, List<String> urls, OnLongClickListener listener) {
            this.names = names;
            this.urls = urls;
            this.longClickListener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            h.name.setText(names.get(position));
            h.url.setText(urls.get(position));
            h.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onLongClick(h.getAdapterPosition());
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return names.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView name, url;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.itemGroupName);
                url = v.findViewById(R.id.itemGroupUrl);
            }
        }
    }

    public static class DashboardFragment extends Fragment {
        private Runnable progressUpdater;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_dashboard, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextView statG = v.findViewById(R.id.statGroups);
            TextView statP = v.findViewById(R.id.statPosted);
            TextView statF = v.findViewById(R.id.statFailed);
            TextView statA = v.findViewById(R.id.statAccounts);
            TextView status = v.findViewById(R.id.statusText);
            ProgressBar prog = v.findViewById(R.id.campaignProgress);
            TextView log = v.findViewById(R.id.logPreview);

            progressUpdater = () -> {
                if (!isAdded()) return;
                statG.setText(String.valueOf(act.groupUrls.size()));
                statP.setText(String.valueOf(act.postedCount));
                statF.setText(String.valueOf(act.failedCount));
                statA.setText(String.valueOf(act.accountCount()));
                if (act.isRunning) {
                    status.setText(R.string.status_real_publish_running);
                    prog.setVisibility(View.VISIBLE);
                    prog.setProgress(50);
                } else {
                    status.setText(R.string.status_idle);
                    prog.setVisibility(View.GONE);
                }
                log.setText(act.getActivityLogText());
            };
            progressUpdater.run();
            act.addProgressListener(progressUpdater);
            v.findViewById(R.id.btnQuickStart).setOnClickListener(btn ->
                    act.bottomNav.setSelectedItemId(R.id.nav_campaign));
            return v;
        }

        @Override
        public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class AccountsFragment extends Fragment {
        private Runnable progressUpdater;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_accounts, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText name = v.findViewById(R.id.inputAccountName);
            TextInputEditText cookies = v.findViewById(R.id.inputCookies);
            TextInputEditText pageIdInput = v.findViewById(R.id.inputPageId);
            TextInputEditText pageTokenInput = v.findViewById(R.id.inputPageToken);
            TextView pageApiStatus = v.findViewById(R.id.pageApiStatus);
            TextView status = v.findViewById(R.id.accountStatus);
            TextView session = v.findViewById(R.id.sessionIndicator);
            TextView profileId = v.findViewById(R.id.profileIdText);
            TextView stG = v.findViewById(R.id.profileStatGroups);
            TextView stP = v.findViewById(R.id.profileStatPosted);
            TextView stF = v.findViewById(R.id.profileStatFailed);

            pageIdInput.setText(act.pageId);
            pageTokenInput.setText(act.pageAccessToken);
            progressUpdater = () -> {
                if (!isAdded()) return;
                name.setText(act.prefs.getString("account_name", ""));
                cookies.setText(act.prefs.getString("account_cookies", ""));
                pageApiStatus.setText(act.getPageConnectionLabel());
                String saved = act.prefs.getString("account_name", "");
                String cSaved = act.prefs.getString("account_cookies", "");
                status.setText(saved.isEmpty() ? getString(R.string.account_none) : getString(R.string.account_saved, saved));
                if (!cSaved.isEmpty() && cSaved.contains("c_user")) {
                    session.setText(R.string.session_active);
                    session.setTextColor(0xFF22C55E);
                    String uid = extractCUser(cSaved);
                    profileId.setText(uid.isEmpty() ? getString(R.string.profile_id_none) : getString(R.string.profile_id, uid));
                } else {
                    session.setText(R.string.session_inactive);
                    session.setTextColor(0xFFF59E0B);
                    profileId.setText(R.string.profile_id_none);
                }
                stG.setText(getString(R.string.profile_groups, act.groupUrls.size()));
                stP.setText(getString(R.string.profile_posted, act.postedCount));
                stF.setText(getString(R.string.profile_failed, act.failedCount));
            };
            progressUpdater.run();
            act.addProgressListener(progressUpdater);

            v.findViewById(R.id.btnSaveAccount).setOnClickListener(btn -> {
                String n = name.getText() != null ? name.getText().toString().trim() : "";
                String c = cookies.getText() != null ? cookies.getText().toString().trim() : "";
                String pId = pageIdInput.getText() != null ? pageIdInput.getText().toString().trim() : "";
                String pToken = pageTokenInput.getText() != null ? pageTokenInput.getText().toString().trim() : "";
                act.prefs.edit().putString("account_name", n).putString("account_cookies", c).apply();
                act.savePageConnection(pId, pToken);
                if (!c.isEmpty()) {
                    CookieManager cm = CookieManager.getInstance();
                    for (String part : c.split(";")) {
                        String p = part.trim();
                        if (!p.isEmpty()) {
                            cm.setCookie("https://m.facebook.com", p);
                            cm.setCookie("https://www.facebook.com", p);
                        }
                    }
                    cm.flush();
                }
                progressUpdater.run();
                Toast.makeText(act, R.string.btn_save_account, Toast.LENGTH_SHORT).show();
            });

            v.findViewById(R.id.btnLoginFb).setOnClickListener(btn -> act.openFacebookLogin());
            v.findViewById(R.id.btnVerifyPage).setOnClickListener(btn -> {
                String pId = pageIdInput.getText() != null ? pageIdInput.getText().toString().trim() : "";
                String pToken = pageTokenInput.getText() != null ? pageTokenInput.getText().toString().trim() : "";
                act.savePageConnection(pId, pToken);
                pageApiStatus.setText(R.string.page_api_verifying);
                act.verifyPageConnection((success, message) -> {
                    if (!isAdded()) return;
                    pageApiStatus.setText(success ? act.getPageConnectionLabel() : act.getString(R.string.page_api_error, message));
                    pageApiStatus.setTextColor(success ? 0xFF22C55E : 0xFFF59E0B);
                });
            });
            v.findViewById(R.id.btnLogout).setOnClickListener(btn -> act.logoutAccount());
            return v;
        }

        @Override
        public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class GroupsFragment extends Fragment {
        private GroupsAdapter adapter;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_groups, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText urlIn = v.findViewById(R.id.inputGroupUrl);
            TextInputEditText nameIn = v.findViewById(R.id.inputGroupName);
            TextInputEditText importIn = v.findViewById(R.id.inputImportList);
            RecyclerView recycler = v.findViewById(R.id.recyclerGroups);
            TextView count = v.findViewById(R.id.groupsCount);

            recycler.setLayoutManager(new LinearLayoutManager(act));
            adapter = new GroupsAdapter(act.groupNames, act.groupUrls, position -> {
                if (position >= 0 && position < act.groupUrls.size()) {
                    act.groupUrls.remove(position);
                    act.groupNames.remove(position);
                    act.saveGroups();
                    adapter.notifyItemRemoved(position);
                    count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                }
            });
            recycler.setAdapter(adapter);
            count.setText(getString(R.string.groups_count, act.groupUrls.size()));

            v.findViewById(R.id.btnSmartExtractor).setOnClickListener(btn ->
                    showSmartExtractorDialog(act, adapter, count));

            v.findViewById(R.id.btnAddGroup).setOnClickListener(btn -> {
                String u = urlIn.getText() != null ? urlIn.getText().toString().trim() : "";
                String n = nameIn.getText() != null ? nameIn.getText().toString().trim() : "";
                if (u.isEmpty()) {
                    Toast.makeText(act, R.string.toast_enter_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (n.isEmpty()) n = u;
                act.groupUrls.add(u);
                act.groupNames.add(n);
                act.saveGroups();
                adapter.notifyItemInserted(act.groupUrls.size() - 1);
                count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                urlIn.setText("");
                nameIn.setText("");
            });

            v.findViewById(R.id.btnImportGroups).setOnClickListener(btn -> {
                String raw = importIn.getText() != null ? importIn.getText().toString() : "";
                if (raw.trim().isEmpty()) {
                    Toast.makeText(act, R.string.toast_enter_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                List<String> found = extractGroupLinks(raw);
                if (found.isEmpty()) {
                    String[] lines = raw.split("\n");
                    for (String line : lines) {
                        String u = line.trim();
                        if (!u.isEmpty()) found.add(u);
                    }
                }
                int start = act.groupUrls.size();
                int added = 0;
                Set<String> existing = new LinkedHashSet<>(act.groupUrls);
                for (String u : found) {
                    if (existing.contains(u)) continue;
                    act.groupUrls.add(u);
                    act.groupNames.add(u);
                    existing.add(u);
                    added++;
                }
                act.saveGroups();
                if (added > 0) adapter.notifyItemRangeInserted(start, added);
                count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                importIn.setText("");
                Toast.makeText(act, getString(R.string.extract_added, added), Toast.LENGTH_SHORT).show();
            });

            v.findViewById(R.id.btnExportGroups).setOnClickListener(btn -> {
                if (act.groupUrls.isEmpty()) {
                    Toast.makeText(act, "لا توجد مجموعات للتصدير", Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (String u : act.groupUrls) sb.append(u).append("\n");
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("groups", sb.toString().trim()));
                    Toast.makeText(act, R.string.toast_exported, Toast.LENGTH_SHORT).show();
                }
            });

            v.findViewById(R.id.btnClearGroups).setOnClickListener(btn -> {
                int size = act.groupUrls.size();
                act.groupUrls.clear();
                act.groupNames.clear();
                act.saveGroups();
                adapter.notifyItemRangeRemoved(0, size);
                count.setText(getString(R.string.groups_count, 0));
                Toast.makeText(act, R.string.toast_groups_cleared, Toast.LENGTH_SHORT).show();
            });

            return v;
        }

        private void showSmartExtractorDialog(MainActivity act, GroupsAdapter adapter, TextView countView) {
            Dialog dialog = new Dialog(act);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(32, 32, 32, 32);
            root.setBackgroundColor(0xFF121212);

            TextView title = new TextView(act);
            title.setText(R.string.smart_extractor_title);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(20);
            title.setPadding(0, 0, 0, 16);
            root.addView(title);

            TextView hint = new TextView(act);
            hint.setText(R.string.smart_extractor_hint);
            hint.setTextColor(0xFFAAAAAA);
            hint.setTextSize(13);
            hint.setPadding(0, 0, 0, 16);
            root.addView(hint);

            TextInputLayout til = new TextInputLayout(act);
            til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            TextInputEditText pasteBox = new TextInputEditText(act);
            pasteBox.setHint("الصق النص هنا…");
            pasteBox.setMinLines(6);
            pasteBox.setMaxLines(12);
            pasteBox.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
            pasteBox.setTextColor(0xFFFFFFFF);
            pasteBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            til.addView(pasteBox);
            root.addView(til);

            TextView resultLabel = new TextView(act);
            resultLabel.setTextColor(0xFF22C55E);
            resultLabel.setTextSize(14);
            resultLabel.setPadding(0, 16, 0, 8);
            resultLabel.setVisibility(View.GONE);
            root.addView(resultLabel);

            ScrollView previewScroll = new ScrollView(act);
            TextView preview = new TextView(act);
            preview.setTextColor(0xFFCCCCCC);
            preview.setTextSize(12);
            preview.setPadding(8, 8, 8, 8);
            previewScroll.addView(preview);
            previewScroll.setVisibility(View.GONE);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            scrollLp.height = 240;
            root.addView(previewScroll, scrollLp);

            final List<String>[] extracted = new List[]{new ArrayList<>()};

            MaterialButton extractBtn = new MaterialButton(act);
            extractBtn.setText(R.string.btn_extract);
            extractBtn.setAllCaps(false);
            extractBtn.setOnClickListener(v -> {
                String raw = pasteBox.getText() != null ? pasteBox.getText().toString() : "";
                List<String> found = extractGroupLinks(raw);
                extracted[0] = found;
                if (found.isEmpty()) {
                    resultLabel.setText(R.string.extract_none);
                    resultLabel.setTextColor(0xFFF59E0B);
                    resultLabel.setVisibility(View.VISIBLE);
                    previewScroll.setVisibility(View.GONE);
                } else {
                    resultLabel.setText(act.getString(R.string.extract_found, found.size()));
                    resultLabel.setTextColor(0xFF22C55E);
                    resultLabel.setVisibility(View.VISIBLE);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(found.size(), 40); i++) {
                        sb.append("• ").append(found.get(i)).append("\n");
                    }
                    if (found.size() > 40) sb.append("… و ").append(found.size() - 40).append(" أخرى");
                    preview.setText(sb.toString().trim());
                    previewScroll.setVisibility(View.VISIBLE);
                }
            });
            root.addView(extractBtn);

            MaterialButton addBtn = new MaterialButton(act);
            addBtn.setText(R.string.btn_add_to_list);
            addBtn.setAllCaps(false);
            addBtn.setOnClickListener(v -> {
                List<String> found = extracted[0];
                if (found == null || found.isEmpty()) {
                    Toast.makeText(act, R.string.extract_none, Toast.LENGTH_SHORT).show();
                    return;
                }
                int start = act.groupUrls.size();
                int added = 0;
                Set<String> existing = new LinkedHashSet<>(act.groupUrls);
                for (String u : found) {
                    if (existing.contains(u)) continue;
                    act.groupUrls.add(u);
                    act.groupNames.add(u);
                    existing.add(u);
                    added++;
                }
                act.saveGroups();
                if (added > 0) adapter.notifyItemRangeInserted(start, added);
                countView.setText(act.getString(R.string.groups_count, act.groupUrls.size()));
                Toast.makeText(act, act.getString(R.string.extract_added, added), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            root.addView(addBtn);

            dialog.setContentView(root);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            dialog.show();
        }
    }

    public static class CampaignFragment extends Fragment {
        private Runnable progressUpdater;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_campaign, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText textIn = v.findViewById(R.id.inputPostText);
            TextInputEditText minIn = v.findViewById(R.id.inputMinDelay);
            TextInputEditText maxIn = v.findViewById(R.id.inputMaxDelay);
            TextView info = v.findViewById(R.id.campaignInfo);
            TextView log = v.findViewById(R.id.campaignLog);
            MaterialButton startBtn = v.findViewById(R.id.btnStartCampaign);
            MaterialButton stopBtn = v.findViewById(R.id.btnStopCampaign);
            MaterialButton retryBtn = v.findViewById(R.id.btnRetryFailed);
            TextView mediaCount = v.findViewById(R.id.mediaCountText);
            TextView spintaxHelp = v.findViewById(R.id.spintaxHelp);

            textIn.setText(act.postText);
            minIn.setText(String.valueOf(Math.max(5, act.minDelaySec)));
            maxIn.setText(String.valueOf(Math.max(5, act.maxDelaySec)));
            mediaCount.setText(getString(R.string.media_count, act.mediaUris.size()));

            ActivityResultLauncher<String[]> pickMedia = registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null) return;
                        for (Uri u : uris) {
                            try {
                                requireContext().getContentResolver().takePersistableUriPermission(
                                        u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception ignored) {}
                            if (!act.mediaUris.contains(u)) act.mediaUris.add(u);
                        }
                        mediaCount.setText(getString(R.string.media_count, act.mediaUris.size()));
                    });

            v.findViewById(R.id.btnPickMedia).setOnClickListener(btn ->
                    pickMedia.launch(new String[]{"image/*", "video/*"}));
            v.findViewById(R.id.btnClearMedia).setOnClickListener(btn -> {
                act.mediaUris.clear();
                mediaCount.setText(getString(R.string.media_count, 0));
            });
            v.findViewById(R.id.btnSpintaxInfo).setOnClickListener(btn -> {
                spintaxHelp.setVisibility(spintaxHelp.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            });

            progressUpdater = () -> {
                if (!isAdded()) return;
                if (act.hasPageConnection()) {
                    info.setText(act.getPageConnectionLabel());
                } else {
                    info.setText(R.string.campaign_page_not_ready);
                }
                log.setText(act.getActivityLogText());
                startBtn.setVisibility(act.isRunning ? View.GONE : View.VISIBLE);
                stopBtn.setVisibility(act.isRunning ? View.VISIBLE : View.GONE);
                retryBtn.setVisibility(act.isRunning ? View.GONE : View.VISIBLE);
            };
            progressUpdater.run();
            act.addProgressListener(progressUpdater);

            startBtn.setOnClickListener(btn -> {
                String t = textIn.getText() != null ? textIn.getText().toString() : "";
                int minD = 5, maxD = 10;
                try { minD = Integer.parseInt(minIn.getText().toString()); } catch (Exception ignored) {}
                try { maxD = Integer.parseInt(maxIn.getText().toString()); } catch (Exception ignored) {}
                act.startPageCampaign(t);
            });

            stopBtn.setOnClickListener(btn -> act.stopCampaign());
            retryBtn.setOnClickListener(btn -> act.retryFailed());
            return v;
        }

        @Override
        public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class SettingsFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_settings, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText delayIn = v.findViewById(R.id.settingsDefaultDelay);
            delayIn.setText(String.valueOf(act.minDelaySec));

            v.findViewById(R.id.btnSaveSettings).setOnClickListener(btn -> {
                try {
                    int d = Integer.parseInt(delayIn.getText().toString());
                    act.minDelaySec = Math.max(5, d);
                    act.maxDelaySec = act.minDelaySec + 5;
                    act.prefs.edit()
                            .putInt("min_delay", act.minDelaySec)
                            .putInt("max_delay", act.maxDelaySec)
                            .apply();
                    Toast.makeText(act, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(act, "رقم غير صالح", Toast.LENGTH_SHORT).show();
                }
            });

            v.findViewById(R.id.btnClearData).setOnClickListener(btn -> {
                act.prefs.edit().clear().apply();
                act.groupUrls.clear();
                act.groupNames.clear();
                act.activityLogs.clear();
                act.mediaUris.clear();
                act.postText = "";
                act.pageId = "";
                act.pageAccessToken = "";
                act.pageName = "";
                act.postedCount = 0;
                act.failedCount = 0;
                act.successStreak = 0;
                act.lastLog = "";
                act.notifyProgress();
                Toast.makeText(act, R.string.toast_data_cleared, Toast.LENGTH_SHORT).show();
            });
            return v;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (publishTask != null) publishTask.cancel(true);
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}

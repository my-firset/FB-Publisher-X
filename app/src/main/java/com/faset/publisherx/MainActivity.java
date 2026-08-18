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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

/** Publisher X v2.1 — Multi-Page Meta Pages API with text/images/video. */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "publisherx_prefs";
    private static final String GRAPH_API_VERSION = "v26.0";
    private static final Pattern GROUP_URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.|mobile\\.)?facebook\\.com/groups/([a-zA-Z0-9._-]+)/?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_ID_PATTERN = Pattern.compile(
            "(?<![a-zA-Z0-9./_-])(\\d{10,20})(?![a-zA-Z0-9])");

    private String lastLog = "";
    private String pageAccessToken = "";
    private final List<PageItem> pages = new ArrayList<>();
    private MultiPageCampaign multiPageCampaign;
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
        pageAccessToken = prefs.getString("page_access_token", "");
        pages.clear();
        try {
            JSONArray pagesArr = new JSONArray(prefs.getString("pages_json", "[]"));
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject o = pagesArr.getJSONObject(i);
                String id = o.optString("id", "");
                String name = o.optString("name", id);
                if (!id.isEmpty()) pages.add(new PageItem(id, name));
            }
        } catch (Exception ignored) {}
        String legacyId = prefs.getString("page_id", "");
        String legacyName = prefs.getString("page_name", "");
        if (pages.isEmpty() && !legacyId.isEmpty()) {
            pages.add(new PageItem(legacyId, legacyName.isEmpty() ? legacyId : legacyName));
            savePagesList();
        }
        multiPageCampaign = new MultiPageCampaign();
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
            for (int i = 0; i < logs.length(); i++) activityLogs.add(logs.optString(i, ""));
        } catch (Exception ignored) {}
    }

    private void savePagesList() {
        try {
            JSONArray arr = new JSONArray();
            for (PageItem p : pages) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                arr.put(o);
            }
            prefs.edit().putString("pages_json", arr.toString()).apply();
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
            for (int i = start; i < activityLogs.size(); i++) arr.put(activityLogs.get(i));
            prefs.edit().putString("activity_logs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void addLog(String entry) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        activityLogs.add("[" + ts + "] " + entry);
        lastLog = entry;
        saveLogs();
        notifyProgress();
    }

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
            if (p.startsWith("c_user=")) return p.substring("c_user=".length()).trim();
        }
        return "";
    }

    public static List<String> extractGroupLinks(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        Matcher m = GROUP_URL_PATTERN.matcher(raw);
        while (m.find()) {
            String id = m.group(1);
            if (id != null && !id.isEmpty()) unique.add(normalizeGroupUrl(id));
        }
        Matcher bare = BARE_ID_PATTERN.matcher(raw);
        while (bare.find()) {
            String id = bare.group(1);
            if (id != null && id.length() >= 10) unique.add(normalizeGroupUrl(id));
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

    public void savePageToken(String token) {
        pageAccessToken = token == null ? "" : token.trim();
        prefs.edit().putString("page_access_token", pageAccessToken).apply();
        notifyProgress();
    }

    public boolean addPage(String id, String name) {
        if (id == null || id.trim().isEmpty()) return false;
        String cleanId = id.trim();
        for (PageItem p : pages) if (p.id.equals(cleanId)) return false;
        pages.add(new PageItem(cleanId, name));
        savePagesList();
        notifyProgress();
        return true;
    }

    public void removePage(int index) {
        if (index >= 0 && index < pages.size()) {
            pages.remove(index);
            savePagesList();
            notifyProgress();
        }
    }

    public boolean hasPageConnection() {
        return !pageAccessToken.isEmpty() && !pages.isEmpty();
    }

    public String getPageConnectionLabel() {
        if (pageAccessToken.isEmpty()) return getString(R.string.page_api_not_configured);
        if (pages.isEmpty()) return getString(R.string.page_api_token_saved);
        if (pages.size() == 1) return getString(R.string.page_api_connected, pages.get(0).name);
        return getString(R.string.pages_count, pages.size()) + " • توكن محفوظ";
    }

    public void logoutAccount() {
        prefs.edit().remove("account_cookies").remove("account_name").remove("page_id")
                .remove("page_access_token").remove("page_name").remove("pages_json").apply();
        pageAccessToken = "";
        pages.clear();
        if (multiPageCampaign != null) multiPageCampaign.stop();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        mediaUris.clear();
        addLog("تم تسجيل الخروج ومسح التوكن والصفحات");
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
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, f).commit();
            return true;
        });
    }

    public void openFacebookLogin() {
        Toast.makeText(this, "استخدم Page Access Token من تبويب الحساب", Toast.LENGTH_LONG).show();
    }

    public void openGroupForManualPosting(String groupUrl) {
        if (groupUrl == null || groupUrl.trim().isEmpty()) return;
        if (postText != null && !postText.trim().isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("campaign_text", postText));
        }
        Toast.makeText(this, R.string.group_manual_share_hint, Toast.LENGTH_LONG).show();
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(groupUrl.trim())));
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    public interface PageVerificationCallback {
        void onComplete(boolean success, String message);
    }

    public void verifyPageConnection(String pageId, PageVerificationCallback callback) {
        if (pageAccessToken.isEmpty() || pageId == null || pageId.trim().isEmpty()) {
            if (callback != null) callback.onComplete(false, getString(R.string.page_api_missing_credentials));
            return;
        }
        final String id = pageId.trim();
        networkExecutor.submit(() -> {
            FacebookPagesClient.PageResult result = FacebookPagesClient.verifyPage(id, pageAccessToken, GRAPH_API_VERSION);
            handler.post(() -> {
                if (result.success) {
                    addLog("✓ تم التحقق من الصفحة عبر Meta API: " + result.name);
                    for (int i = 0; i < pages.size(); i++) {
                        if (pages.get(i).id.equals(id)) {
                            pages.set(i, new PageItem(id, result.name));
                            savePagesList();
                            break;
                        }
                    }
                } else {
                    addLog("✗ تعذر التحقق من الصفحة: " + result.message);
                }
                notifyProgress();
                if (callback != null) callback.onComplete(result.success, result.message);
            });
        });
    }

    public void startPageCampaign(String text) {
        startPageCampaign(text, mediaUris);
    }

    public void startPageCampaign(String text, List<Uri> selectedMedia) {
        if (isRunning || (multiPageCampaign != null && multiPageCampaign.isRunning())) {
            Toast.makeText(this, R.string.toast_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        List<Uri> mediaSnapshot = selectedMedia == null ? new ArrayList<>() : new ArrayList<>(selectedMedia);
        String trimmedText = text == null ? "" : text.trim();
        if (trimmedText.isEmpty() && mediaSnapshot.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasPageConnection()) {
            Toast.makeText(this, R.string.page_api_missing_credentials, Toast.LENGTH_LONG).show();
            bottomNav.setSelectedItemId(R.id.nav_accounts);
            return;
        }
        postText = trimmedText;
        prefs.edit().putString("post_text", postText).apply();
        isRunning = true;
        currentIndex = 0;
        successStreak = 0;
        String mode = mediaSnapshot.isEmpty() ? "نصي" : "متعدد الوسائط";
        addLog("بدء حملة " + mode + " على " + pages.size() + " صفحة…");
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        notifyProgress();
        if (multiPageCampaign == null) multiPageCampaign = new MultiPageCampaign();
        multiPageCampaign.start(pages, pageAccessToken, postText, mediaSnapshot,
                getContentResolver(), GRAPH_API_VERSION, minDelaySec, maxDelaySec, handler,
                new MultiPageCampaign.Listener() {
                    @Override public void onLog(String message) { addLog(message); }
                    @Override public void onPageResult(int index, int total, PageItem page, boolean success, String detail) {
                        if (success) { postedCount++; prefs.edit().putInt("posted_count", postedCount).apply(); }
                        else { failedCount++; prefs.edit().putInt("failed_count", failedCount).apply(); }
                        currentIndex = index;
                        notifyProgress();
                    }
                    @Override public void onFinished(int posted, int failed) {
                        isRunning = false;
                        currentIndex = -1;
                        Toast.makeText(MainActivity.this, R.string.toast_finished, Toast.LENGTH_LONG).show();
                        notifyProgress();
                    }
                });
    }

    public void stopCampaign() {
        isRunning = false;
        if (multiPageCampaign != null) multiPageCampaign.stop();
        if (publishTask != null) publishTask.cancel(true);
        addLog("تم الإيقاف بواسطة المستخدم");
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
        notifyProgress();
    }

    public void retryFailed() {
        if (isRunning) { Toast.makeText(this, "أوقف النشر أولاً", Toast.LENGTH_SHORT).show(); return; }
        if ((postText == null || postText.trim().isEmpty()) && mediaUris.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_text, Toast.LENGTH_SHORT).show(); return;
        }
        addLog("إعادة محاولة النشر الحقيقي عبر Meta API");
        Toast.makeText(this, R.string.toast_retry, Toast.LENGTH_SHORT).show();
        startPageCampaign(postText, mediaUris);
    }

    private void notifyProgress() {
        handler.post(() -> {
            for (Runnable r : new ArrayList<>(progressListeners)) {
                try { r.run(); } catch (Exception ignored) {}
            }
        });
    }

    private void renderMediaPreview(TextView mediaCount, TextView previewTitle, LinearLayout previewList) {
        if (mediaCount == null || previewTitle == null || previewList == null) return;
        mediaCount.setText(getString(R.string.media_count, mediaUris.size()));
        previewList.removeAllViews();
        if (mediaUris.isEmpty()) { previewTitle.setText(R.string.media_preview_empty); return; }
        previewTitle.setText(getString(R.string.media_preview_title) + " (" + mediaUris.size() + ")");
        float density = getResources().getDisplayMetrics().density;
        int thumbSize = (int) (72 * density);
        for (Uri uri : mediaUris) {
            String mime = getContentResolver().getType(uri);
            boolean video = mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 6, 0, 6);
            if (video) {
                TextView videoBadge = new TextView(this);
                videoBadge.setText("▶");
                videoBadge.setTextColor(0xFFFFFFFF);
                videoBadge.setTextSize(20);
                videoBadge.setGravity(android.view.Gravity.CENTER);
                videoBadge.setBackgroundColor(0xFF334155);
                row.addView(videoBadge, new LinearLayout.LayoutParams(thumbSize, thumbSize));
            } else {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(0xFF334155);
                image.setImageURI(uri);
                row.addView(image, new LinearLayout.LayoutParams(thumbSize, thumbSize));
            }
            TextView label = new TextView(this);
            label.setText(video ? getString(R.string.media_item_video, mediaDisplayName(uri))
                    : getString(R.string.media_item_image, mediaDisplayName(uri)));
            label.setTextColor(0xFFCBD5E1);
            label.setTextSize(13);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMargins((int) (10 * density), 0, 0, 0);
            row.addView(label, labelLp);
            previewList.addView(row);
        }
    }

    private String mediaDisplayName(Uri uri) {
        if (uri == null) return "media";
        String value = uri.getLastPathSegment();
        return (value == null || value.trim().isEmpty()) ? uri.toString() : value;
    }

    public void addProgressListener(Runnable r) {
        if (r != null && !progressListeners.contains(r)) progressListeners.add(r);
    }

    public void removeProgressListener(Runnable r) { progressListeners.remove(r); }

    private int accountCount() { return hasPageConnection() ? pages.size() : 0; }

    public String getActivityLogText() {
        if (activityLogs.isEmpty()) return getString(R.string.no_activity);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, activityLogs.size() - 30);
        for (int i = start; i < activityLogs.size(); i++) sb.append(activityLogs.get(i)).append("\n");
        return sb.toString().trim();
    }

    public static class PagesAdapter extends RecyclerView.Adapter<PagesAdapter.VH> {
        private final List<PageItem> items;
        private final OnDeleteListener deleteListener;
        public interface OnDeleteListener { void onDelete(int position); }
        public PagesAdapter(List<PageItem> items, OnDeleteListener deleteListener) {
            this.items = items; this.deleteListener = deleteListener;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_page, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            PageItem p = items.get(position);
            h.name.setText(p.name);
            h.id.setText("ID: " + p.id);
            h.deleteBtn.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (deleteListener != null && pos != RecyclerView.NO_POSITION) deleteListener.onDelete(pos);
            });
        }
        @Override public int getItemCount() { return items.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final TextView name, id; final MaterialButton deleteBtn;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.itemPageName);
                id = v.findViewById(R.id.itemPageId);
                deleteBtn = v.findViewById(R.id.btnDeletePage);
            }
        }
    }

    public static class GroupsAdapter extends RecyclerView.Adapter<GroupsAdapter.VH> {
        private final List<String> names, urls;
        private final OnLongClickListener longClickListener;
        private final OnOpenListener openListener;
        public interface OnLongClickListener { void onLongClick(int position); }
        public interface OnOpenListener { void onOpen(int position); }
        public GroupsAdapter(List<String> names, List<String> urls, OnLongClickListener listener, OnOpenListener openListener) {
            this.names = names; this.urls = urls; this.longClickListener = listener; this.openListener = openListener;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            h.name.setText(names.get(position));
            h.url.setText(urls.get(position));
            h.openButton.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (openListener != null && pos != RecyclerView.NO_POSITION) openListener.onOpen(pos);
            });
            h.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) longClickListener.onLongClick(h.getAdapterPosition());
                return true;
            });
        }
        @Override public int getItemCount() { return names.size(); }
        static class VH extends RecyclerView.ViewHolder {
            final TextView name, url; final MaterialButton openButton;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.itemGroupName);
                url = v.findViewById(R.id.itemGroupUrl);
                openButton = v.findViewById(R.id.btnOpenGroupManual);
            }
        }
    }

    public static class DashboardFragment extends Fragment {
        private Runnable progressUpdater;
        @Nullable @Override
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
            v.findViewById(R.id.btnQuickStart).setOnClickListener(btn -> act.bottomNav.setSelectedItemId(R.id.nav_campaign));
            return v;
        }
        @Override public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class AccountsFragment extends Fragment {
        private Runnable progressUpdater;
        private PagesAdapter pagesAdapter;
        @Nullable @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_accounts, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText pageTokenInput = v.findViewById(R.id.inputPageToken);
            TextInputEditText pageIdInput = v.findViewById(R.id.inputPageId);
            TextInputEditText pageNameInput = v.findViewById(R.id.inputPageName);
            TextView pageApiStatus = v.findViewById(R.id.pageApiStatus);
            TextView pagesCount = v.findViewById(R.id.pagesCount);
            RecyclerView recyclerPages = v.findViewById(R.id.recyclerPages);
            pageTokenInput.setText(act.pageAccessToken);
            recyclerPages.setLayoutManager(new LinearLayoutManager(act));
            pagesAdapter = new PagesAdapter(act.pages, position -> {
                act.removePage(position);
                pagesAdapter.notifyItemRemoved(position);
                pagesCount.setText(getString(R.string.pages_count, act.pages.size()));
                pageApiStatus.setText(act.getPageConnectionLabel());
                Toast.makeText(act, R.string.toast_page_removed, Toast.LENGTH_SHORT).show();
            });
            recyclerPages.setAdapter(pagesAdapter);
            progressUpdater = () -> {
                if (!isAdded()) return;
                pagesCount.setText(getString(R.string.pages_count, act.pages.size()));
                pageApiStatus.setText(act.getPageConnectionLabel());
                pageApiStatus.setTextColor(act.hasPageConnection() ? 0xFF22C55E : 0xFFF59E0B);
                pagesAdapter.notifyDataSetChanged();
            };
            progressUpdater.run();
            act.addProgressListener(progressUpdater);
            v.findViewById(R.id.btnSaveToken).setOnClickListener(btn -> {
                String token = pageTokenInput.getText() != null ? pageTokenInput.getText().toString().trim() : "";
                act.savePageToken(token);
                pageApiStatus.setText(act.getPageConnectionLabel());
                Toast.makeText(act, R.string.toast_token_saved, Toast.LENGTH_SHORT).show();
            });
            v.findViewById(R.id.btnAddPage).setOnClickListener(btn -> {
                String id = pageIdInput.getText() != null ? pageIdInput.getText().toString().trim() : "";
                String name = pageNameInput.getText() != null ? pageNameInput.getText().toString().trim() : "";
                if (id.isEmpty()) { Toast.makeText(act, R.string.toast_enter_page_id, Toast.LENGTH_SHORT).show(); return; }
                if (act.addPage(id, name)) {
                    pagesAdapter.notifyItemInserted(act.pages.size() - 1);
                    pagesCount.setText(getString(R.string.pages_count, act.pages.size()));
                    pageIdInput.setText(""); pageNameInput.setText("");
                    pageApiStatus.setText(act.getPageConnectionLabel());
                    Toast.makeText(act, R.string.toast_page_added, Toast.LENGTH_SHORT).show();
                } else Toast.makeText(act, R.string.toast_page_exists, Toast.LENGTH_SHORT).show();
            });
            v.findViewById(R.id.btnVerifyPage).setOnClickListener(btn -> {
                String id = pageIdInput.getText() != null ? pageIdInput.getText().toString().trim() : "";
                if (id.isEmpty() && !act.pages.isEmpty()) id = act.pages.get(act.pages.size() - 1).id;
                if (act.pageAccessToken.isEmpty()) { Toast.makeText(act, R.string.page_api_missing_credentials, Toast.LENGTH_SHORT).show(); return; }
                if (id.isEmpty()) { Toast.makeText(act, R.string.toast_enter_page_id, Toast.LENGTH_SHORT).show(); return; }
                pageApiStatus.setText(R.string.page_api_verifying);
                act.verifyPageConnection(id, (success, message) -> {
                    if (!isAdded()) return;
                    pageApiStatus.setText(success ? act.getString(R.string.page_api_connected, message)
                            : act.getString(R.string.page_api_error, message));
                    pageApiStatus.setTextColor(success ? 0xFF22C55E : 0xFFF59E0B);
                    pagesAdapter.notifyDataSetChanged();
                });
            });
            v.findViewById(R.id.btnLogout).setOnClickListener(btn -> {
                act.logoutAccount();
                pageTokenInput.setText("");
                pagesAdapter.notifyDataSetChanged();
                pagesCount.setText(getString(R.string.pages_count, 0));
            });
            return v;
        }
        @Override public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class GroupsFragment extends Fragment {
        private GroupsAdapter adapter;
        @Nullable @Override
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
                    act.groupUrls.remove(position); act.groupNames.remove(position);
                    act.saveGroups(); adapter.notifyItemRemoved(position);
                    count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                }
            }, position -> {
                if (position >= 0 && position < act.groupUrls.size())
                    act.openGroupForManualPosting(act.groupUrls.get(position));
            });
            recycler.setAdapter(adapter);
            count.setText(getString(R.string.groups_count, act.groupUrls.size()));
            v.findViewById(R.id.btnAddGroup).setOnClickListener(btn -> {
                String u = urlIn.getText() != null ? urlIn.getText().toString().trim() : "";
                String n = nameIn.getText() != null ? nameIn.getText().toString().trim() : "";
                if (u.isEmpty()) { Toast.makeText(act, R.string.toast_enter_url, Toast.LENGTH_SHORT).show(); return; }
                if (n.isEmpty()) n = u;
                act.groupUrls.add(u); act.groupNames.add(n); act.saveGroups();
                adapter.notifyItemInserted(act.groupUrls.size() - 1);
                count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                urlIn.setText(""); nameIn.setText("");
            });
            v.findViewById(R.id.btnImportGroups).setOnClickListener(btn -> {
                String raw = importIn.getText() != null ? importIn.getText().toString() : "";
                if (raw.trim().isEmpty()) { Toast.makeText(act, R.string.toast_enter_url, Toast.LENGTH_SHORT).show(); return; }
                List<String> found = extractGroupLinks(raw);
                if (found.isEmpty()) for (String line : raw.split("\n")) { String u = line.trim(); if (!u.isEmpty()) found.add(u); }
                int start = act.groupUrls.size(); int added = 0;
                Set<String> existing = new LinkedHashSet<>(act.groupUrls);
                for (String u : found) {
                    if (existing.contains(u)) continue;
                    act.groupUrls.add(u); act.groupNames.add(u); existing.add(u); added++;
                }
                act.saveGroups();
                if (added > 0) adapter.notifyItemRangeInserted(start, added);
                count.setText(getString(R.string.groups_count, act.groupUrls.size()));
                importIn.setText("");
                Toast.makeText(act, getString(R.string.extract_added, added), Toast.LENGTH_SHORT).show();
            });
            v.findViewById(R.id.btnExportGroups).setOnClickListener(btn -> {
                if (act.groupUrls.isEmpty()) { Toast.makeText(act, "لا توجد مجموعات للتصدير", Toast.LENGTH_SHORT).show(); return; }
                StringBuilder sb = new StringBuilder();
                for (String u : act.groupUrls) sb.append(u).append("\n");
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("groups", sb.toString().trim())); Toast.makeText(act, R.string.toast_exported, Toast.LENGTH_SHORT).show(); }
            });
            v.findViewById(R.id.btnClearGroups).setOnClickListener(btn -> {
                int size = act.groupUrls.size();
                act.groupUrls.clear(); act.groupNames.clear(); act.saveGroups();
                adapter.notifyItemRangeRemoved(0, size);
                count.setText(getString(R.string.groups_count, 0));
                Toast.makeText(act, R.string.toast_groups_cleared, Toast.LENGTH_SHORT).show();
            });
            v.findViewById(R.id.btnSmartExtractor).setOnClickListener(btn ->
                    Toast.makeText(act, "استخدم استيراد القائمة للصق الروابط", Toast.LENGTH_SHORT).show());
            return v;
        }
    }

    public static class CampaignFragment extends Fragment {
        private Runnable progressUpdater;
        @Nullable @Override
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
            TextView mediaPreviewTitle = v.findViewById(R.id.mediaPreviewTitle);
            LinearLayout mediaPreviewList = v.findViewById(R.id.mediaPreviewList);
            TextView spintaxHelp = v.findViewById(R.id.spintaxHelp);
            textIn.setText(act.postText);
            minIn.setText(String.valueOf(Math.max(5, act.minDelaySec)));
            maxIn.setText(String.valueOf(Math.max(5, act.maxDelaySec)));
            act.renderMediaPreview(mediaCount, mediaPreviewTitle, mediaPreviewList);
            ActivityResultLauncher<String[]> pickMedia = registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                        if (uris == null) return;
                        for (Uri u : uris) {
                            try { requireContext().getContentResolver().takePersistableUriPermission(
                                    u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                            if (!act.mediaUris.contains(u)) act.mediaUris.add(u);
                        }
                        act.renderMediaPreview(mediaCount, mediaPreviewTitle, mediaPreviewList);
                    });
            v.findViewById(R.id.btnPickMedia).setOnClickListener(btn -> pickMedia.launch(new String[]{"image/*", "video/*"}));
            v.findViewById(R.id.btnClearMedia).setOnClickListener(btn -> {
                act.mediaUris.clear();
                act.renderMediaPreview(mediaCount, mediaPreviewTitle, mediaPreviewList);
            });
            v.findViewById(R.id.btnSpintaxInfo).setOnClickListener(btn ->
                    spintaxHelp.setVisibility(spintaxHelp.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            progressUpdater = () -> {
                if (!isAdded()) return;
                if (act.hasPageConnection()) {
                    info.setText(getString(R.string.campaign_info, act.pages.size(),
                            act.isRunning ? getString(R.string.running) : getString(R.string.ready)));
                } else info.setText(R.string.campaign_page_not_ready);
                log.setText(act.getActivityLogText());
                startBtn.setVisibility(act.isRunning ? View.GONE : View.VISIBLE);
                stopBtn.setVisibility(act.isRunning ? View.VISIBLE : View.GONE);
                retryBtn.setVisibility(act.isRunning ? View.GONE : View.VISIBLE);
            };
            progressUpdater.run();
            act.addProgressListener(progressUpdater);
            startBtn.setOnClickListener(btn -> {
                String t = textIn.getText() != null ? textIn.getText().toString() : "";
                try { act.minDelaySec = Math.max(5, Integer.parseInt(minIn.getText().toString())); } catch (Exception ignored) {}
                try { act.maxDelaySec = Math.max(act.minDelaySec, Integer.parseInt(maxIn.getText().toString())); } catch (Exception ignored) {}
                act.prefs.edit().putInt("min_delay", act.minDelaySec).putInt("max_delay", act.maxDelaySec).apply();
                act.startPageCampaign(t, new ArrayList<>(act.mediaUris));
            });
            stopBtn.setOnClickListener(btn -> act.stopCampaign());
            retryBtn.setOnClickListener(btn -> act.retryFailed());
            return v;
        }
        @Override public void onDestroyView() {
            MainActivity act = (MainActivity) getActivity();
            if (act != null && progressUpdater != null) act.removeProgressListener(progressUpdater);
            super.onDestroyView();
        }
    }

    public static class SettingsFragment extends Fragment {
        @Nullable @Override
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
                    act.prefs.edit().putInt("min_delay", act.minDelaySec).putInt("max_delay", act.maxDelaySec).apply();
                    Toast.makeText(act, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(act, "رقم غير صالح", Toast.LENGTH_SHORT).show();
                }
            });
            v.findViewById(R.id.btnClearData).setOnClickListener(btn -> {
                act.prefs.edit().clear().apply();
                act.groupUrls.clear(); act.groupNames.clear(); act.activityLogs.clear();
                act.mediaUris.clear(); act.pages.clear();
                act.postText = ""; act.pageAccessToken = "";
                act.postedCount = 0; act.failedCount = 0; act.successStreak = 0; act.lastLog = "";
                act.notifyProgress();
                Toast.makeText(act, R.string.toast_data_cleared, Toast.LENGTH_SHORT).show();
            });
            return v;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        if (publishTask != null) publishTask.cancel(true);
        if (multiPageCampaign != null) multiPageCampaign.stop();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}

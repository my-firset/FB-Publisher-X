package com.faset.publisherx;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Publisher X — Independent Native Marketing Dashboard
 * No Facebook UI, no visible WebView, pure Material Design fragments.
 * Hidden WebView is used only for background campaign execution.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PublisherX";
    private static final String PREFS = "publisherx_prefs";

    private BottomNavigationView bottomNav;
    private WebView hiddenWebView; // never shown to user
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // Campaign state
    private final List<String> groupUrls = new ArrayList<>();
    private final List<String> groupNames = new ArrayList<>();
    private String postText = "";
    private int minDelaySec = 30;
    private int maxDelaySec = 60;
    private boolean isRunning = false;
    private int currentIndex = -1;
    private int postedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bottomNav = findViewById(R.id.bottomNav);
        hiddenWebView = findViewById(R.id.hiddenWebView);

        loadLocalData();
        setupHiddenWebView();
        setupBottomNav();

        // Default screen
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new DashboardFragment())
                    .commit();
            bottomNav.setSelectedItemId(R.id.nav_dashboard);
        }
    }

    private void loadLocalData() {
        postText = prefs.getString("post_text", "");
        minDelaySec = prefs.getInt("min_delay", 30);
        maxDelaySec = prefs.getInt("max_delay", 60);
        postedCount = prefs.getInt("posted_count", 0);

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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupHiddenWebView() {
        // Completely invisible, only for background queue execution
        WebSettings s = hiddenWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(hiddenWebView, true);
        hiddenWebView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        hiddenWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isRunning && currentIndex >= 0 && currentIndex < groupUrls.size()) {
                    handler.postDelayed(() -> injectAndPost(postText), 2500);
                }
            }
        });
        hiddenWebView.setVisibility(View.GONE);
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

    // ── Campaign logic (background only) ──────────────────────────────

    public void startCampaign(String text, int minD, int maxD) {
        if (groupUrls.isEmpty()) {
            Toast.makeText(this, "Add groups first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "Enter post text", Toast.LENGTH_SHORT).show();
            return;
        }
        postText = text.trim();
        minDelaySec = Math.max(15, minD);
        maxDelaySec = Math.max(minDelaySec, maxD);
        prefs.edit()
                .putString("post_text", postText)
                .putInt("min_delay", minDelaySec)
                .putInt("max_delay", maxDelaySec)
                .apply();

        isRunning = true;
        currentIndex = -1;
        processNext();
        Toast.makeText(this, "Campaign started in background", Toast.LENGTH_SHORT).show();
    }

    public void stopCampaign() {
        isRunning = false;
        Toast.makeText(this, "Campaign stopped", Toast.LENGTH_SHORT).show();
    }

    private void processNext() {
        if (!isRunning) return;
        currentIndex++;
        if (currentIndex >= groupUrls.size()) {
            isRunning = false;
            handler.post(() -> Toast.makeText(this, "Campaign finished", Toast.LENGTH_LONG).show());
            return;
        }
        String url = groupUrls.get(currentIndex);
        if (!url.startsWith("http")) {
            url = "https://m.facebook.com/groups/" + url;
        }
        Log.i(TAG, "Background load: " + url);
        hiddenWebView.loadUrl(url);
    }

    private void injectAndPost(String text) {
        String safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
        String js =
            "(function(){" +
            "  try {" +
            "    var sels=['div[contenteditable=true]','div[role=textbox]','textarea'];" +
            "    var box=null;" +
            "    for(var i=0;i<sels.length;i++){box=document.querySelector(sels[i]);if(box)break;}" +
            "    if(!box){AndroidBridge.onResult(false,'no_composer');return;}" +
            "    box.focus();" +
            "    if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){box.value='" + safe + "';}" +
            "    else{box.innerText='" + safe + "';}" +
            "    box.dispatchEvent(new Event('input',{bubbles:true}));" +
            "    setTimeout(function(){" +
            "      var btn=document.querySelector('button[type=submit],div[role=button]');" +
            "      var found=false;" +
            "      var all=document.querySelectorAll('button,div[role=button]');" +
            "      for(var j=0;j<all.length;j++){" +
            "        var t=(all[j].innerText||'').trim().toLowerCase();" +
            "        if(t==='post'||t==='نشر'||t==='share'||t==='مشاركة'){btn=all[j];found=true;break;}" +
            "      }" +
            "      if(btn){btn.click();AndroidBridge.onResult(true,'ok');}" +
            "      else{AndroidBridge.onResult(false,'no_button');}" +
            "    },1200);" +
            "  }catch(e){AndroidBridge.onResult(false,String(e));}" +
            "})();";
        hiddenWebView.evaluateJavascript(js, null);
    }

    private void scheduleNext() {
        if (!isRunning) return;
        int delay = minDelaySec;
        if (maxDelaySec > minDelaySec) {
            delay = minDelaySec + new Random().nextInt(maxDelaySec - minDelaySec + 1);
        }
        handler.postDelayed(this::processNext, delay * 1000L);
    }

    public class Bridge {
        @JavascriptInterface
        public void onResult(boolean success, String msg) {
            Log.i(TAG, "Post result: " + success + " / " + msg);
            if (success) {
                postedCount++;
                prefs.edit().putInt("posted_count", postedCount).apply();
            }
            scheduleNext();
        }
    }

    // ── Fragments ─────────────────────────────────────────────────────

    public static class DashboardFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_dashboard, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextView statG = v.findViewById(R.id.statGroups);
            TextView statP = v.findViewById(R.id.statPosted);
            TextView status = v.findViewById(R.id.statusText);
            ProgressBar prog = v.findViewById(R.id.campaignProgress);
            TextView log = v.findViewById(R.id.logPreview);

            statG.setText(String.valueOf(act.groupUrls.size()));
            statP.setText(String.valueOf(act.postedCount));
            status.setText(act.isRunning ? "Running… " + (act.currentIndex + 1) + "/" + act.groupUrls.size()
                    : "Idle — ready to start");
            prog.setVisibility(act.isRunning ? View.VISIBLE : View.GONE);
            if (act.isRunning && act.groupUrls.size() > 0) {
                prog.setProgress((act.currentIndex + 1) * 100 / act.groupUrls.size());
            }
            log.setText("Posted total: " + act.postedCount + "\nGroups: " + act.groupUrls.size());

            v.findViewById(R.id.btnQuickStart).setOnClickListener(btn ->
                    act.bottomNav.setSelectedItemId(R.id.nav_campaign));
            return v;
        }
    }

    public static class AccountsFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_accounts, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText name = v.findViewById(R.id.inputAccountName);
            TextInputEditText cookies = v.findViewById(R.id.inputCookies);
            TextView status = v.findViewById(R.id.accountStatus);

            name.setText(act.prefs.getString("account_name", ""));
            cookies.setText(act.prefs.getString("account_cookies", ""));
            String saved = act.prefs.getString("account_name", "");
            status.setText(saved.isEmpty() ? "No account saved yet" : "Saved: " + saved);

            v.findViewById(R.id.btnSaveAccount).setOnClickListener(btn -> {
                String n = name.getText() != null ? name.getText().toString().trim() : "";
                String c = cookies.getText() != null ? cookies.getText().toString().trim() : "";
                act.prefs.edit()
                        .putString("account_name", n)
                        .putString("account_cookies", c)
                        .apply();
                // Apply cookies to hidden WebView if provided
                if (!c.isEmpty()) {
                    CookieManager cm = CookieManager.getInstance();
                    for (String part : c.split(";")) {
                        cm.setCookie("https://m.facebook.com", part.trim());
                    }
                    cm.flush();
                }
                status.setText("Saved: " + (n.isEmpty() ? "(no label)" : n));
                Toast.makeText(act, "Account saved locally", Toast.LENGTH_SHORT).show();
            });
            return v;
        }
    }

    public static class GroupsFragment extends Fragment {
        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View v = inflater.inflate(R.layout.fragment_groups, container, false);
            MainActivity act = (MainActivity) requireActivity();
            TextInputEditText urlIn = v.findViewById(R.id.inputGroupUrl);
            TextInputEditText nameIn = v.findViewById(R.id.inputGroupName);
            ListView list = v.findViewById(R.id.listGroups);
            TextView count = v.findViewById(R.id.groupsCount);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(act,
                    android.R.layout.simple_list_item_1, act.groupNames);
            list.setAdapter(adapter);
            count.setText(act.groupUrls.size() + " groups saved");

            v.findViewById(R.id.btnAddGroup).setOnClickListener(btn -> {
                String u = urlIn.getText() != null ? urlIn.getText().toString().trim() : "";
                String n = nameIn.getText() != null ? nameIn.getText().toString().trim() : "";
                if (u.isEmpty()) {
                    Toast.makeText(act, "Enter URL or ID", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (n.isEmpty()) n = u;
                act.groupUrls.add(u);
                act.groupNames.add(n);
                act.saveGroups();
                adapter.notifyDataSetChanged();
                count.setText(act.groupUrls.size() + " groups saved");
                urlIn.setText("");
                nameIn.setText("");
            });

            v.findViewById(R.id.btnClearGroups).setOnClickListener(btn -> {
                act.groupUrls.clear();
                act.groupNames.clear();
                act.saveGroups();
                adapter.notifyDataSetChanged();
                count.setText("0 groups saved");
            });

            list.setOnItemLongClickListener((parent, view, position, id) -> {
                act.groupUrls.remove(position);
                act.groupNames.remove(position);
                act.saveGroups();
                adapter.notifyDataSetChanged();
                count.setText(act.groupUrls.size() + " groups saved");
                return true;
            });
            return v;
        }
    }

    public static class CampaignFragment extends Fragment {
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

            textIn.setText(act.postText);
            minIn.setText(String.valueOf(act.minDelaySec));
            maxIn.setText(String.valueOf(act.maxDelaySec));
            info.setText(act.groupUrls.size() + " groups selected • " +
                    (act.isRunning ? "Running" : "Ready"));
            log.setText(act.isRunning ? "Campaign in progress…" : "Waiting…");
            startBtn.setVisibility(act.isRunning ? View.GONE : View.VISIBLE);
            stopBtn.setVisibility(act.isRunning ? View.VISIBLE : View.GONE);

            startBtn.setOnClickListener(btn -> {
                String t = textIn.getText() != null ? textIn.getText().toString() : "";
                int minD = 30, maxD = 60;
                try { minD = Integer.parseInt(minIn.getText().toString()); } catch (Exception ignored) {}
                try { maxD = Integer.parseInt(maxIn.getText().toString()); } catch (Exception ignored) {}
                act.startCampaign(t, minD, maxD);
                startBtn.setVisibility(View.GONE);
                stopBtn.setVisibility(View.VISIBLE);
                info.setText(act.groupUrls.size() + " groups • Running");
                log.setText("Started…");
            });

            stopBtn.setOnClickListener(btn -> {
                act.stopCampaign();
                startBtn.setVisibility(View.VISIBLE);
                stopBtn.setVisibility(View.GONE);
                info.setText(act.groupUrls.size() + " groups • Stopped");
                log.setText("Stopped by user");
            });
            return v;
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
                    act.minDelaySec = Math.max(15, d);
                    act.maxDelaySec = act.minDelaySec + 15;
                    act.prefs.edit()
                            .putInt("min_delay", act.minDelaySec)
                            .putInt("max_delay", act.maxDelaySec)
                            .apply();
                    Toast.makeText(act, "Settings saved", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(act, "Invalid number", Toast.LENGTH_SHORT).show();
                }
            });

            v.findViewById(R.id.btnClearData).setOnClickListener(btn -> {
                act.prefs.edit().clear().apply();
                act.groupUrls.clear();
                act.groupNames.clear();
                act.postText = "";
                act.postedCount = 0;
                Toast.makeText(act, "All local data cleared", Toast.LENGTH_SHORT).show();
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
        if (hiddenWebView != null) hiddenWebView.destroy();
        super.onDestroy();
    }
}

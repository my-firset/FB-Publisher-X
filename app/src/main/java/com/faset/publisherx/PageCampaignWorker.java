package com.faset.publisherx;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One-shot WorkManager worker that runs a sequential multi-page campaign
 * using the official Meta Pages API. Payload (token, pages, text, delays)
 * is read from SharedPreferences written by MainActivity before enqueue.
 */
public class PageCampaignWorker extends Worker {

    public static final String UNIQUE_WORK_NAME = "page_campaign_scheduled";
    public static final String PREFS = "publisherx_prefs";
    public static final String KEY_SCHEDULED_PAYLOAD = "scheduled_campaign_payload";
    private static final String TAG = "PageCampaignWorker";
    private static final String GRAPH_API_VERSION = "v26.0";

    public PageCampaignWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String payloadJson = prefs.getString(KEY_SCHEDULED_PAYLOAD, "");
        if (payloadJson == null || payloadJson.isEmpty()) {
            Log.w(TAG, "No scheduled payload");
            return Result.failure(new Data.Builder().putString("error", "no_payload").build());
        }

        try {
            JSONObject payload = new JSONObject(payloadJson);
            String token = payload.optString("token", "");
            String text = payload.optString("text", "");
            int minDelay = Math.max(5, payload.optInt("min_delay", 5));
            int maxDelay = Math.max(minDelay, payload.optInt("max_delay", 10));
            JSONArray pagesArr = payload.optJSONArray("pages");
            if (token.isEmpty() || pagesArr == null || pagesArr.length() == 0) {
                return Result.failure(new Data.Builder().putString("error", "invalid_payload").build());
            }

            List<PageItem> pages = new ArrayList<>();
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject o = pagesArr.getJSONObject(i);
                String id = o.optString("id", "");
                String name = o.optString("name", id);
                if (!id.isEmpty()) pages.add(new PageItem(id, name));
            }
            if (pages.isEmpty()) {
                return Result.failure(new Data.Builder().putString("error", "no_pages").build());
            }

            // Media URIs are not persisted across process death for scheduled jobs
            // (content URIs require persistable permission and are session-bound).
            // Scheduled campaigns run text-only for reliability.
            List<Uri> media = new ArrayList<>();

            final int[] posted = {0};
            final int[] failed = {0};
            final Object lock = new Object();
            final boolean[] finished = {false};

            MultiPageCampaign campaign = new MultiPageCampaign();
            campaign.start(
                    pages,
                    token,
                    text,
                    media,
                    ctx.getContentResolver(),
                    GRAPH_API_VERSION,
                    minDelay,
                    maxDelay,
                    new android.os.Handler(android.os.Looper.getMainLooper()),
                    new MultiPageCampaign.Listener() {
                        @Override
                        public void onLog(String message) {
                            Log.i(TAG, message);
                            appendLog(prefs, message);
                        }

                        @Override
                        public void onPageResult(int index, int total, PageItem page, boolean success, String detail) {
                            if (success) {
                                posted[0]++;
                                prefs.edit().putInt("posted_count", prefs.getInt("posted_count", 0) + 1).apply();
                            } else {
                                failed[0]++;
                                prefs.edit().putInt("failed_count", prefs.getInt("failed_count", 0) + 1).apply();
                            }
                        }

                        @Override
                        public void onFinished(int p, int f) {
                            synchronized (lock) {
                                finished[0] = true;
                                lock.notifyAll();
                            }
                        }
                    });

            // Wait for campaign thread to finish (with generous timeout)
            long deadline = System.currentTimeMillis() + Math.max(30 * 60 * 1000L, pages.size() * (maxDelay + 30) * 1000L);
            synchronized (lock) {
                while (!finished[0] && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        campaign.stop();
                        return Result.failure();
                    }
                }
            }
            if (!finished[0]) {
                campaign.stop();
                return Result.failure(new Data.Builder().putString("error", "timeout").build());
            }

            prefs.edit().remove(KEY_SCHEDULED_PAYLOAD).apply();
            return Result.success(new Data.Builder()
                    .putInt("posted", posted[0])
                    .putInt("failed", failed[0])
                    .build());
        } catch (Exception e) {
            Log.e(TAG, "Worker failed", e);
            return Result.failure(new Data.Builder().putString("error", e.getMessage()).build());
        }
    }

    private static void appendLog(SharedPreferences prefs, String entry) {
        try {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            JSONArray arr;
            try {
                arr = new JSONArray(prefs.getString("activity_logs", "[]"));
            } catch (Exception e) {
                arr = new JSONArray();
            }
            arr.put("[" + ts + "] [مجدول] " + entry);
            // Keep last 100
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, arr.length() - 100);
            for (int i = start; i < arr.length(); i++) trimmed.put(arr.get(i));
            prefs.edit().putString("activity_logs", trimmed.toString()).apply();
        } catch (Exception ignored) {}
    }
}

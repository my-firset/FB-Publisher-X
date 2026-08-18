package com.faset.publisherx;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sequential multi-page publisher using official Meta Pages API.
 * Supports text, images, and single video via FacebookPagesClient.publishPost.
 */
public final class MultiPageCampaign {

    public interface Listener {
        void onLog(String message);
        void onPageResult(int index, int total, PageItem page, boolean success, String detail);
        void onFinished(int posted, int failed);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return running.get();
    }

    public void stop() {
        running.set(false);
    }

    public void start(
            List<PageItem> pages,
            String pageToken,
            String postText,
            List<Uri> mediaUris,
            ContentResolver resolver,
            String graphVersion,
            int minDelaySec,
            int maxDelaySec,
            Handler mainHandler,
            Listener listener) {

        if (running.getAndSet(true)) return;
        final List<PageItem> pagesSnap = new ArrayList<>(pages);
        final List<Uri> mediaSnap = mediaUris == null ? new ArrayList<>() : new ArrayList<>(mediaUris);
        final String token = pageToken == null ? "" : pageToken;
        final String baseText = postText == null ? "" : postText;
        final int minD = Math.max(5, minDelaySec);
        final int maxD = Math.max(minD, maxDelaySec);

        new Thread(() -> {
            int posted = 0;
            int failed = 0;
            int streak = 0;
            int total = pagesSnap.size();

            mainHandler.post(() -> listener.onLog(
                    "بدء حملة على " + total + " صفحة عبر Meta Pages API…"));

            for (int i = 0; i < total; i++) {
                if (!running.get()) break;
                final PageItem page = pagesSnap.get(i);
                final int idx = i + 1;
                final String label = page.name.isEmpty() ? page.id : page.name;

                mainHandler.post(() -> listener.onLog(
                        "[" + idx + "/" + total + "] نشر على: " + label + "…"));

                String message = MainActivity.spinText(baseText);
                FacebookPagesClient.PublishResult result = FacebookPagesClient.publishPost(
                        page.id, token, message, mediaSnap, resolver, graphVersion);

                if (result.success) {
                    posted++;
                    streak++;
                    final String postId = result.postId;
                    mainHandler.post(() -> {
                        listener.onLog("✓ [" + idx + "] نجح على " + label + " — ID: " + postId);
                        listener.onPageResult(idx, total, page, true, postId);
                    });
                } else {
                    failed++;
                    streak = 0;
                    final String err = result.message;
                    mainHandler.post(() -> {
                        listener.onLog("✗ [" + idx + "] فشل على " + label + ": " + err);
                        listener.onPageResult(idx, total, page, false, err);
                    });
                }

                if (i < total - 1 && running.get()) {
                    if (streak > 0 && streak % 15 == 0) {
                        mainHandler.post(() -> listener.onLog(
                                "استراحة حماية 5 دقائق بعد 15 منشور ناجح…"));
                        try { Thread.sleep(5 * 60 * 1000L); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        int delaySec = minD;
                        if (maxD > minD) {
                            delaySec = minD + new Random().nextInt(maxD - minD + 1);
                        }
                        final int d = delaySec;
                        mainHandler.post(() -> listener.onLog(
                                "انتظار " + d + " ثانية قبل الصفحة التالية…"));
                        try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            running.set(false);
            final int fp = posted;
            final int ff = failed;
            mainHandler.post(() -> {
                listener.onLog("انتهت الحملة — ناجح: " + fp + " | فاشل: " + ff);
                listener.onFinished(fp, ff);
            });
        }, "MultiPageCampaign").start();
    }
}

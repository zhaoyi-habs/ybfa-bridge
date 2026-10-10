package com.yb.fa;

import org.json.*;
import java.io.*;
import java.net.*;
import android.util.Base64;

public class GitHub {
    public final String token, repo;
    public GitHub(String t, String r){ token = t; repo = r; }

    HttpURLConnection c(String url, String m) throws Exception {
        HttpURLConnection x = (HttpURLConnection) new URL(url).openConnection();
        x.setRequestMethod(m);
        x.setRequestProperty("Authorization", "token " + token);
        x.setRequestProperty("Accept", "application/vnd.github+json");
        x.setRequestProperty("User-Agent", "yb-fa");
        x.setConnectTimeout(30000);
        x.setReadTimeout(15000);
        return x;
    }

    String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return new String(o.toByteArray(), "UTF-8");
    }

    public String raw(String url) throws Exception {
        HttpURLConnection x = c(url, "GET");
        int code = x.getResponseCode();
        if (code >= 400) { x.disconnect(); return null; }
        return read(x.getInputStream());
    }

    public JSONObject getJson(String path) throws Exception {
        String s = raw("https://api.github.com/repos/" + repo + "/contents/" + path);
        if (s == null) return null;
        JSONObject j = new JSONObject(s);
        String b64 = j.optString("content", "").replace("\n", "");
        if (b64.length() == 0) return null;
        String dec = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
        JSONObject out = new JSONObject(dec);
        out.put("_sha", j.optString("sha"));
        return out;
    }

    public String sha(String path) throws Exception {
        String s = raw("https://api.github.com/repos/" + repo + "/contents/" + path);
        if (s == null) return null;
        return new JSONObject(s).optString("sha", null);
    }

    public void putFile(String path, String content) throws Exception {
        String old = null;
        try { old = sha(path); } catch (Exception e) {}
        JSONObject b = new JSONObject();
        b.put("message", "ybfa " + System.currentTimeMillis());
        b.put("content", Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP));
        if (old != null) b.put("sha", old);
        post("https://api.github.com/repos/" + repo + "/contents/" + path, b);
    }

    public void delete(String path) throws Exception {
        String old = sha(path);
        if (old == null) return;
        JSONObject b = new JSONObject();
        b.put("message", "ybfa del");
        b.put("sha", old);
        post("https://api.github.com/repos/" + repo + "/contents/" + path, b, "DELETE");
    }

    public String post(String url, JSONObject body) throws Exception { return post(url, body, "PUT"); }

    public String post(String url, JSONObject body, String method) throws Exception {
        HttpURLConnection x = c(url, method);
        x.setDoOutput(true);
        x.setRequestProperty("Content-Type", "application/json");
        x.getOutputStream().write(body.toString().getBytes("UTF-8"));
        x.getOutputStream().close();
        int code = x.getResponseCode();
        String resp = read(code >= 400 ? x.getErrorStream() : x.getInputStream());
        x.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + " " + resp.substring(0, Math.min(200, resp.length())));
        return resp;
    }

    public String putBlob(byte[] data) throws Exception {
        JSONObject b = new JSONObject();
        b.put("encoding", "base64");
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        String r = post("https://api.github.com/repos/" + repo + "/git/blobs", b, "POST");
        return new JSONObject(r).optString("sha");
    }
}

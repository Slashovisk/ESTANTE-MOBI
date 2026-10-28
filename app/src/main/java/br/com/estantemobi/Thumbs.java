package br.com.estantemobi;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Capas em miniatura (files/thumbs/<id>.jpg), geradas na primeira vez que a estante pede.
 * Ao gerar, também lê título e autor de dentro do arquivo (EPUB, MOBI, FB2).
 */
final class Thumbs {

    private static final int W = 300;
    private final File dir;
    private final Sources sources;
    private final LibraryDb db;
    private final Semaphore slots = new Semaphore(2);

    Thumbs(File filesDir, Sources sources, LibraryDb db) {
        this.dir = new File(filesDir, "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        this.sources = sources;
        this.db = db;
    }

    File get(Book b) {
        File out = new File(dir, b.id + ".jpg");
        if (out.isFile()) return out;
        File none = new File(dir, b.id + ".none");
        if (none.isFile()) return null;
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            return null;
        }
        try {
            if (out.isFile()) return out;
            Bitmap bmp = null;
            try { bmp = make(b); } catch (Throwable ignored) { }
            if (bmp == null) {
                //noinspection ResultOfMethodCallIgnored
                none.createNewFile();
                return null;
            }
            File tmp = new File(dir, b.id + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, os);
            }
            bmp.recycle();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(out);
            return out.isFile() ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            slots.release();
        }
    }

    void forget(String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".jpg").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".none").delete();
    }

    private Bitmap make(Book b) throws Exception {
        String f = b.format == null ? "" : b.format;
        switch (f) {
            case "cbz": case "zip": case "cbr":
                return scale(decode(Comics.firstPage(sources, b)));
            case "pdf":
                return scale(Pdfs.firstPage(sources, b, W));
            case "epub":
                return epub(b);
            case "mobi": case "azw": case "azw3": case "prc":
                return mobi(b);
            case "fb2":
                return fb2(b);
            default:
                db.setProbe(b.id, null, null);
                return null;
        }
    }

    // ------------------------------------------------------------ EPUB

    private static final Pattern FULL_PATH = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"");

    private Bitmap epub(Book b) throws Exception {
        File local = sources.local(b);
        try (ZipFile z = Comics.openZipFile(local)) {
            String container = text(z, "META-INF/container.xml");
            Matcher m = FULL_PATH.matcher(container == null ? "" : container);
            if (!m.find()) return null;
            String opfPath = m.group(1);
            String opf = text(z, opfPath);
            if (opf == null) return null;
            db.setProbe(b.id, unescape(tag(opf, "dc:title")), unescape(tag(opf, "dc:creator")));
            String href = null;
            // EPUB 3: properties="cover-image"; EPUB 2: <meta name="cover" content="ID">
            Matcher items = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opf);
            String coverId = attr(firstMatch(opf, "<meta\\b[^>]*name\\s*=\\s*\"cover\"[^>]*>"), "content");
            String guess = null;
            while (items.find()) {
                String it = items.group();
                String props = attr(it, "properties"), id = attr(it, "id"), h = attr(it, "href"), type = attr(it, "media-type");
                if (props != null && props.contains("cover-image")) { href = h; break; }
                if (coverId != null && coverId.equals(id)) href = h;
                if (guess == null && type != null && type.startsWith("image/") && (h + id).toLowerCase().contains("cover")) guess = h;
            }
            if (href == null) href = guess;
            if (href == null) return null;
            String path = resolve(opfPath, java.net.URLDecoder.decode(href, "UTF-8"));
            ZipEntry e = z.getEntry(path);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return scale(decode(Comics.readFully(in))); }
        } finally {
            Sources.release(local);
        }
    }

    private static String text(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) { return new String(Comics.readFully(in), StandardCharsets.UTF_8); }
    }

    private static String resolve(String base, String href) {
        String dirPart = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "";
        String[] parts = (dirPart + href).split("/");
        java.util.ArrayDeque<String> st = new java.util.ArrayDeque<>();
        for (String p : parts) {
            if (p.equals("..")) { if (!st.isEmpty()) st.removeLast(); }
            else if (!p.isEmpty() && !p.equals(".")) st.addLast(p);
        }
        return String.join("/", st);
    }

    // ------------------------------------------------------------ MOBI (PalmDB)

    private Bitmap mobi(Book b) throws Exception {
        byte[] d = sources.readAll(b, 80L * 1024 * 1024);
        if (d.length < 78) return null;
        int n = u16(d, 76);
        int[] off = new int[n + 1];
        for (int i = 0; i < n; i++) off[i] = (int) u32(d, 78 + i * 8);
        off[n] = d.length;
        int r0 = off[0];
        if (r0 + 132 > d.length || d[r0 + 16] != 'M' || d[r0 + 17] != 'O') { db.setProbe(b.id, null, null); return null; }
        int hlen = (int) u32(d, r0 + 20);
        int enc = (int) u32(d, r0 + 28);
        Charset cs = enc == 65001 ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        long firstImage = u32(d, r0 + 108);
        String title = null, author = null;
        long coverOff = -1;
        if ((u32(d, r0 + 128) & 0x40) != 0) {
            int p = r0 + 16 + hlen;
            if (p + 12 <= d.length && d[p] == 'E' && d[p + 1] == 'X') {
                long count = u32(d, p + 8);
                p += 12;
                for (long i = 0; i < count && p + 8 <= d.length; i++) {
                    int type = (int) u32(d, p), len = (int) u32(d, p + 4);
                    if (len < 8 || p + len > d.length) break;
                    if (type == 503) title = new String(d, p + 8, len - 8, cs);
                    else if (type == 100 && author == null) author = new String(d, p + 8, len - 8, cs);
                    else if (type == 201 && len >= 12) coverOff = u32(d, p + 8);
                    p += len;
                }
            }
        }
        if (title == null) {
            int no = (int) u32(d, r0 + 84), nl = (int) u32(d, r0 + 88);
            if (no > 0 && r0 + no + nl <= d.length) title = new String(d, r0 + no, nl, cs);
        }
        db.setProbe(b.id, title, author);
        if (firstImage <= 0 || firstImage == 0xFFFFFFFFL || coverOff < 0) return null;
        int rec = (int) (firstImage + coverOff);
        if (rec >= n) return null;
        byte[] img = new byte[off[rec + 1] - off[rec]];
        System.arraycopy(d, off[rec], img, 0, img.length);
        return scale(decode(img));
    }

    // ------------------------------------------------------------ FB2

    private Bitmap fb2(Book b) throws Exception {
        byte[] d = sources.readAll(b, 60L * 1024 * 1024);
        String head = new String(d, 0, Math.min(d.length, 300), StandardCharsets.ISO_8859_1);
        Matcher em = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)").matcher(head);
        Charset cs = StandardCharsets.UTF_8;
        if (em.find()) { try { cs = Charset.forName(em.group(1)); } catch (Exception ignored) { } }
        String x = new String(d, cs);
        String ti = firstMatch(x, "<title-info>[\\s\\S]*?</title-info>");
        if (ti == null) ti = x;
        String author = null;
        String a = firstMatch(ti, "<author>[\\s\\S]*?</author>");
        if (a != null) author = (nz(tag(a, "first-name")) + " " + nz(tag(a, "last-name"))).trim();
        db.setProbe(b.id, unescape(tag(ti, "book-title")), author);
        String img = firstMatch(firstMatch(ti, "<coverpage>[\\s\\S]*?</coverpage>"), "<image\\b[^>]*>");
        if (img == null) return null;
        Matcher hm = Pattern.compile("href\\s*=\\s*\"#?([^\"]+)\"").matcher(img);
        if (!hm.find()) return null;
        Matcher bm = Pattern.compile("<binary\\b[^>]*id\\s*=\\s*\"" + Pattern.quote(hm.group(1)) + "\"[^>]*>([\\s\\S]*?)</binary>").matcher(x);
        if (!bm.find()) return null;
        return scale(decode(Base64.decode(bm.group(1).replaceAll("\\s+", ""), Base64.DEFAULT)));
    }

    // ------------------------------------------------------------ utilidades

    private static Bitmap decode(byte[] img) {
        if (img == null || img.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(img, 0, img.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= W) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(img, 0, img.length, o2);
    }

    private static Bitmap scale(Bitmap src) {
        if (src == null) return null;
        if (src.getWidth() <= W) return src;
        int h = Math.round(src.getHeight() * (W / (float) src.getWidth()));
        Bitmap out = Bitmap.createScaledBitmap(src, W, Math.max(1, h), true);
        if (out != src) src.recycle();
        return out;
    }

    private static String tag(String xml, String name) {
        if (xml == null) return null;
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + "\\b[^>]*>([\\s\\S]*?)</" + Pattern.quote(name) + ">").matcher(xml);
        return m.find() ? m.group(1).replaceAll("<[^>]+>", "").trim() : null;
    }

    private static String attr(String el, String name) {
        if (el == null) return null;
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(el);
        return m.find() ? m.group(1) : null;
    }

    private static String firstMatch(String s, String re) {
        if (s == null) return null;
        Matcher m = Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group() : null;
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static int u16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }

    private static long u32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    @SuppressWarnings("unused")
    private static byte[] jpeg(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
        return out.toByteArray();
    }
}

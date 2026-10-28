package br.com.estantemobi;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Acesso aos arquivos dos livros, estejam eles copiados no app ou numa pasta do celular. */
final class Sources {

    private final Context ctx;
    private final File tmpDir;

    Sources(Context ctx) {
        this.ctx = ctx;
        this.tmpDir = new File(ctx.getCacheDir(), "abertos");
        //noinspection ResultOfMethodCallIgnored
        tmpDir.mkdirs();
    }

    InputStream open(Book b) throws IOException {
        if ("app".equals(b.source)) return new FileInputStream(b.path);
        InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(b.uri));
        if (in == null) throw new IOException("O arquivo não pôde ser aberto. Ele ainda está na pasta?");
        return in;
    }

    byte[] head(Book b, int n) {
        byte[] buf = new byte[n];
        try (InputStream in = open(b)) {
            int off = 0, r;
            while (off < n && (r = in.read(buf, off, n - off)) > 0) off += r;
            if (off < n) { byte[] s = new byte[off]; System.arraycopy(buf, 0, s, 0, off); return s; }
            return buf;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    byte[] readAll(Book b, long max) throws IOException {
        try (InputStream in = open(b)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(Math.max(b.size, 1024), max));
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("Arquivo grande demais.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Um arquivo com acesso aleatório (ZIP, RAR). Para itens de pasta, usa o descritor; se falhar, copia para o cache. */
    File local(Book b) throws IOException {
        if ("app".equals(b.source)) return new File(b.path);
        File cached = new File(tmpDir, b.id + "." + Book.extOf(b.name));
        if (cached.isFile() && cached.length() == b.size && b.size > 0) return cached;
        try {
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(Uri.parse(b.uri), "r");
            if (pfd != null) {
                File proc = new File("/proc/self/fd/" + pfd.getFd());
                // testa se dá para reabrir pelo caminho; o descritor original pode ser fechado depois
                try (RandomAccessFile raf = new RandomAccessFile(proc, "r")) {
                    if (raf.length() > 0) return new Reopened(proc.getPath(), pfd);
                } catch (IOException ignored) { }
                pfd.close();
            }
        } catch (Exception ignored) { }
        // último recurso: cópia temporária (arquivos de nuvem, por exemplo)
        cleanTmp();
        try (InputStream in = open(b); OutputStream os = new FileOutputStream(cached)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return cached;
    }

    ParcelFileDescriptor descriptor(Book b) throws IOException {
        if ("app".equals(b.source)) return ParcelFileDescriptor.open(new File(b.path), ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(Uri.parse(b.uri), "r");
        if (pfd == null) throw new IOException("O arquivo não pôde ser aberto.");
        return pfd;
    }

    private void cleanTmp() {
        File[] files = tmpDir.listFiles();
        if (files == null || files.length < 3) return;
        java.util.Arrays.sort(files, (a, c) -> Long.compare(a.lastModified(), c.lastModified()));
        for (int i = 0; i < files.length - 2; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** Caminho /proc/self/fd/N que mantém o descritor vivo enquanto o objeto existir. */
    static final class Reopened extends File {
        private final transient ParcelFileDescriptor pfd;
        Reopened(String path, ParcelFileDescriptor pfd) { super(path); this.pfd = pfd; }
        void release() { try { pfd.close(); } catch (IOException ignored) { } }
    }

    static void release(File f) {
        if (f instanceof Reopened) ((Reopened) f).release();
    }

    // ------------------------------------------------------------ ordem natural (pag2 antes de pag10)

    private static final Pattern CHUNK = Pattern.compile("(\\d+)|(\\D+)");

    static final Comparator<String> NATURAL = (a, b) -> {
        Matcher ma = CHUNK.matcher(a.toLowerCase()), mb = CHUNK.matcher(b.toLowerCase());
        while (true) {
            boolean fa = ma.find(), fb = mb.find();
            if (!fa || !fb) return fa ? 1 : fb ? -1 : 0;
            String ga = ma.group(), gb = mb.group();
            int r;
            if (ma.group(1) != null && mb.group(1) != null) {
                String na = ga.replaceFirst("^0+(?=\\d)", ""), nb = gb.replaceFirst("^0+(?=\\d)", "");
                r = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
            } else {
                r = ga.compareTo(gb);
            }
            if (r != 0) return r;
        }
    };

    static boolean isImage(String name) {
        String n = name.toLowerCase();
        if (n.contains("__macosx/") || n.startsWith(".") || n.contains("/.")) return false;
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".avif");
    }

    static String imageMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".avif")) return "image/avif";
        return "image/jpeg";
    }
}

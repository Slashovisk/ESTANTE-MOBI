package br.com.estantemobi;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Desenha páginas de PDF com o leitor de PDF do próprio Android (PdfRenderer). */
final class Pdfs {

    private final Sources sources;
    private String openId;
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;

    Pdfs(Sources sources) { this.sources = sources; }

    private void ensure(Book b) throws IOException {
        if (renderer != null && b.id.equals(openId)) return;
        close();
        pfd = sources.descriptor(b);
        try {
            renderer = new PdfRenderer(pfd);
        } catch (SecurityException e) {
            close();
            throw new IOException("Este PDF é protegido por senha.");
        } catch (IOException e) {
            close();
            throw new IOException("Não foi possível ler este PDF. Ele pode estar corrompido.");
        }
        openId = b.id;
    }

    synchronized int pageCount(Book b) throws IOException {
        ensure(b);
        return renderer.getPageCount();
    }

    /** Página i como JPEG, com a largura pedida (limitada para não estourar a memória). */
    synchronized byte[] render(Book b, int index, int width) throws IOException {
        ensure(b);
        if (index < 0 || index >= renderer.getPageCount()) throw new IOException("Página inexistente.");
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int pw = Math.max(1, page.getWidth()), ph = Math.max(1, page.getHeight());
            width = Math.max(200, Math.min(width, 2400));
            float scale = width / (float) pw;
            int height = Math.round(ph * scale);
            // no máximo ~10 megapixels por página
            if ((long) width * height > 10_000_000L) {
                float k = (float) Math.sqrt(10_000_000.0 / ((long) width * height));
                width = Math.round(width * k); height = Math.round(height * k); scale = width / (float) pw;
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 6);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            bmp.recycle();
            return out.toByteArray();
        }
    }

    synchronized void close() {
        if (renderer != null) { try { renderer.close(); } catch (Exception ignored) { } renderer = null; }
        if (pfd != null) { try { pfd.close(); } catch (IOException ignored) { } pfd = null; }
        openId = null;
    }

    /** Capa: primeira página pequena, abrindo e fechando um leitor só para isso. */
    static Bitmap firstPage(Sources sources, Book b, int width) throws IOException {
        try (ParcelFileDescriptor fd = sources.descriptor(b); PdfRenderer r = new PdfRenderer(fd)) {
            if (r.getPageCount() == 0) return null;
            try (PdfRenderer.Page page = r.openPage(0)) {
                float scale = width / (float) Math.max(1, page.getWidth());
                int h = Math.max(1, Math.round(page.getHeight() * scale));
                Bitmap bmp = Bitmap.createBitmap(width, Math.min(h, width * 3), Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.setScale(scale, scale);
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            }
        } catch (SecurityException e) {
            return null;
        }
    }
}

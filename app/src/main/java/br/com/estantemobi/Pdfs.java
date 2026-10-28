package br.com.estantemobi;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Desenha páginas de PDF com o leitor de PDF do próprio Android (PdfRenderer). */
final class Pdfs {

    private final Sources sources;
    private String openId;
    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    /** área com conteúdo de cada página (frações 0..1: x0, y0, x1, y1), calculada uma vez */
    private final Map<Integer, float[]> crops = new HashMap<>();

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

    /**
     * Página i como JPEG, com a largura pedida (limitada para não estourar a memória).
     * Com crop, corta as margens brancas: a área com conteúdo ocupa toda a largura.
     */
    synchronized byte[] render(Book b, int index, int width, boolean crop) throws IOException {
        ensure(b);
        if (index < 0 || index >= renderer.getPageCount()) throw new IOException("Página inexistente.");
        // o PdfRenderer só deixa uma página aberta por vez: as áreas são medidas antes
        float[] box = crop ? stableBox(index) : new float[]{0, 0, 1, 1};
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int pw = Math.max(1, page.getWidth()), ph = Math.max(1, page.getHeight());
            float cw = (box[2] - box[0]) * pw, ch = (box[3] - box[1]) * ph;
            width = Math.max(200, Math.min(width, 2400));
            float scale = width / cw;
            int height = Math.max(1, Math.round(ch * scale));
            // no máximo ~10 megapixels por página
            if ((long) width * height > 10_000_000L) {
                float k = (float) Math.sqrt(10_000_000.0 / ((long) width * height));
                width = Math.round(width * k); height = Math.round(height * k); scale = width / cw;
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(-box[0] * pw * scale, -box[1] * ph * scale);
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream out = new ByteArrayOutputStream(width * height / 6);
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            bmp.recycle();
            return out.toByteArray();
        }
    }

    /**
     * Área da página somada à das vizinhas do mesmo lado (i-2, i+2). Assim o tamanho da letra
     * não muda de uma página para outra, mesmo numa página com pouco texto.
     */
    private float[] stableBox(int index) {
        float[] box = null;
        int n = renderer.getPageCount();
        for (int j = index - 2; j <= index + 2; j += 2) {
            if (j < 0 || j >= n) continue;
            float[] b = contentBox(j);
            if (b[2] - b[0] >= 0.999f && b[3] - b[1] >= 0.999f && j != index) continue; // página vazia/cheia não conta
            if (box == null) box = b.clone();
            else { box[0] = Math.min(box[0], b[0]); box[1] = Math.min(box[1], b[1]); box[2] = Math.max(box[2], b[2]); box[3] = Math.max(box[3], b[3]); }
        }
        return box == null ? new float[]{0, 0, 1, 1} : box;
    }

    /** Desenha a página pequena e procura onde há algo além de branco. */
    private float[] contentBox(int index) {
        float[] cached = crops.get(index);
        if (cached != null) return cached;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            return measure(page, index, Math.max(1, page.getWidth()), Math.max(1, page.getHeight()));
        }
    }

    private float[] measure(PdfRenderer.Page page, int index, int pw, int ph) {
        int sw = 240, sh = Math.max(1, Math.round(ph * (sw / (float) pw)));
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        small.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.setScale(sw / (float) pw, sw / (float) pw);
        page.render(small, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        small.recycle();
        int minX = sw, minY = sh, maxX = -1, maxY = -1;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int c = px[y * sw + x];
                int lum = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (lum < 690) { // não é (quase) branco
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        float[] box;
        if (maxX < 0) {
            box = new float[]{0, 0, 1, 1}; // página em branco
        } else {
            float pad = 0.02f;
            box = new float[]{
                    Math.max(0, minX / (float) sw - pad), Math.max(0, minY / (float) sh - pad),
                    Math.min(1, (maxX + 1) / (float) sw + pad), Math.min(1, (maxY + 1) / (float) sh + pad)};
            // conteúdo minúsculo (só um número de página, por exemplo): não amplia demais
            if (box[2] - box[0] < 0.25f) { box[0] = 0; box[2] = 1; }
            if (box[3] - box[1] < 0.1f) { box[1] = 0; box[3] = 1; }
        }
        crops.put(index, box);
        return box;
    }

    synchronized void close() {
        crops.clear();
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

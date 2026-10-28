/*
 * mobi-parser.js — leitor de arquivos MOBI (MOBI6 / PRC / AZW sem DRM) no navegador.
 * Sem dependências. Uso:
 *   const book = MobiParser.parse(arrayBuffer);
 *   book.title, book.author, book.language, book.cover (data URL ou null)
 *   book.html   -> HTML do livro já limpo, com imagens embutidas e links internos (#fpNNN)
 *   book.toc    -> [{ label, href }]  (pode vir vazio; o leitor monta a partir dos títulos)
 * Suporta compressão PalmDOC e HUFF/CDIC. Arquivos com DRM não são suportados.
 */
(function (root) {
  'use strict';

  const u16 = (v, o) => (v[o] << 8) | v[o + 1];
  const u32 = (v, o) => ((v[o] << 24) >>> 0) + (v[o + 1] << 16) + (v[o + 2] << 8) + v[o + 3];
  const ascii = (v, o, n) => String.fromCharCode(...v.subarray(o, o + n));

  function readRecords(buf) {
    const v = new Uint8Array(buf);
    if (v.length < 78) throw new Error('Arquivo pequeno demais para ser um MOBI.');
    const type = ascii(v, 60, 8);
    if (type !== 'BOOKMOBI' && type !== 'TEXtREAd') throw new Error('Este arquivo não parece ser um MOBI (assinatura "' + type + '").');
    const n = u16(v, 76);
    const offs = [];
    for (let i = 0; i < n; i++) offs.push(u32(v, 78 + i * 8));
    offs.push(v.length);
    return { v, type, records: offs.slice(0, n).map((o, i) => v.subarray(o, offs[i + 1])) };
  }

  // ---------- PalmDOC (LZ77) ----------
  function palmdoc(d) {
    const out = new Uint8Array(d.length * 8 + 16);
    let o = 0, i = 0;
    while (i < d.length) {
      const c = d[i++];
      if (c >= 1 && c <= 8) { for (let k = 0; k < c && i < d.length; k++) out[o++] = d[i++]; }
      else if (c < 0x80) out[o++] = c;
      else if (c >= 0xC0) { out[o++] = 0x20; out[o++] = c ^ 0x80; }
      else {
        const x = (c << 8) | d[i++];
        const dist = (x >> 3) & 0x7FF, len = (x & 7) + 3;
        for (let k = 0; k < len; k++) { out[o] = out[o - dist]; o++; }
      }
    }
    return out.subarray(0, o);
  }

  // ---------- HUFF/CDIC ----------
  // Sem BigInt: os códigos têm até 32 bits e cabem com folga num Number (2^53).
  function HuffCdic(records, huffIdx, huffCount) {
    const huff = records[huffIdx];
    if (ascii(huff, 0, 4) !== 'HUFF') throw new Error('Tabela HUFF inválida.');
    const P = n => Math.pow(2, n);
    const off1 = u32(huff, 8), off2 = u32(huff, 12);
    const d1len = new Uint8Array(256), d1term = new Uint8Array(256), d1max = new Float64Array(256);
    for (let i = 0; i < 256; i++) {
      const x = u32(huff, off1 + i * 4);
      const cl = x & 0x1F;
      d1len[i] = cl; d1term[i] = x & 0x80 ? 1 : 0;
      d1max[i] = (Math.floor(x / 256) + 1) * P(32 - cl) - 1;
    }
    const mincode = new Float64Array(33), maxcode = new Float64Array(33);
    for (let i = 0; i < 32; i++) {
      const cl = i + 1;
      mincode[cl] = u32(huff, off2 + i * 8) * P(32 - cl);
      maxcode[cl] = (u32(huff, off2 + i * 8 + 4) + 1) * P(32 - cl) - 1;
    }
    const dict = [];
    for (let r = huffIdx + 1; r < huffIdx + huffCount; r++) {
      const c = records[r];
      if (ascii(c, 0, 4) !== 'CDIC') throw new Error('Tabela CDIC inválida.');
      const phrases = u32(c, 8), bits = u32(c, 12);
      const n = Math.min(1 << bits, phrases - dict.length);
      for (let i = 0; i < n; i++) {
        const off = u16(c, 16 + i * 2);
        const blen = u16(c, 16 + off);
        dict.push({ bytes: c.subarray(18 + off, 18 + off + (blen & 0x7FFF)), done: !!(blen & 0x8000) });
      }
    }
    const SH = new Float64Array(33); for (let i = 0; i <= 32; i++) SH[i] = P(32 - i);
    function unpack(data) {
      const len = data.length;
      const d = new Uint8Array(len + 8); d.set(data);
      let bitsleft = len * 8, bit = 0;
      let out = new Uint8Array(Math.max(64, len * 4)), o = 0;
      for (;;) {
        // lê 32 bits a partir da posição "bit"
        const b = bit >>> 3, sh = bit & 7;
        const hi = ((d[b] << 24) >>> 0) + (d[b + 1] << 16) + (d[b + 2] << 8) + d[b + 3];
        const code = sh ? (hi * P(sh) % 4294967296) + (d[b + 4] >>> (8 - sh)) : hi;
        const top = Math.floor(code / 16777216);
        let cl = d1len[top], mc = d1max[top];
        if (!d1term[top]) { while (code < mincode[cl]) cl++; mc = maxcode[cl]; }
        bit += cl; bitsleft -= cl;
        if (bitsleft < 0) break;
        const r = Math.floor((mc - code) / SH[cl]);
        const ent = dict[r];
        if (!ent.done) { ent.done = true; ent.bytes = unpack(ent.bytes); }
        const sl = ent.bytes;
        if (o + sl.length > out.length) { const n = new Uint8Array((o + sl.length) * 2); n.set(out.subarray(0, o)); out = n; }
        out.set(sl, o); o += sl.length;
      }
      return out.subarray(0, o);
    }
    return unpack;
  }

  // ---------- bytes extras no fim de cada registro de texto ----------
  function trailingSize(rec, flags) {
    let size = rec.length, num = 0;
    const entry = end => {
      let bitpos = 0, result = 0, s = end;
      while (s > 0) {
        const b = rec[s - 1];
        result |= (b & 0x7F) << bitpos; bitpos += 7; s--;
        if ((b & 0x80) || bitpos >= 28 || s === 0) break;
      }
      return result;
    };
    for (let f = flags >> 1; f; f >>= 1) if (f & 1) num += entry(size - num);
    if (flags & 1) num += (rec[size - num - 1] & 3) + 1;
    return Math.min(num, size);
  }

  function readExth(v, o) {
    const out = {};
    if (ascii(v, o, 4) !== 'EXTH') return out;
    const count = u32(v, o + 8);
    let p = o + 12;
    for (let i = 0; i < count && p + 8 <= v.length; i++) {
      const t = u32(v, p), len = u32(v, p + 4);
      (out[t] = out[t] || []).push(v.subarray(p + 8, p + len));
      p += len;
    }
    return out;
  }

  function mimeOf(b) {
    if (b[0] === 0xFF && b[1] === 0xD8) return 'image/jpeg';
    if (b[0] === 0x89 && b[1] === 0x50) return 'image/png';
    if (b[0] === 0x47 && b[1] === 0x49) return 'image/gif';
    if (b[0] === 0x42 && b[1] === 0x4D) return 'image/bmp';
    return null;
  }
  function dataURL(bytes, mime) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return 'data:' + mime + ';base64,' + btoa(s);
  }
  const latin1 = b => { let s = ''; for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000)); return s; };
  const fromLatin1 = s => { const b = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 0xFF; return b; };

  function parse(buf) {
    const { records } = readRecords(buf);
    const r0 = records[0];
    const compression = u16(r0, 0), textLength = u32(r0, 4), textRecords = u16(r0, 8), encryption = u16(r0, 12);
    if (encryption !== 0) throw new Error('Este livro tem DRM (proteção de cópia) e não pode ser aberto.');

    let encoding = 1252, firstImage = -1, huffIdx = 0, huffCount = 0, extraFlags = 0;
    let title = '', author = '', language = '', coverIdx = -1, exth = {};
    const isMobi = r0.length > 20 && ascii(r0, 16, 4) === 'MOBI';
    if (isMobi) {
      const hlen = u32(r0, 20);
      encoding = u32(r0, 28);
      firstImage = u32(r0, 108);
      huffIdx = u32(r0, 112); huffCount = u32(r0, 116);
      if (hlen >= 0xE4) extraFlags = u16(r0, 242);
      const nameOff = u32(r0, 84), nameLen = u32(r0, 88);
      const dec0 = new TextDecoder(encoding === 65001 ? 'utf-8' : 'windows-1252');
      title = dec0.decode(r0.subarray(nameOff, nameOff + nameLen));
      if (u32(r0, 128) & 0x40) exth = readExth(r0, 16 + hlen);
      const ex = t => exth[t] ? dec0.decode(exth[t][0]) : '';
      title = ex(503) || title;
      author = (exth[100] || []).map(b => dec0.decode(b)).join(', ');
      language = ex(524);
      if (exth[201]) coverIdx = u32(exth[201][0], 0);
    }

    // texto
    let decompress;
    if (compression === 1) decompress = d => d;
    else if (compression === 2) decompress = palmdoc;
    else if (compression === 17480) decompress = HuffCdic(records, huffIdx, huffCount);
    else throw new Error('Compressão desconhecida (' + compression + ').');

    const parts = []; let total = 0;
    for (let i = 1; i <= textRecords && i < records.length; i++) {
      let rec = records[i];
      if (extraFlags) rec = rec.subarray(0, rec.length - trailingSize(rec, extraFlags));
      const out = decompress(rec);
      parts.push(out); total += out.length;
    }
    let raw = new Uint8Array(total); { let o = 0; for (const p of parts) { raw.set(p, o); o += p.length; } }
    if (textLength && textLength < raw.length) raw = raw.subarray(0, textLength);

    // imagens (registros a partir de firstImage)
    const images = [];
    if (firstImage > 0 && firstImage !== 0xFFFFFFFF) {
      for (let i = firstImage; i < records.length; i++) {
        const m = mimeOf(records[i]);
        images.push(m ? { bytes: records[i], mime: m } : null);
      }
    }
    const imgCache = {};
    const imgSrc = n => {
      if (imgCache[n] !== undefined) return imgCache[n];
      const im = images[n - 1];
      return (imgCache[n] = im ? dataURL(im.bytes, im.mime) : '');
    };

    // âncoras para links filepos (offsets em bytes do texto)
    let s = latin1(raw);
    const targets = new Set();
    s.replace(/filepos\s*=\s*["']?0*(\d+)/gi, (_, n) => { targets.add(+n); return _; });
    const sorted = [...targets].filter(n => n <= s.length).sort((a, b) => b - a);
    for (const pos of sorted) {
      // não inserir dentro de uma tag: recua até o início dela, se for o caso
      let p = pos; const lt = s.lastIndexOf('<', p - 1), gt = s.lastIndexOf('>', p - 1);
      if (lt > gt) p = lt;
      s = s.slice(0, p) + '<a id="fp' + pos + '"></a>' + s.slice(p);
    }
    const dec = new TextDecoder(encoding === 65001 ? 'utf-8' : 'windows-1252');
    let html = dec.decode(fromLatin1(s));

    // guia (sumário oficial do Kindle)
    let tocTarget = null;
    const guide = html.match(/<guide[\s\S]*?<\/guide>/i);
    if (guide) {
      const m = guide[0].match(/<reference[^>]*type\s*=\s*["']?toc["']?[^>]*filepos\s*=\s*["']?0*(\d+)/i) ||
                guide[0].match(/<reference[^>]*filepos\s*=\s*["']?0*(\d+)[^>]*type\s*=\s*["']?toc/i);
      if (m) tocTarget = 'fp' + (+m[1]);
    }

    html = html
      .replace(/<guide[\s\S]*?<\/guide>/gi, '')
      .replace(/<mbp:pagebreak\s*\/?>/gi, '<div class="mobi-pagebreak"></div>')
      .replace(/<\/?mbp:[^>]*>/gi, '')
      .replace(/filepos\s*=\s*["']?0*(\d+)["']?/gi, 'href="#fp$1"');

    // limpeza: DOM seguro, imagens embutidas
    const doc = new DOMParser().parseFromString(html, 'text/html');
    doc.querySelectorAll('script,style,iframe,object,embed,link,meta,form,base').forEach(e => e.remove());
    doc.querySelectorAll('*').forEach(el => {
      for (const a of [...el.attributes]) {
        const n = a.name.toLowerCase();
        if (n.startsWith('on')) el.removeAttribute(a.name);
        else if ((n === 'href' || n === 'src') && /^\s*javascript:/i.test(a.value)) el.removeAttribute(a.name);
        else if (n === 'height' || n === 'width') { if (el.tagName !== 'IMG') el.removeAttribute(a.name); }
      }
    });
    doc.querySelectorAll('img').forEach(img => {
      const idx = img.getAttribute('recindex') || img.getAttribute('hirecindex') || img.getAttribute('lorecindex');
      if (idx) { const src = imgSrc(parseInt(idx, 10)); if (src) img.setAttribute('src', src); else img.remove(); }
      else if (!/^data:/.test(img.getAttribute('src') || '')) img.remove();
      img.removeAttribute('width'); img.removeAttribute('height');
    });
    doc.querySelectorAll('a[href]').forEach(a => {
      const h = a.getAttribute('href');
      if (!h.startsWith('#')) { a.setAttribute('target', '_blank'); a.setAttribute('rel', 'noopener'); }
    });

    // sumário do próprio livro (links dentro da página indicada no <guide>)
    const toc = [];
    if (tocTarget) {
      const start = doc.getElementById(tocTarget);
      if (start) {
        const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_ELEMENT);
        walker.currentNode = start;
        let seenLink = false, el;
        while ((el = walker.nextNode())) {
          if (el.classList && el.classList.contains('mobi-pagebreak') && seenLink) break;
          if (el.tagName === 'A' && (el.getAttribute('href') || '').startsWith('#fp')) {
            const label = el.textContent.replace(/\s+/g, ' ').trim();
            if (label) { toc.push({ label, href: el.getAttribute('href') }); seenLink = true; }
          }
        }
      }
    }

    let cover = null;
    if (coverIdx >= 0 && images[coverIdx]) cover = dataURL(images[coverIdx].bytes, images[coverIdx].mime);

    return { title: title || 'Sem título', author, language, cover, html: doc.body.innerHTML, toc, tocTarget };
  }

  const api = { parse };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.MobiParser = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);

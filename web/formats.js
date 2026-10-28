/*
 * formats.js — leitura de EPUB, FB2, TXT e quadrinhos em ZIP/CBZ no navegador.
 * Todos devolvem o mesmo formato do MobiParser: { title, author, language, cover, html, toc }.
 * Quadrinhos devolvem { count, names, page(i) -> Promise<Blob> }.
 * Depende de DecompressionStream('deflate-raw') (Chrome/WebView 103+).
 */
(function (root) {
  'use strict';

  // ---------------------------------------------------------------- ZIP
  const u16 = (v, o) => v[o] | (v[o + 1] << 8);
  const u32 = (v, o) => (v[o] | (v[o + 1] << 8) | (v[o + 2] << 16)) + v[o + 3] * 16777216;
  const utf8 = new TextDecoder('utf-8');

  function ZipReader(buf) {
    const v = new Uint8Array(buf);
    let eocd = -1;
    for (let i = v.length - 22; i >= Math.max(0, v.length - 65557); i--) {
      if (v[i] === 0x50 && v[i + 1] === 0x4b && v[i + 2] === 5 && v[i + 3] === 6) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('O arquivo não é um ZIP válido.');
    const count = u16(v, eocd + 10);
    let p = u32(v, eocd + 16);
    const entries = [], byName = new Map();
    for (let i = 0; i < count; i++) {
      if (u32(v, p) !== 0x02014b50) break;
      const flags = u16(v, p + 8), method = u16(v, p + 10);
      const csize = u32(v, p + 20), size = u32(v, p + 24);
      const nlen = u16(v, p + 28), xlen = u16(v, p + 30), clen = u16(v, p + 32);
      const off = u32(v, p + 42);
      const nameBytes = v.subarray(p + 46, p + 46 + nlen);
      const name = (flags & 0x800) ? utf8.decode(nameBytes) : decodeLegacy(nameBytes);
      const e = { name, method, csize, size, off, encrypted: !!(flags & 1) };
      entries.push(e); byName.set(name, e); byName.set(name.toLowerCase(), byName.get(name.toLowerCase()) || e);
      p += 46 + nlen + xlen + clen;
    }
    async function bytes(e) {
      if (typeof e === 'string') e = find(e);
      if (!e) throw new Error('Arquivo interno não encontrado.');
      if (e.encrypted) throw new Error('Este arquivo está protegido por senha.');
      const lh = e.off;
      if (u32(v, lh) !== 0x04034b50) throw new Error('ZIP corrompido.');
      const start = lh + 30 + u16(v, lh + 26) + u16(v, lh + 28);
      const data = v.subarray(start, start + e.csize);
      if (e.method === 0) return data;
      if (e.method !== 8) throw new Error('Compressão de ZIP não suportada (' + e.method + ').');
      const ds = new DecompressionStream('deflate-raw');
      const out = await new Response(new Blob([data]).stream().pipeThrough(ds)).arrayBuffer();
      return new Uint8Array(out);
    }
    function find(name) {
      if (!name) return null;
      name = name.replace(/^\//, '');
      return byName.get(name) || byName.get(name.toLowerCase()) || null;
    }
    return { entries, find, bytes, text: async n => utf8.decode(await bytes(n)) };
  }
  function decodeLegacy(b) {
    // nomes antigos (CP437/latin1): tenta UTF-8 e cai para latin1
    try { return new TextDecoder('utf-8', { fatal: true }).decode(b); } catch (e) { return new TextDecoder('windows-1252').decode(b); }
  }
  const isZip = v => v[0] === 0x50 && v[1] === 0x4b && v[2] === 3 && v[3] === 4;

  // ---------------------------------------------------------------- utilidades
  const IMG_RE = /\.(jpe?g|png|gif|webp|bmp|avif)$/i;
  function mimeOfName(n) {
    const m = (n.match(/\.([a-z0-9]+)$/i) || [])[1] || '';
    return { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', avif: 'image/avif', svg: 'image/svg+xml' }[m.toLowerCase()] || 'application/octet-stream';
  }
  function dataURL(bytes, mime) {
    let s = '';
    for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return 'data:' + mime + ';base64,' + btoa(s);
  }
  function resolvePath(base, href) {
    if (!href) return '';
    href = href.split('#')[0];
    try { href = decodeURIComponent(href); } catch (e) {}
    if (/^[a-z]+:/i.test(href)) return href;
    const parts = (href.startsWith('/') ? [] : base.split('/').slice(0, -1));
    for (const seg of href.split('/')) {
      if (seg === '..') parts.pop(); else if (seg && seg !== '.') parts.push(seg);
    }
    return parts.join('/');
  }
  const natural = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // Limpa um fragmento de DOM: tira scripts, estilos, eventos e atributos que atrapalham o leitor.
  const DROP = 'script,style,link,meta,iframe,object,embed,form,input,button,select,textarea,video,audio,base,noscript';
  function sanitize(rootEl) {
    rootEl.querySelectorAll(DROP).forEach(e => e.remove());
    rootEl.querySelectorAll('*').forEach(el => {
      for (const a of [...el.attributes]) {
        const n = a.name.toLowerCase();
        if (n.startsWith('on') || n === 'style' || n === 'class' || n === 'width' || n === 'height' || n === 'align' && el.tagName !== 'P') el.removeAttribute(a.name);
        else if ((n === 'href' || n === 'src' || n === 'xlink:href') && /^\s*javascript:/i.test(a.value)) el.removeAttribute(a.name);
      }
    });
  }

  // ---------------------------------------------------------------- EPUB
  async function parseEpub(buf) {
    const zip = ZipReader(buf);
    // DRM: conteúdo (xhtml) criptografado. Fontes ofuscadas são comuns e não atrapalham.
    if (zip.find('META-INF/encryption.xml')) {
      const enc = await zip.text('META-INF/encryption.xml').catch(() => '');
      if (/CipherReference[^>]+URI\s*=\s*["'][^"']+\.(x?html?|xml)["']/i.test(enc)) throw new Error('Este EPUB tem DRM (proteção de cópia) e não pode ser aberto.');
    }
    const xml = s => new DOMParser().parseFromString(s, 'application/xml');
    const container = xml(await zip.text('META-INF/container.xml'));
    const rf = container.querySelector('rootfile');
    if (!rf) throw new Error('EPUB sem arquivo de conteúdo (OPF).');
    const opfPath = rf.getAttribute('full-path');
    const opf = xml(await zip.text(opfPath));
    const q = (sel) => opf.getElementsByTagName(sel);
    const textOf = tag => { const els = opf.getElementsByTagNameNS('*', tag); return els.length ? els[0].textContent.trim() : ''; };
    const title = textOf('title') || 'Sem título';
    const authors = [...opf.getElementsByTagNameNS('*', 'creator')].map(e => e.textContent.trim()).filter(Boolean);
    const language = textOf('language');

    const manifest = new Map(), byHref = new Map();
    for (const it of q('item')) {
      const m = { id: it.getAttribute('id'), href: resolvePath(opfPath, it.getAttribute('href')), type: it.getAttribute('media-type') || '', props: it.getAttribute('properties') || '' };
      manifest.set(m.id, m); byHref.set(m.href, m);
    }
    const spineEl = q('spine')[0];
    const spine = [...q('itemref')].map(r => manifest.get(r.getAttribute('idref'))).filter(m => m && /x?html|xml/.test(m.type || 'html'));
    if (!spine.length) throw new Error('EPUB sem capítulos.');
    const chapterIndex = new Map(spine.map((m, i) => [m.href, i]));

    // imagens viram data URLs (com cache, porque a mesma imagem pode aparecer várias vezes)
    const imgCache = new Map();
    async function imgData(path) {
      if (imgCache.has(path)) return imgCache.get(path);
      const p = (async () => { try { const b = await zip.bytes(path); const m = byHref.get(path); return dataURL(b, (m && m.type) || mimeOfName(path)); } catch (e) { return ''; } })();
      imgCache.set(path, p); return p;
    }

    // capa
    let coverPath = null;
    for (const m of manifest.values()) if (/cover-image/.test(m.props)) coverPath = m.href;
    if (!coverPath) {
      const meta = [...q('meta')].find(e => e.getAttribute('name') === 'cover');
      const m = meta && manifest.get(meta.getAttribute('content'));
      if (m && /^image\//.test(m.type)) coverPath = m.href;
    }
    if (!coverPath) for (const m of manifest.values()) if (/^image\//.test(m.type) && /cover/i.test(m.id + m.href)) { coverPath = m.href; break; }
    const cover = coverPath ? await imgData(coverPath) : null;

    const linkTarget = (fromPath, href) => {
      const [p, frag] = href.split('#');
      const target = p ? resolvePath(fromPath, p) : fromPath;
      const j = chapterIndex.get(target);
      if (j == null) return null;
      return '#e' + j + (frag ? '-' + safeId(frag) : '');
    };

    const parts = [];
    for (let i = 0; i < spine.length; i++) {
      const item = spine[i];
      let src;
      try { src = await zip.text(item.href); } catch (e) { continue; }
      let doc = new DOMParser().parseFromString(src, 'application/xhtml+xml');
      if (doc.getElementsByTagName('parsererror').length || !doc.body) doc = new DOMParser().parseFromString(src, 'text/html');
      const body = doc.body || doc.documentElement;
      // svg com <image> (capas costumam vir assim) vira <img>
      body.querySelectorAll('svg').forEach(svg => {
        const im = svg.querySelector('image');
        if (im) { const img = doc.createElement('img'); img.setAttribute('src', im.getAttribute('xlink:href') || im.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || im.getAttribute('href') || ''); svg.replaceWith(img); }
      });
      sanitize(body);
      body.querySelectorAll('[id]').forEach(el => el.setAttribute('id', 'e' + i + '-' + safeId(el.getAttribute('id'))));
      body.querySelectorAll('a[href]').forEach(a => {
        const h = a.getAttribute('href');
        if (/^(https?:|mailto:)/i.test(h)) { a.setAttribute('target', '_blank'); a.setAttribute('rel', 'noopener'); return; }
        const t = linkTarget(item.href, h);
        if (t) a.setAttribute('href', t); else a.removeAttribute('href');
      });
      const imgs = [...body.querySelectorAll('img')];
      await Promise.all(imgs.map(async img => {
        const s = img.getAttribute('src');
        if (!s || /^data:/.test(s)) return;
        const d = await imgData(resolvePath(item.href, s));
        if (d) img.setAttribute('src', d); else img.remove();
      }));
      const html = body.innerHTML.trim();
      if (!html) continue;
      parts.push((parts.length ? '<div class="mobi-pagebreak"></div>' : '') + '<a id="e' + i + '"></a>' + html);
    }

    // sumário: EPUB3 (nav) ou EPUB2 (NCX)
    const toc = [];
    const navItem = [...manifest.values()].find(m => /\bnav\b/.test(m.props));
    if (navItem) {
      try {
        const nd = new DOMParser().parseFromString(await zip.text(navItem.href), 'application/xhtml+xml');
        const navs = [...nd.getElementsByTagName('nav')];
        const nav = navs.find(n => (n.getAttribute('epub:type') || n.getAttributeNS('http://www.idpf.org/2007/ops', 'type') || '') === 'toc') || navs[0];
        if (nav) for (const a of nav.getElementsByTagName('a')) {
          const t = linkTarget(navItem.href, a.getAttribute('href') || '');
          const label = a.textContent.replace(/\s+/g, ' ').trim();
          if (t && label) toc.push({ label, href: t, level: depthOf(a, 'li') });
        }
      } catch (e) {}
    }
    if (!toc.length) {
      const ncxId = spineEl && spineEl.getAttribute('toc');
      const ncx = (ncxId && manifest.get(ncxId)) || [...manifest.values()].find(m => /ncx/.test(m.type));
      if (ncx) {
        try {
          const nd = xml(await zip.text(ncx.href));
          for (const np of nd.getElementsByTagName('navPoint')) {
            const label = (np.getElementsByTagName('text')[0] || {}).textContent;
            const c = np.getElementsByTagName('content')[0];
            const t = c && linkTarget(ncx.href, c.getAttribute('src') || '');
            if (t && label && label.trim()) toc.push({ label: label.replace(/\s+/g, ' ').trim(), href: t, level: depthOf(np, 'navPoint') });
          }
        } catch (e) {}
      }
    }
    return { title, author: authors.join(', '), language, cover, html: parts.join(''), toc: toc.filter(t => t.level <= 2) };
  }
  function depthOf(el, tag) { let d = 0; for (let p = el; p; p = p.parentElement) if (p.localName === tag || p.tagName === tag) d++; return Math.max(1, d); }
  function safeId(s) { return String(s).replace(/[^A-Za-z0-9_-]/g, '_'); }

  // ---------------------------------------------------------------- FB2
  function parseFb2(buf) {
    const v = new Uint8Array(buf);
    const head = new TextDecoder('ascii').decode(v.subarray(0, 300));
    const enc = ((head.match(/encoding\s*=\s*["']([^"']+)/i) || [])[1] || 'utf-8').toLowerCase();
    let text;
    try { text = new TextDecoder(enc).decode(v); } catch (e) { text = new TextDecoder('utf-8').decode(v); }
    const doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length && !doc.getElementsByTagName('body').length) throw new Error('Arquivo FB2 inválido.');
    const first = (el, tag) => el ? el.getElementsByTagName(tag)[0] : null;
    const ti = first(doc, 'title-info');
    const title = (first(ti, 'book-title') || {}).textContent || 'Sem título';
    const authors = ti ? [...ti.getElementsByTagName('author')].map(a => ['first-name', 'middle-name', 'last-name'].map(t => (first(a, t) || {}).textContent || '').join(' ').replace(/\s+/g, ' ').trim() || (first(a, 'nickname') || {}).textContent || '').filter(Boolean) : [];
    const language = (first(ti, 'lang') || {}).textContent || '';
    const binaries = new Map();
    for (const b of doc.getElementsByTagName('binary')) binaries.set(b.getAttribute('id'), 'data:' + (b.getAttribute('content-type') || 'image/jpeg') + ';base64,' + b.textContent.replace(/\s+/g, ''));
    const hrefOf = el => (el.getAttribute('l:href') || el.getAttribute('xlink:href') || el.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || el.getAttribute('href') || '');
    let cover = null;
    const cp = first(ti, 'coverpage');
    if (cp) { const im = first(cp, 'image'); if (im) cover = binaries.get(hrefOf(im).replace(/^#/, '')) || null; }

    const toc = []; let n = 0;
    function conv(el, depth) {
      let out = '';
      for (const c of el.childNodes) {
        if (c.nodeType === 3) { out += esc(c.textContent); continue; }
        if (c.nodeType !== 1) continue;
        const tag = c.localName, idAttr = c.getAttribute('id') ? ' id="fb-' + safeId(c.getAttribute('id')) + '"' : '';
        switch (tag) {
          case 'section': {
            const id = 'fbs' + (n++);
            const t = first(c, 'title');
            if (t && t.parentNode === c && depth <= 2) toc.push({ label: t.textContent.replace(/\s+/g, ' ').trim(), href: '#' + id, level: depth });
            out += (depth === 1 ? '<div class="mobi-pagebreak"></div>' : '') + '<div id="' + id + '"' + '>' + conv(c, depth + 1) + '</div>'; break;
          }
          case 'title': { const h = Math.max(2, Math.min(depth, 4)); out += '<h' + h + idAttr + '>' + [...c.getElementsByTagName('p')].map(p => conv(p, depth)).join('<br>') + '</h' + h + '>'; break; }
          case 'subtitle': out += '<h4' + idAttr + '>' + conv(c, depth) + '</h4>'; break;
          case 'p': out += '<p' + idAttr + '>' + conv(c, depth) + '</p>'; break;
          case 'empty-line': out += '<p>&nbsp;</p>'; break;
          case 'emphasis': out += '<i>' + conv(c, depth) + '</i>'; break;
          case 'strong': out += '<b>' + conv(c, depth) + '</b>'; break;
          case 'strikethrough': out += '<s>' + conv(c, depth) + '</s>'; break;
          case 'sup': case 'sub': out += '<' + tag + '>' + conv(c, depth) + '</' + tag + '>'; break;
          case 'epigraph': case 'cite': out += '<blockquote' + idAttr + '>' + conv(c, depth) + '</blockquote>'; break;
          case 'text-author': out += '<p style="text-align:right"><i>' + conv(c, depth) + '</i></p>'; break;
          case 'poem': case 'stanza': out += '<blockquote>' + conv(c, depth) + '</blockquote>'; break;
          case 'v': out += '<p style="text-indent:0">' + conv(c, depth) + '</p>'; break;
          case 'image': { const src = binaries.get(hrefOf(c).replace(/^#/, '')); if (src) out += '<p align="center"><img src="' + src + '"></p>'; break; }
          case 'a': { const h = hrefOf(c); out += h.startsWith('#') ? '<a href="#fb-' + safeId(h.slice(1)) + '">' + conv(c, depth) + '</a>' : '<a href="' + esc(h) + '" target="_blank" rel="noopener">' + conv(c, depth) + '</a>'; break; }
          case 'table': out += '<table>' + conv(c, depth) + '</table>'; break;
          case 'tr': case 'td': case 'th': out += '<' + tag + '>' + conv(c, depth) + '</' + tag + '>'; break;
          default: out += conv(c, depth);
        }
      }
      return out;
    }
    let html = cover ? '<p align="center"><img src="' + cover + '"></p><div class="mobi-pagebreak"></div>' : '';
    const bodies = [...doc.getElementsByTagName('body')];
    bodies.forEach((b, i) => {
      if (b.getAttribute('name') === 'notes' || b.getAttribute('name') === 'comments') html += '<div class="mobi-pagebreak"></div><h2>Notas</h2>' + conv(b, 3);
      else html += conv(b, 1);
    });
    return { title: title.trim(), author: authors.join(', '), language, cover, html, toc };
  }

  // ---------------------------------------------------------------- TXT
  function parseTxt(buf, fileName) {
    const v = new Uint8Array(buf);
    let text;
    if (v[0] === 0xFF && v[1] === 0xFE) text = new TextDecoder('utf-16le').decode(v);
    else if (v[0] === 0xFE && v[1] === 0xFF) text = new TextDecoder('utf-16be').decode(v);
    else { try { text = new TextDecoder('utf-8', { fatal: true }).decode(v); } catch (e) { text = new TextDecoder('windows-1252').decode(v); } }
    text = text.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
    const blocks = /\n[ \t]*\n/.test(text) ? text.split(/\n[ \t]*\n+/) : text.split('\n');
    const CH = /^\s*((cap[ií]tulo|chapter|parte|part|livro|book|pr[oó]logo|ep[ií]logo|prologue|epilogue)\b[^\n]{0,80}|[IVXLC]{1,7}\.?|\d{1,3}\.?)\s*$/i;
    const toc = []; let html = '', n = 0;
    for (let b of blocks) {
      b = b.trim(); if (!b) continue;
      if (b.length < 90 && !b.includes('\n') && CH.test(b)) {
        const id = 't' + (n++);
        toc.push({ label: b, href: '#' + id, level: 1 });
        html += (html ? '<div class="mobi-pagebreak"></div>' : '') + '<h2 id="' + id + '">' + esc(b) + '</h2>';
      } else html += '<p>' + esc(b).replace(/\n/g, '<br>') + '</p>';
    }
    const title = (fileName || 'Texto').replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ');
    return { title, author: '', language: '', cover: null, html, toc };
  }

  // ---------------------------------------------------------------- quadrinhos em ZIP (versão navegador)
  function openComicZip(buf) {
    const zip = ZipReader(buf);
    const pages = zip.entries.filter(e => IMG_RE.test(e.name) && !/(^|\/)(__MACOSX|\.)/.test(e.name) && e.size > 0)
      .sort((a, b) => natural.compare(a.name, b.name));
    if (!pages.length) throw new Error('Este arquivo não tem páginas de imagem.');
    return {
      count: pages.length,
      names: pages.map(p => p.name),
      async page(i) { const b = await zip.bytes(pages[i]); return new Blob([b], { type: mimeOfName(pages[i].name) }); },
    };
  }

  // Detecta o formato pelo conteúdo (e pelo nome, como desempate).
  function detect(buf, fileName) {
    const v = new Uint8Array(buf, 0, Math.min(buf.byteLength, 4096));
    const ext = ((fileName || '').match(/\.([a-z0-9]+)$/i) || [])[1] || '';
    const ascii = (o, n) => String.fromCharCode(...v.subarray(o, o + n));
    if (v.length > 68 && (ascii(60, 8) === 'BOOKMOBI' || ascii(60, 8) === 'TEXtREAd')) return 'mobi';
    if (ascii(0, 5) === '%PDF-') return 'pdf';
    if (ascii(0, 4) === 'Rar!') return 'cbr';
    if (ascii(0, 8) === 'AT&TFORM') return 'djvu';
    if (isZip(v)) {
      if (ascii(30, 8) === 'mimetype' && /epub/.test(ascii(38, 30))) return 'epub';
      if (/^epub$/i.test(ext)) return 'epub';
      return 'cbz';
    }
    const head = new TextDecoder('utf-8').decode(v.subarray(0, 600));
    if (/<FictionBook/i.test(head)) return 'fb2';
    if (/^(txt|text)$/i.test(ext)) return 'txt';
    return ext.toLowerCase() || 'desconhecido';
  }

  const api = { ZipReader, parseEpub, parseFb2, parseTxt, openComicZip, detect, natural };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.Formats = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);

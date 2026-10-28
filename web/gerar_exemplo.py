"""Gera um arquivo MOBI (MOBI6, compressão PalmDOC) de exemplo para testar o leitor.
Texto original, escrito só para esta demonstração."""
import struct, io, re, sys, argparse
ap=argparse.ArgumentParser(); ap.add_argument('--copias',type=int,default=1); ap.add_argument('--huff',action='store_true'); ap.add_argument('--out',default='exemplo.mobi'); ARGS=ap.parse_args()
from PIL import Image, ImageDraw, ImageFont

TITLE = "A Livraria da Rua das Flores"
AUTHOR = "Livro de exemplo"

CAPS = [
("Um", "A chave errada", [
"Marta herdou a livraria numa terça-feira de chuva, junto com um molho de onze chaves e nenhuma explicação. O tabelião leu o testamento do tio Aurélio em voz baixa, como quem pede desculpas, e depois empurrou o envelope pardo sobre a mesa. Dentro havia as chaves, um recibo de luz atrasado e um bilhete escrito a lápis: <i>a última abre o que importa</i>.",
"A loja ficava no fim da Rua das Flores, entre uma sapataria fechada e uma padaria que ainda fazia pão de ló às sextas. A fachada era estreita, de madeira verde descascada, e a vitrine exibia uma pilha de atlas desbotados pelo sol. Marta testou as chaves uma por uma. A sexta abriu a porta da frente. A segunda, o caixa. A nona, um armário de vassouras. A décima primeira não abriu nada.",
"Lá dentro, o cheiro era de papel úmido e café antigo. As estantes subiam até o teto, e uma escada de rodinhas corria num trilho de latão que rangia ao menor toque. Havia livros nas prateleiras, livros no chão, livros empilhados sobre cadeiras que claramente nunca tinham servido para sentar. Num canto, sobre o balcão, um caderno de capa dura registrava cada venda dos últimos trinta anos com a mesma letra miúda e inclinada.",
"Marta folheou o caderno até a última página escrita. A data era de três semanas antes da morte do tio. A entrada dizia apenas: <i>Separado para M. Não vender.</i> Não havia título, nem preço, nem indicação de prateleira.",
"Ela passou a tarde inteira procurando. Abriu gavetas, olhou atrás dos dicionários, subiu a escada até as prateleiras mais altas, onde a poeira formava uma camada fina e uniforme como neve. Quando a luz da rua começou a ficar alaranjada, sentou-se no último degrau e girou a décima primeira chave entre os dedos. Era menor que as outras, de metal escuro, com um número gravado no cabo: 214.",
]),
("Dois", "O cliente das quintas", [
"Na quinta-feira seguinte, às dez em ponto, a sineta da porta tocou. Um senhor de chapéu cinza entrou, cumprimentou Marta com um aceno curto e foi direto para a seção de poesia, como se conhecesse o caminho de olhos fechados.",
"— O senhor era cliente do meu tio? — ela perguntou.",
"— Toda quinta, há vinte e dois anos — ele respondeu, sem tirar os olhos das lombadas. — Ele sempre guardava alguma coisa para mim. Nunca pedi. Ele simplesmente sabia.",
"O homem se chamava Benedito e tinha sido professor de geografia. Contou que Aurélio costumava separar livros para os fregueses fiéis, embrulhados em papel pardo e amarrados com barbante, cada pacote com um número escrito a caneta. Os números não seguiam ordem nenhuma que alguém conseguisse entender. Benedito tinha recebido o 37, o 112, o 9 e, certa vez, o 401.",
"— E o que tinha no 401?",
"— Um guia de pássaros do Pantanal. Eu nunca tinha ido ao Pantanal. — Ele sorriu pela primeira vez. — Fui no ano seguinte.",
"Marta mostrou a chave. Benedito a examinou contra a luz da janela, virou-a duas vezes e devolveu.",
"— Ele tinha um arquivo. Um móvel de gavetinhas, desses de biblioteca antiga. Ficava nos fundos, atrás da cortina. Mas não sei se ainda existe.",
"Existia. Estava coberto por uma lona azul e por três caixas de enciclopédias que Marta precisou arrastar uma de cada vez. O móvel tinha duzentas e cinquenta gavetas numeradas, todas trancadas, todas com a mesma fechadura pequena.",
]),
("Três", "Gaveta 214", [
"A chave entrou sem esforço e girou com um estalo seco. Dentro da gaveta 214 havia um pacote de papel pardo, amarrado com barbante, e um cartão dobrado ao meio.",
"Marta não abriu o pacote de imediato. Leu primeiro o cartão, na mesma letra miúda do caderno de vendas: <i>Você vai querer vender a loja. Tudo bem. Só leia isto antes.</i>",
"O pacote continha um livro fino, sem título na capa, encadernado à mão. As páginas eram de cadernos diferentes, costuradas juntas, algumas pautadas, outras lisas. Na primeira, o tio tinha escrito: <i>Lista de pessoas que voltaram.</i>",
"Era um registro de clientes. Não de compras, mas de retornos. Ao lado de cada nome, Aurélio anotava o que tinha separado para a pessoa e o que tinha acontecido depois. A moça que levou um manual de cerâmica e abriu um ateliê. O menino que ganhou um livro de xadrez e voltou, anos depois, com um troféu de torneio estadual para mostrar. A viúva que comprou romances policiais durante um inverno inteiro e, na primavera, pediu um guia de viagem para Portugal.",
"Marta leu até a última página. A última entrada tinha o nome dela, a data do seu aniversário de nove anos e uma linha: <i>Levou o atlas. Perguntou onde ficava cada lugar. Quis saber se dava para ir a pé.</i>",
"Ela não se lembrava daquilo. Lembrava da loja de um jeito vago, como um lugar escuro onde os adultos falavam baixo. Mas foi até a vitrine e tirou o atlas de cima da pilha desbotada. Na contracapa, com a letra redonda de uma criança, estava escrito o nome dela.",
]),
("Quatro", "A placa na porta", [
"A placa de <i>vende-se</i> ficou pronta numa segunda-feira. Marta a encostou no balcão e foi preparar café, decidida a pendurá-la depois do almoço.",
"Às dez e meia, a sineta tocou. Era uma menina de uns dez anos, de uniforme escolar e mochila nas costas, que parou na porta como quem não tem certeza de que pode entrar.",
"— Aqui tem livro de vulcão?",
"Marta pensou em dizer que a loja ia fechar. Em vez disso, subiu a escada de rodinhas até a terceira prateleira da seção de ciências, onde tinha visto, dias antes, um volume grosso com uma erupção na capa. Desceu com ele debaixo do braço e o colocou sobre o balcão, ao lado do caderno de vendas.",
"A menina não tinha dinheiro suficiente. Marta embrulhou o livro em papel pardo, amarrou com barbante e escreveu um número na frente, sem pensar muito: 251.",
"— Você paga quando voltar — disse.",
"Depois que a menina saiu, Marta abriu o livro sem título na última página escrita e acrescentou, com a própria letra, um nome, uma data e uma linha. Guardou a placa de <i>vende-se</i> atrás da cortina, ao lado do arquivo de gavetinhas, e virou o aviso da porta para <i>aberto</i>.",
]),
]

def page(html):
    return html

# ---------- montar HTML no estilo MOBI6 (filepos com 10 dígitos) ----------
def build_html():
    global CAPS
    if ARGS.copias > 1:
        base = CAPS
        CAPS = [(f'{n} ({k+1})', nm, ps) for k in range(ARGS.copias) for (n, nm, ps) in base]
    parts = []
    parts.append('<html><head><guide><reference type="toc" title="Sumário" filepos=@@TOC@@ /><reference type="text" title="Início" filepos=@@C0@@ /></guide></head><body>')
    parts.append('<p height="0" width="0" align="center"><img recindex="00001" /></p><mbp:pagebreak/>')
    parts.append('<a id="TOC"></a>@@MARK_TOC@@<h2 align="center">Sumário</h2>')
    for i, (num, name, _) in enumerate(CAPS):
        parts.append(f'<p><a filepos=@@C{i}@@ >Capítulo {num.lower()} · {name}</a></p>')
    parts.append('<mbp:pagebreak/>')
    for i, (num, name, paras) in enumerate(CAPS):
        parts.append(f'@@MARK_C{i}@@<h1>Capítulo {num.lower()}</h1><h3>{name}</h3>')
        for p in paras:
            parts.append(f'<p>{p}</p>')
        parts.append('<mbp:pagebreak/>')
    parts.append('</body></html>')
    html = ''.join(parts)
    # resolve marcadores para offsets em bytes
    for _ in range(2):
        b = html
        tmp = b
        offsets = {}
        for key in ['TOC'] + [f'C{i}' for i in range(len(CAPS))]:
            mark = f'@@MARK_{key}@@'
            pre = tmp.split(mark)[0]
            pre = re.sub(r'@@MARK_[A-Z0-9]+@@', '', pre)
            pre = re.sub(r'@@[A-Z0-9]+@@', '0' * 10, pre)
            offsets[key] = len(pre.encode('utf-8'))
    out = re.sub(r'@@MARK_[A-Z0-9]+@@', '', html)
    for k, v in offsets.items():
        out = out.replace(f'@@{k}@@', f'{v:010d}')
    return out.encode('utf-8')

# ---------- compressão PalmDOC ----------
def palmdoc_compress(data):
    out = bytearray(); i = 0; n = len(data)
    while i < n:
        # tenta LZ77
        best_len, best_dist = 0, 0
        if i > 0 and i + 3 <= n:
            start = max(0, i - 2047)
            maxl = min(10, n - i)
            for L in range(maxl, 2, -1):
                j = data.rfind(data[i:i+L], start, i + L - 1)
                if j != -1 and j < i:
                    best_len, best_dist = L, i - j
                    break
        if best_len >= 3:
            v = 0x8000 | (best_dist << 3) | (best_len - 3)
            out += struct.pack('>H', v); i += best_len; continue
        c = data[i]
        if c == 0x20 and i + 1 < n and 0x40 <= data[i+1] <= 0x7f:
            out.append(data[i+1] ^ 0x80); i += 2; continue
        if c == 0 or (0x09 <= c <= 0x7f):
            out.append(c); i += 1; continue
        # bytes altos: literal 1..8
        j = i; buf = bytearray()
        while j < n and len(buf) < 8 and (data[j] >= 0x80 or 1 <= data[j] <= 8):
            buf.append(data[j]); j += 1
        out.append(len(buf)); out += buf; i = j
    return bytes(out)

def palmdoc_decompress(d):
    out = bytearray(); i = 0
    while i < len(d):
        c = d[i]; i += 1
        if 1 <= c <= 8: out += d[i:i+c]; i += c
        elif c < 0x80: out.append(c)
        elif c >= 0xC0: out += bytes([0x20, c ^ 0x80])
        else:
            v = (c << 8) | d[i]; i += 1
            dist = (v >> 3) & 0x7ff; L = (v & 7) + 3
            for _ in range(L): out.append(out[-dist])
    return bytes(out)

def cover():
    W, H = 600, 900
    im = Image.new('RGB', (W, H), (38, 70, 60))
    d = ImageDraw.Draw(im)
    for k in range(0, H, 30):
        d.line([(0, k), (W, k)], fill=(44, 78, 67), width=2)
    d.rectangle([40, 40, W-40, H-40], outline=(214, 196, 150), width=4)
    try:
        fb = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf', 54)
        fs = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf', 28)
    except Exception:
        fb = fs = ImageFont.load_default()
    y = 260
    for line in ['A Livraria', 'da Rua', 'das Flores']:
        w = d.textlength(line, font=fb); d.text(((W-w)/2, y), line, font=fb, fill=(240, 228, 196)); y += 76
    d.line([(220, y+30), (380, y+30)], fill=(214, 196, 150), width=3)
    t = 'livro de exemplo'; w = d.textlength(t, font=fs)
    d.text(((W-w)/2, H-150), t, font=fs, fill=(214, 196, 150))
    b = io.BytesIO(); im.save(b, 'JPEG', quality=80); return b.getvalue()

def build():
    text = build_html()
    RS = 4096
    chunks = [text[i:i+RS] for i in range(0, len(text), RS)]
    recs = []
    for ch in chunks:
        if ARGS.huff:
            c = bytes(255 - v for v in ch)
        else:
            c = palmdoc_compress(ch)
            assert palmdoc_decompress(c) == ch
        # trailing: multibyte (0 bytes de sobreposição) + uma entrada TBS de 2 bytes
        recs.append(c + b'\x00' + b'\x00\x82')
    extra_flags = 0b11
    img = cover()
    huffrecs = []
    if ARGS.huff:
        h = bytearray(b'HUFF' + struct.pack('>III', 24, 24, 24 + 1024))
        h += b''.join(struct.pack('>I', (255 << 8) | 0x80 | 8) for _ in range(256))
        h += b'\x00' * (64 * 4)
        cd = bytearray(b'CDIC' + struct.pack('>III', 16, 256, 8))
        offs = b''; ents = b''; base = 256 * 2
        for v in range(256):
            offs += struct.pack('>H', base + len(ents)); ents += struct.pack('>H', 0x8000 | 1) + bytes([v])
        cd += offs + ents
        huffrecs = [bytes(h), bytes(cd)]
    first_img = 1 + len(recs) + len(huffrecs)
    name = TITLE.encode('utf-8')
    exth_recs = [(100, AUTHOR.encode('utf-8')), (503, name), (201, struct.pack('>I', 0)), (524, b'pt')]
    exth_body = b''.join(struct.pack('>II', t, 8 + len(v)) + v for t, v in exth_recs)
    exth = b'EXTH' + struct.pack('>II', 12 + len(exth_body), len(exth_recs)) + exth_body
    exth += b'\x00' * ((4 - len(exth) % 4) % 4)
    MH = 232
    mobi = bytearray(MH)
    mobi[0:4] = b'MOBI'
    struct.pack_into('>I', mobi, 4, MH)
    struct.pack_into('>I', mobi, 8, 2)        # tipo: livro
    struct.pack_into('>I', mobi, 12, 65001)   # UTF-8
    struct.pack_into('>I', mobi, 16, 12345)
    struct.pack_into('>I', mobi, 20, 6)       # versão
    for off in range(24, 64, 4): struct.pack_into('>I', mobi, off, 0xFFFFFFFF)
    struct.pack_into('>I', mobi, 64, first_img + 1)  # primeiro registro não-livro
    full_name_off = 16 + MH + len(exth)
    struct.pack_into('>I', mobi, 68, full_name_off)
    struct.pack_into('>I', mobi, 72, len(name))
    struct.pack_into('>I', mobi, 76, 0x16)    # locale pt
    struct.pack_into('>I', mobi, 88, 6)
    struct.pack_into('>I', mobi, 92, first_img)
    if ARGS.huff:
        struct.pack_into('>I', mobi, 96, 1 + len(recs)); struct.pack_into('>I', mobi, 100, 2)
    struct.pack_into('>I', mobi, 112, 0x50)   # EXTH presente
    struct.pack_into('>H', mobi, 176, 1)
    struct.pack_into('>H', mobi, 178, len(recs))
    struct.pack_into('>H', mobi, 226, extra_flags)
    palm = struct.pack('>HHIHHHH', 17480 if ARGS.huff else 2, 0, len(text), len(recs), RS, 0, 0)
    rec0 = palm + bytes(mobi) + exth + name + b'\x00' * (4 + (4 - (len(name) % 4)) % 4) + b'\x00' * 2048
    records = [rec0] + recs + huffrecs + [img, b'\xe9\x8e\r\n']
    hdr = bytearray(78)
    pdbname = b'A_Livraria_da_Rua_das_Flores'[:31]
    hdr[0:len(pdbname)] = pdbname
    hdr[60:64] = b'BOOK'; hdr[64:68] = b'MOBI'
    struct.pack_into('>H', hdr, 76, len(records))
    off = 78 + 8 * len(records) + 2
    lst = b''
    for k, r in enumerate(records):
        lst += struct.pack('>II', off, (k * 2) & 0xFFFFFF); off += len(r)
    data = bytes(hdr) + lst + b'\x00\x00' + b''.join(records)
    open(ARGS.out, 'wb').write(data)
    print('ok', len(data), 'bytes;', len(recs), 'registros de texto; texto', len(text))

build()

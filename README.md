# Estante

Aplicativo Android para guardar e ler livros, quadrinhos e PDFs, com uma estante organizada por tipo e por série.

| Tipo | Formatos | Como abre |
|---|---|---|
| Livros | MOBI, EPUB, FB2, TXT (e AZW3 que traga a parte MOBI) | Leitor paginado no estilo Kindle: fontes, temas, sumário, tempo restante |
| Quadrinhos | CBZ, CBR, ZIP com imagens | Página por página, zoom com pinça ou toque duplo, página dupla com o celular deitado, modo mangá |
| Documentos | PDF | Páginas desenhadas pelo leitor de PDF do próprio Android, com zoom |

## Gerar o APK

Cada envio (`push`) para este repositório gera um APK novo automaticamente:

1. Abra a aba **Actions** e clique no build mais recente (ícone verde).
2. Em **Artifacts**, baixe `EstanteMobi-1.0.N`. Vem um .zip com o APK dentro.
3. Copie o APK para o celular e toque nele. Na primeira vez, o Android pede para permitir "instalar de fontes desconhecidas"; se o Play Protect avisar, toque em **Instalar mesmo assim**.

Cada versão nova instala por cima da anterior **sem apagar a estante**. Também dá para gerar manualmente em **Actions → Gerar APK → Run workflow**, ou pelo Android Studio (**Build → Build APK(s)**).

## Como usar

**Adicionar** (botão no canto) oferece duas opções:

- **Escolher arquivos:** copia os arquivos escolhidos para dentro do app. É bom para livros avulsos, porque o livro continua na estante mesmo que o original seja apagado.
- **Adicionar uma pasta inteira:** o app lê os arquivos onde já estão, sem copiar, incluindo subpastas. Use para coleções grandes, como milhares de quadrinhos. Cada subpasta vira uma **série** na estante. Arquivos novos colocados na pasta aparecem sozinhos na próxima vez que o app abrir.

Outros detalhes:

- **"Abrir com" e "Compartilhar":** tocando num arquivo no gerenciador de arquivos ou na lista de downloads e escolhendo *Estante*, o arquivo entra na estante e já abre.
- **Abas Livros, Quadrinhos e PDF:** aparecem quando há mais de um tipo. A busca procura em todas as abas.
- **"Continuar lendo":** mostra os últimos itens abertos, de qualquer tipo.
- **Pressionar e segurar uma capa** (ou tocar em ⋯): mostra detalhes, permite marcar como lido ou não lido e remover. Itens de pasta são só ocultados; o arquivo continua no celular.
- **Pastas da estante:** em Adicionar → Pastas da estante, dá para atualizar ou remover uma pasta.
- **Quadrinhos:** tocar nas laterais vira a página e no centro mostra os controles. Nas opções, escolha a direção (mangá lê da direita para a esquerda), o ajuste (página inteira ou largura da tela) e a página dupla. Pastas e arquivos com "manga" no nome já abrem no modo mangá.
- **Botão voltar:** fecha painéis, depois sai do livro ou da série, depois sai do app.

## Limites conhecidos

- **DRM:** livros com proteção de cópia (comprados na Amazon ou em lojas com Adobe DRM) não abrem.
- **AZW3 só em KF8:** ainda não é lido. Muitos AZW3 trazem também a versão MOBI e abrem normalmente; os outros podem ser convertidos para EPUB no Calibre.
- **CBR em RAR 5:** a biblioteca usada lê RAR 4, o formato mais comum em quadrinhos. Arquivos em RAR 5 mostram um aviso; converta-os para CBZ.
- **Ainda não suportados:** CB7 (7z) e DJVU.
- **CBR na primeira abertura:** as páginas são extraídas para o cache, o que leva alguns segundos em arquivos grandes. O app guarda as duas últimas revistas extraídas.
- **Fontes de leitura:** vêm do Google Fonts. Sem internet, o app usa as fontes serifadas do Android.
- **Versão do Android:** 8.0 ou mais novo.

## Como o projeto está organizado

```
app/src/main/java/br/com/estantemobi/
  MainActivity.java    WebView, ponte com a página, importação, endereços locais
  LibraryDb.java       banco SQLite da estante (itens e pastas)
  FolderScanner.java   leitura de pastas pelo Storage Access Framework, sem copiar
  Sources.java         acesso aos arquivos (copiados ou de pasta), ordem natural de nomes
  Comics.java          páginas de CBZ/ZIP (java.util.zip) e CBR (junrar)
  Pdfs.java            páginas de PDF (android.graphics.pdf.PdfRenderer)
  Thumbs.java          capas em miniatura e leitura de título/autor (EPUB, MOBI, FB2)
app/src/main/assets/www/index.html   interface (gerada a partir de web/)
web/app.template.html                estante, leitor de livros e visualizador de páginas
web/mobi-parser.js                   formato MOBI (PalmDOC e HUFF/CDIC)
web/formats.js                       EPUB, FB2, TXT e quadrinhos em ZIP (versão navegador)
web/montar.py                        junta tudo em assets/www/index.html
.github/workflows/gerar-apk.yml      build automático
```

A única biblioteca externa é a [junrar](https://github.com/junrar/junrar), usada para ler RAR. A página fala com o Java por uma ponte chamada `Android` e lê os arquivos por endereços servidos pelo próprio app, sem internet:

| Endereço | Conteúdo |
|---|---|
| `/books/<id>.bin` | arquivo original (livros de texto) |
| `/thumb/<id>.jpg` | capa em miniatura (gerada na primeira vez) |
| `/comic/<id>/pages.json` e `/comic/<id>/<n>` | páginas de quadrinho |
| `/pdf/<id>/info.json` e `/pdf/<id>/<n>.jpg?w=1200` | páginas de PDF desenhadas |

### Alterar a interface

Edite os arquivos em `web/` e rode `python3 web/montar.py`, que atualiza `app/src/main/assets/www/index.html`. Depois faça o commit para o GitHub gerar o APK novo. Dá para testar no navegador abrindo esse `index.html`; fora do app, a estante usa o armazenamento do navegador e abre livros e CBZ (CBR e PDF só no app).

## Versão para a loja (Play Store)

O APK do GitHub é assinado com uma chave de teste incluída no projeto (`app/debug.keystore`). A Play Store exige uma chave própria:

1. Crie a chave uma vez e guarde-a com segurança; sem ela não é possível publicar atualizações:
   ```
   keytool -genkeypair -v -keystore estante-release.jks -alias estante -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Crie `keystore.properties` na raiz (já está no `.gitignore`):
   ```
   storeFile=estante-release.jks
   storePassword=SUA_SENHA
   keyAlias=estante
   keyPassword=SUA_SENHA
   ```
3. Gere com `./gradlew bundleRelease` (`.aab` para a loja) ou `./gradlew assembleRelease` (APK).

Antes de publicar, troque o `applicationId` em `app/build.gradle` (`br.com.estantemobi`) pelo identificador da sua empresa.

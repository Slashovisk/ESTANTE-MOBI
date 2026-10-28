# Estante MOBI

Aplicativo Android para guardar e ler livros `.mobi`. Cada livro escolhido é copiado para dentro do app e entra na estante com capa, título, autor e progresso de leitura. Apagar o arquivo original do celular não tira o livro da estante.

## Gerar o APK pelo GitHub (sem instalar nada)

1. Crie um repositório no GitHub (pode ser privado).
2. Envie **o conteúdo desta pasta** para a raiz do repositório. O arquivo `.github/workflows/gerar-apk.yml` precisa ficar na raiz.
   - Pelo site: em **Add file → Upload files**, arraste todos os arquivos e pastas daqui, inclusive a pasta `.github`. Se o navegador esconder pastas que começam com ponto, crie o arquivo `.github/workflows/gerar-apk.yml` pelo botão **Add file → Create new file** e cole o conteúdo.
   - Pelo terminal:
     ```
     git init && git add . && git commit -m "Estante MOBI"
     git branch -M main
     git remote add origin https://github.com/SEU_USUARIO/estante-mobi.git
     git push -u origin main
     ```
3. Abra a aba **Actions** do repositório. O build "Gerar APK" começa sozinho e leva de 3 a 5 minutos.
4. Ao terminar, abra o build e baixe o arquivo em **Artifacts** (`EstanteMobi-1.0.N`). Ele vem em um .zip com o APK dentro.

Cada `push` gera um APK novo com número de versão maior. Ele instala por cima do anterior **sem apagar a estante**. Também dá para rodar manualmente em **Actions → Gerar APK → Run workflow**.

## Gerar pelo Android Studio

Abra esta pasta no Android Studio (File → Open), espere o Gradle sincronizar e use **Build → Build APK(s)**. O APK fica em `app/build/outputs/apk/debug/`.

Pelo terminal, com o Android SDK instalado: `./gradlew assembleDebug`.

## Instalar no celular

1. Copie o `.apk` para o celular, ou baixe-o direto do GitHub pelo navegador do celular.
2. Toque no arquivo. O Android vai pedir para permitir a instalação de "fontes desconhecidas" pelo app que abriu o arquivo (Chrome ou gerenciador de arquivos).
3. Na primeira vez, o Play Protect pode avisar que o app não é conhecido. Toque em **Instalar mesmo assim**.

## Como usar

- **Adicionar livro:** abre o seletor de arquivos do Android. Dá para escolher vários de uma vez, inclusive da pasta Downloads.
- **Abrir com / Compartilhar:** ao tocar num `.mobi` no gerenciador de arquivos ou na lista de downloads, escolha *Estante MOBI*. O livro vai para a estante e já abre.
- **Pressionar e segurar uma capa** (ou tocar em ⋯) mostra os detalhes do livro e a opção de remover.
- **Botão voltar:** dentro do livro, volta para a estante. Na estante, sai do app.
- Livros repetidos são reconhecidos pelo conteúdo do arquivo e não entram duas vezes.

## Como o projeto está organizado

```
app/src/main/java/br/com/estantemobi/MainActivity.java   app nativo: WebView, seletor de arquivos, armazenamento
app/src/main/assets/www/index.html                        interface (gerada a partir de web/)
app/src/main/AndroidManifest.xml                          "Abrir com" e "Compartilhar" para .mobi
web/app.template.html                                     interface: estante + leitor (edite aqui)
web/mobi-parser.js                                        leitura do formato MOBI (PalmDOC e HUFF/CDIC)
web/montar.py                                             junta tudo em assets/www/index.html
.github/workflows/gerar-apk.yml                           build automático no GitHub
```

O app não usa nenhuma biblioteca externa. A interface roda na WebView do sistema e conversa com o Java por uma ponte chamada `Android`:

| Função | O que faz |
|---|---|
| `listBooks()` | devolve a lista de livros (JSON) |
| `pickBook()` | abre o seletor de arquivos |
| `importBase64(nome, dados, abrir)` | guarda um arquivo enviado pela própria página |
| `updateMeta(id, json)` | salva título, capa, posição de leitura etc. |
| `deleteBook(id)` | remove o livro |
| `setBars(cor, escuro)` | pinta as barras do sistema com a cor do tema de leitura |

Os livros ficam em `files/books/` (área privada do app): `<id>.bin` é o arquivo original e `<id>.json` guarda os dados. A página lê cada livro em `https://appassets.androidplatform.net/books/<id>.bin`, um endereço servido pelo próprio app, sem internet.

### Alterar a interface

Edite `web/app.template.html` (ou `web/mobi-parser.js`) e rode:

```
python3 web/montar.py
```

Isso atualiza `app/src/main/assets/www/index.html`. Depois faça o commit para o GitHub gerar o APK novo. Dá para testar a interface no navegador do computador abrindo o `index.html` gerado; fora do app, a estante usa o armazenamento do navegador.

## Versão para a loja (Play Store)

O APK do GitHub é assinado com uma chave de teste incluída no projeto (`app/debug.keystore`). Isso serve para instalar e atualizar no celular, mas a Play Store exige uma chave própria:

1. Crie a chave uma vez e guarde-a com segurança. Se ela for perdida, não será mais possível publicar atualizações:
   ```
   keytool -genkeypair -v -keystore estante-release.jks -alias estante -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Crie `keystore.properties` na raiz do projeto (ele já está no `.gitignore`):
   ```
   storeFile=estante-release.jks
   storePassword=SUA_SENHA
   keyAlias=estante
   keyPassword=SUA_SENHA
   ```
3. Gere o pacote: `./gradlew bundleRelease` (arquivo `.aab` para a Play Store) ou `./gradlew assembleRelease` (APK).

Troque também o `applicationId` em `app/build.gradle` (`br.com.estantemobi`) pelo identificador da sua empresa antes de publicar.

## Limites conhecidos

- Livros com DRM (comprados na Amazon) não abrem. Isso é uma proteção do arquivo e não tem como contornar.
- Arquivos só em KF8 (`.azw3`) ainda não são lidos. Muitos `.mobi` modernos trazem as duas versões e abrem normalmente.
- As fontes de leitura (Literata, Source Serif, Atkinson) vêm do Google Fonts. Sem internet, o app usa as fontes serifadas do próprio Android.
- Android 7.0 ou mais novo, com a WebView do sistema atualizada.

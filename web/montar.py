"""Gera app/src/main/assets/www/index.html a partir de app.template.html + mobi-parser.js.
Rode depois de editar a interface:  python3 web/montar.py"""
import base64, os
here = os.path.dirname(os.path.abspath(__file__))
rd = lambda n: open(os.path.join(here, n), encoding='utf-8').read()
t = rd('app.template.html')
t = t.replace('/*__PARSER__*/', rd('mobi-parser.js')).replace('/*__FORMATS__*/', rd('formats.js'))
t = t.replace('__SAMPLE_B64__', base64.b64encode(open(os.path.join(here, 'exemplo.mobi'), 'rb').read()).decode())
i = t.index('<div class="app"')
doc = ('<!doctype html>\n<html lang="pt-BR">\n<head>\n<meta charset="utf-8">\n'
       '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
       '<meta name="theme-color" content="#fbfaf7">\n' + t[:i] +
       '<style>:root{padding:env(safe-area-inset-top,0px) 0 env(safe-area-inset-bottom,0px)}body{margin:0;font-size:14px}img{max-width:100%}</style>\n'
       '</head>\n<body>\n' + t[i:] + '\n</body>\n</html>\n')
out = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'www', 'index.html')
os.makedirs(os.path.dirname(out), exist_ok=True)
open(out, 'w', encoding='utf-8').write(doc)
print('ok:', os.path.normpath(out), len(doc), 'bytes')

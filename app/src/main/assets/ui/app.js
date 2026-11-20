(function () {
'use strict';

/* ======================================================================
 * FCC Claude — interface (frontend). O backend (Claude Code + FCC Server)
 * roda no Linux interno e conversa com esta tela pela ponte "AndroidBridge".
 * ====================================================================== */

const $ = (s, el) => (el || document).querySelector(s);
const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));

/* ---------- ícones ---------- */
const I = {
  home: '<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
  msgs: '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z"/>',
  chat: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><line x1="8" y1="9" x2="16" y2="9"/><line x1="8" y1="13" x2="13" y2="13"/>',
  folder: '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  dots: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
  bell: '<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>',
  clip: '<path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',
  send: '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>',
  arrow: '<line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/>',
  back: '<line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>',
  chev: '<polyline points="9 18 15 12 9 6"/>',
  down: '<line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/>',
  copy: '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  check: '<polyline points="20 6 9 17 4 12"/>',
  x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  trash: '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
  edit: '<path d="M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"/>',
  refresh: '<polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',
  file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2.5" fill="currentColor"/>',
  lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  cube: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/>',
  bulb: '<path d="M9 18h6M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.2 1 2v.3h6v-.3c0-.8.4-1.5 1-2A7 7 0 0 0 12 2z"/>',
  search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
  terminal: '<polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>',
  user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  heart: '<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>',
  warn: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
};
function icon(name) {
  const fill = name === 'stop' || name === 'dots';
  return '<svg viewBox="0 0 24 24" fill="' + (fill ? 'currentColor' : 'none') + '" stroke="' + (fill ? 'none' : 'currentColor') +
    '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + (I[name] || '') + '</svg>';
}
function paintIcons(root) { $$('[data-i]', root).forEach(el => { if (!el.dataset.done) { el.innerHTML = icon(el.dataset.i); el.dataset.done = '1'; } }); }

/* ---------- ponte com o Android ---------- */
window.__posts = [];
const N = {
  post(o) {
    window.__posts.push(o);
    try { window.AndroidBridge.post(JSON.stringify(o)); } catch (e) { /* sem Android (teste em navegador) */ }
  }
};

/* ---------- estado ---------- */
const S = {
  version: '1.0', busy: null, user: { name: '' }, mascot: true,
  conv: { id: '', name: 'Nova conversa', folder: { kind: 'none', name: '' }, sync: { state: 'idle', last: 0, summary: '' } },
  convs: [], server: { state: 'starting', port: 0, msg: '' }, gen: { running: false, convId: '' },
  notices: [], settings: { mode: 'auto' },
};
let msgs = [];
let view = 'home';
let pending = [];            // anexos escolhidos
let draft = null;            // texto enviado (para restaurar se faltar pasta)
let fs = { path: '', entries: [], loading: false, error: '' };
let askedFolder = {};

const SUGGESTIONS = [
  { e: '🚀', t: 'Criar um projeto Python', p: 'Crie um projeto Python novo nesta pasta, com estrutura organizada (main.py, requirements.txt e README). Antes de começar, me pergunte o nome e o que o projeto deve fazer.' },
  { e: '🤖', t: 'Gerar um app Android', p: 'Quero criar um aplicativo Android. Me pergunte o que ele deve fazer e então proponha um plano e crie a estrutura inicial do projeto nesta pasta.' },
  { e: '</>', t: 'Explicar um código', p: 'Explique de forma simples o código desta pasta. Se não houver arquivos, peça para eu enviar ou colar o código.' },
  { e: '📦', t: 'Instalar uma biblioteca', p: 'Quero instalar uma biblioteca Python. Pergunte qual e instale no ambiente deste projeto, mostrando um exemplo de uso.' },
];
const MODES = [
  { id: 'auto', t: 'Automático', d: 'Recomendado: a IA edita arquivos e roda comandos, bloqueando ações arriscadas.' },
  { id: 'bypassPermissions', t: 'Total', d: 'A IA executa tudo sem pedir confirmação. Use com atenção.' },
  { id: 'acceptEdits', t: 'Cautelosa', d: 'A IA só edita arquivos; comandos de terminal ficam bloqueados.' },
];

/* ---------- utilidades ---------- */
function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function toast(t) { const el = $('#toast'); el.textContent = t; el.classList.add('on'); clearTimeout(toast._t); toast._t = setTimeout(() => el.classList.remove('on'), 2600); }
function ago(ts) {
  if (!ts) return '';
  const d = (Date.now() - ts) / 1000;
  if (d < 45) return 'agora';
  if (d < 3600) return Math.round(d / 60) + ' min';
  if (d < 86400) return Math.round(d / 3600) + ' h';
  if (d < 172800) return 'ontem';
  return new Date(ts).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
}
function sizeFmt(n) { if (n < 1024) return n + ' B'; if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'; return (n / 1048576).toFixed(1) + ' MB'; }
function el(tag, cls, html) { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

/* ---------- folha (bottom sheet) ---------- */
function openSheet(o) {
  $('#sheet-title').textContent = o.title || '';
  const body = $('#sheet-body'); body.innerHTML = '';
  if (o.html != null) body.innerHTML = o.html; else if (o.node) body.appendChild(o.node);
  const acts = $('#sheet-actions'); acts.innerHTML = '';
  (o.actions || []).forEach(a => {
    const b = el('button', 'btn ' + (a.kind || '') + ' block', (a.icon ? '<span data-i="' + a.icon + '"></span>' : '') + esc(a.label));
    b.addEventListener('click', () => { if (a.keep !== true) closeSheet(); if (a.onClick) a.onClick(); });
    acts.appendChild(b);
  });
  paintIcons($('#sheet'));
  $('#sheet-bg').classList.add('on');
  if (o.focus) setTimeout(() => { const i = $(o.focus); if (i) { i.focus(); i.select && i.select(); } }, 120);
}
function closeSheet() { $('#sheet-bg').classList.remove('on'); }
$('#sheet-bg').addEventListener('click', e => { if (e.target.id === 'sheet-bg') closeSheet(); });

function promptSheet(title, value, okLabel, cb) {
  openSheet({
    title, html: '<input type="text" id="p-input" maxlength="80">', focus: '#p-input',
    actions: [{ label: okLabel, kind: 'primary', onClick: () => cb(($('#p-input') || {}).value || '') }, { label: 'Cancelar' }]
  });
  const i = $('#p-input'); i.value = value || '';
  i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); const v = i.value; closeSheet(); cb(v); } });
}
function confirmSheet(title, text, okLabel, cb) {
  openSheet({ title, html: '<p>' + esc(text) + '</p>', actions: [{ label: okLabel, kind: 'danger', onClick: cb }, { label: 'Cancelar' }] });
}

/* ---------- navegação ---------- */
function go(name) {
  view = name;
  $$('.view').forEach(v => v.classList.toggle('active', v.id === 'v-' + name));
  $$('#tabs button').forEach(b => b.classList.toggle('active', b.dataset.tab === name || (name === 'chat' && b.dataset.tab === 'convs')));
  const comp = (name === 'home' || name === 'chat');
  document.body.classList.toggle('comp', comp);
  document.body.classList.toggle('nocomp', !comp);
  $('#input').placeholder = name === 'chat' ? 'Pergunte qualquer coisa…' : 'Digite sua mensagem aqui…';
  measure();
  if (name === 'chat') { renderChatHeader(); renderMsgs(true); }
  if (name === 'convs') renderConvs();
  if (name === 'projects') { renderProject(); lsDir(fs.path); }
  if (name === 'settings') renderSettings();
  if (name === 'more') $('#more-ver').textContent = 'Versão ' + S.version;
  const v = $('#v-' + name); if (v && name !== 'chat') v.scrollTop = 0;
}
$$('#tabs button').forEach(b => b.addEventListener('click', () => go(b.dataset.tab)));

function measure() {
  const h = $('#composer').offsetHeight || 0;
  document.documentElement.style.setProperty('--comp', (document.body.classList.contains('comp') ? h + 16 : 0) + 'px');
}
let maxH = window.innerHeight;
window.addEventListener('resize', () => {
  maxH = Math.max(maxH, window.innerHeight);
  const kb = window.innerHeight < maxH - 140;
  document.body.classList.toggle('kb', kb);
  measure();
  if (kb && view === 'chat') scrollBottom(true);
});

/* ---------- cabeçalho, estado geral ---------- */
function srvText() {
  switch (S.server.state) {
    case 'READY': return ['Assistente pronto', 'ok'];
    case 'STARTING': return ['Preparando o assistente…', 'warn'];
    case 'FAILED': return ['Assistente indisponível', 'bad'];
    default: return ['Assistente parado', 'warn'];
  }
}
function folderText() {
  const f = S.conv.folder || { kind: 'none' };
  if (f.kind === 'saf') return '📁 ' + (f.name || 'Pasta do celular');
  if (f.kind === 'internal') return '📁 Pasta interna do app';
  return '📁 Escolha uma pasta';
}
function syncText() {
  const s = (S.conv.sync || {});
  if (s.state === 'syncing') return 'sincronizando…';
  if (s.state === 'error') return 'erro na sincronização';
  if (S.conv.folder && S.conv.folder.kind === 'saf') return s.last ? 'sincronizado ' + ago(s.last) : 'não sincronizado';
  return '';
}
function renderChrome() {
  $('#ver').textContent = 'v' + S.version;
  const nm = (S.user.name || 'U').trim();
  $('#avatar').textContent = (nm[0] || 'U').toUpperCase();
  $('#belldot').classList.toggle('on', S.notices.some(n => !n.read));
  document.body.classList.toggle('no-mascot', !S.mascot);
  const b = S.busy, bo = $('#busy');
  bo.classList.toggle('on', !!b);
  if (b) {
    $('#busy-title').textContent = b.title || 'Preparando';
    $('#busy-text').textContent = b.text || '';
    const bar = $('.bar', bo);
    $('#busy-retry').style.display = b.error ? 'inline-flex' : 'none';
    bar.style.display = b.error ? 'none' : 'block';
    if (b.pct == null || b.pct < 0) { bar.classList.add('ind'); $('#busy-pct').textContent = ''; }
    else { bar.classList.remove('ind'); $('#busy-bar').style.width = Math.round(b.pct * 100) + '%'; $('#busy-pct').textContent = Math.round(b.pct * 100) + '%'; }
  }
  const send = $('#send');
  send.classList.toggle('stop', !!S.gen.running);
  send.innerHTML = icon(S.gen.running ? 'stop' : 'send');
  send.setAttribute('aria-label', S.gen.running ? 'Parar' : 'Enviar');
  if (view === 'chat') renderChatHeader();
  if (view === 'convs') renderConvs();
  if (view === 'projects') renderProject();
  if (view === 'settings') renderSettings();
}
function renderChatHeader() {
  $('#chat-title').textContent = S.conv.name || 'Conversa';
  const f = $('#chat-folder'); f.textContent = folderText() + (syncText() ? ' · ' + syncText() : '');
  f.className = 'pill' + ((S.conv.sync || {}).state === 'error' ? ' bad' : '');
  const [t, k] = srvText(); const s = $('#chat-server'); s.textContent = t; s.className = 'pill srv ' + k;
}

/* ---------- Markdown / código ---------- */
const PURIFY = { FORBID_TAGS: ['style', 'form', 'input', 'button', 'textarea', 'select', 'iframe', 'object', 'embed', 'link', 'meta'], FORBID_ATTR: ['style', 'srcset'], ALLOW_DATA_ATTR: false };
function mdHtml(text) {
  let html;
  try { html = window.marked.parse(String(text || ''), { gfm: true, breaks: true, async: false }); }
  catch (e) { html = '<p>' + esc(text) + '</p>'; }
  return window.DOMPurify.sanitize(html, PURIFY);
}
function mdNode(text) {
  const d = el('div', 'md'); d.innerHTML = mdHtml(text);
  $$('a', d).forEach(a => { a.dataset.href = a.getAttribute('href') || ''; a.removeAttribute('href'); a.removeAttribute('target'); a.classList.add('lnk'); });
  $$('table', d).forEach(t => { const w = el('div', 'tbl'); t.parentNode.insertBefore(w, t); w.appendChild(t); });
  $$('pre', d).forEach(pre => {
    const code = $('code', pre); if (!code) return;
    const m = /language-([\w+#.-]+)/.exec(code.className || ''); const lang = m ? m[1] : '';
    try {
      if (lang && window.hljs.getLanguage(lang)) window.hljs.highlightElement(code);
      else if (code.textContent.length < 6000) window.hljs.highlightElement(code);
    } catch (e) { /* sem destaque */ }
    const wrap = el('div', 'code');
    const head = el('div', 'code-head', '<span>' + esc(lang || 'código') + '</span><button class="copy" type="button">' + icon('copy').replace('<svg', '<svg width="14" height="14"') + ' Copiar</button>');
    pre.parentNode.insertBefore(wrap, pre); wrap.appendChild(head); wrap.appendChild(pre);
  });
  return d;
}
document.addEventListener('click', e => {
  const cp = e.target.closest && e.target.closest('.code .copy');
  if (cp) {
    const code = $('code', cp.closest('.code')); const txt = code ? code.textContent : '';
    N.post({ a: 'copy', text: txt });
    cp.classList.add('done'); cp.innerHTML = icon('check').replace('<svg', '<svg width="14" height="14"') + ' Copiado';
    setTimeout(() => { cp.classList.remove('done'); cp.innerHTML = icon('copy').replace('<svg', '<svg width="14" height="14"') + ' Copiar'; }, 1800);
    return;
  }
  const a = e.target.closest && e.target.closest('.lnk');
  if (a) { e.preventDefault(); const u = a.dataset.href || ''; if (/^https?:\/\//i.test(u)) N.post({ a: 'open', url: u }); }
});

/* ---------- mensagens ---------- */
const msgsEl = $('#msgs');
let stick = true;
msgsEl.addEventListener('scroll', () => {
  stick = (msgsEl.scrollHeight - msgsEl.scrollTop - msgsEl.clientHeight) < 90;
  $('#jump').classList.toggle('on', !stick && msgs.length > 0);
});
function scrollBottom(force) {
  if (force || stick) { msgsEl.scrollTop = msgsEl.scrollHeight; stick = true; $('#jump').classList.remove('on'); }
  else $('#jump').classList.add('on');
}
$('#jump').addEventListener('click', () => scrollBottom(true));

function toolNode(b) {
  const t = el('div', 'tool ' + (b.status === 'ok' ? 'ok' : b.status === 'err' ? 'err' : 'run'));
  const st = b.status === 'run' ? '<span class="st"><i class="spin"></i></span>' : '<span class="st">' + icon(b.status === 'err' ? 'warn' : 'check') + '</span>';
  const head = el('button', 't-head', st + '<b>' + esc(b.label || b.name || 'Ferramenta') + '</b><span class="d">' + esc(b.detail || '') + '</span>');
  head.type = 'button';
  t.appendChild(head);
  if (b.output) { const pre = el('pre'); pre.textContent = b.output; t.appendChild(pre); head.addEventListener('click', () => t.classList.toggle('open')); }
  return t;
}
function errNode(b) {
  const txt = String(b.text || '');
  const prov = /50\d|40[1-3]|provider|provedor|api error|unavailable|connect|auth|key/i.test(txt);
  const c = el('div', 'errcard');
  c.innerHTML = '<div class="t">Não consegui concluir</div><div>' + esc(txt.slice(0, 600)) + '</div>';
  const btns = el('div', 'btns');
  if (prov) { const b1 = el('button', 'btn primary', 'Abrir provedores e APIs'); b1.addEventListener('click', () => N.post({ a: 'admin' })); btns.appendChild(b1); }
  const b2 = el('button', 'btn', 'Tentar de novo'); b2.addEventListener('click', retryLast); btns.appendChild(b2);
  c.appendChild(btns); return c;
}
function noteNode(m) {
  const n = el('div', 'note' + (m.noteKind === 'wait' ? ' wait' : ''), '<i class="spin"></i><span>' + esc(m.note) + '</span>');
  if (m.noteKind === 'retry') {
    const b = el('button', 'btn', 'Provedores'); b.style.marginLeft = 'auto'; b.addEventListener('click', () => N.post({ a: 'admin' })); n.appendChild(b);
  }
  return n;
}
function fillAssistant(col, m) {
  col.textContent = '';
  const bubble = el('div', 'bubble');
  (m.blocks || []).forEach(b => {
    if (b.kind === 'text') { if ((b.text || '').trim()) bubble.appendChild(mdNode(b.text)); }
    else if (b.kind === 'tool') bubble.appendChild(toolNode(b));
    else if (b.kind === 'error') bubble.appendChild(errNode(b));
  });
  if (m.note) bubble.appendChild(noteNode(m));
  if (m.streaming && !m.note) bubble.appendChild(el('div', 'dots', '<i></i><i></i><i></i>'));
  col.appendChild(bubble);
  if (!m.streaming) {
    const text = (m.blocks || []).filter(b => b.kind === 'text').map(b => b.text).join('\n\n');
    if (text.trim()) {
      const acts = el('div', 'acts'); const c = el('button', '', 'Copiar resposta');
      c.addEventListener('click', () => { N.post({ a: 'copy', text }); toast('Resposta copiada'); });
      acts.appendChild(c); col.appendChild(acts);
    }
  }
}
function fillUser(bubble, m) {
  bubble.textContent = '';
  (m.blocks || []).forEach(b => {
    if (b.kind === 'file') bubble.appendChild(el('span', 'fchip', icon('file').replace('<svg', '<svg width="14" height="14"') + esc(b.name || b.detail)));
  });
  const t = (m.blocks || []).filter(b => b.kind === 'text').map(b => b.text).join('\n');
  if (t) { const s = el('div'); s.textContent = t; bubble.appendChild(s); }
}
function buildMsgEl(m) {
  const w = el('div', 'msg ' + m.role); w.dataset.id = m.id;
  if (m.role === 'user') { const b = el('div', 'bubble'); fillUser(b, m); w.appendChild(b); }
  else {
    const av = el('img', 'av'); av.src = 'img/logo.svg'; av.alt = '';
    const col = el('div', 'col'); fillAssistant(col, m); w.appendChild(av); w.appendChild(col);
  }
  return w;
}
function renderMsgs(force) {
  msgsEl.textContent = '';
  msgs.forEach(m => msgsEl.appendChild(buildMsgEl(m)));
  $('#empty').classList.toggle('on', msgs.length === 0);
  msgsEl.style.display = msgs.length === 0 ? 'none' : 'flex';
  scrollBottom(force);
}
let raf = 0, dirty = {};
function updateMsgDom(m) {
  dirty[m.id] = m;
  if (raf) return;
  raf = requestAnimationFrame(() => {
    raf = 0;
    const items = dirty; dirty = {};
    Object.keys(items).forEach(id => {
      const m2 = items[id];
      let w = msgsEl.querySelector('.msg[data-id="' + CSS.escape(id) + '"]');
      if (!w) { w = buildMsgEl(m2); msgsEl.appendChild(w); }
      else if (m2.role === 'assistant') fillAssistant($('.col', w), m2);
      else fillUser($('.bubble', w), m2);
    });
    $('#empty').classList.toggle('on', msgs.length === 0);
    msgsEl.style.display = msgs.length === 0 ? 'none' : 'flex';
    scrollBottom(false);
  });
}

/* ---------- envio ---------- */
function autosize() { const t = $('#input'); t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 140) + 'px'; measure(); }
$('#input').addEventListener('input', autosize);
function renderPending() {
  const r = $('#attach-row'); r.textContent = '';
  pending.forEach((f, i) => {
    const c = el('span', 'achip', icon('file').replace('<svg', '<svg width="14" height="14"') + '<span>' + esc(f.name) + '</span>');
    const x = el('button', '', icon('x')); x.type = 'button'; x.addEventListener('click', () => { pending.splice(i, 1); renderPending(); });
    c.appendChild(x); r.appendChild(c);
  });
  measure();
}
function send(textOverride) {
  if (S.gen.running) return;
  const ta = $('#input');
  const text = String(textOverride != null ? textOverride : ta.value).trim();
  if (!text && !pending.length) return;
  draft = { text, files: pending.slice() };
  if (view !== 'chat') go('chat');
  N.post({ a: 'send', text, files: pending });
  if (textOverride == null) { ta.value = ''; autosize(); }
  pending = []; renderPending();
}
function retryLast() {
  const last = [...msgs].reverse().find(m => m.role === 'user');
  if (!last) return;
  const t = (last.blocks || []).filter(b => b.kind === 'text').map(b => b.text).join('\n');
  if (t) send(t);
}
$('#send').addEventListener('click', () => { if (S.gen.running) N.post({ a: 'stop' }); else send(); });
$('#attach').addEventListener('click', () => N.post({ a: 'attach' }));
$('#busy-retry').addEventListener('click', () => N.post({ a: 'retryExtract' }));

/* ---------- pasta / sugestões ---------- */
function askFolder() {
  openSheet({
    title: 'Onde guardar seus projetos?',
    html: '<p>Escolha uma pasta do celular. Você poderá abrir e editar os arquivos pelo gerenciador de arquivos.</p><p style="margin-top:8px">Sem escolher, os arquivos ficam escondidos dentro do app.</p>',
    actions: [
      { label: 'Escolher pasta do celular', kind: 'primary', icon: 'folder', onClick: () => N.post({ a: 'pickFolder' }) },
      { label: 'Usar pasta interna do app', onClick: () => N.post({ a: 'useInternal' }) },
      { label: 'Agora não' }
    ]
  });
}
function buildSugg(container) {
  container.textContent = '';
  SUGGESTIONS.forEach(s => {
    const b = el('button', '', '<span class="e">' + esc(s.e) + '</span><span>' + esc(s.t) + '</span>'); b.type = 'button';
    b.addEventListener('click', () => send(s.p)); container.appendChild(b);
  });
}

/* ---------- conversas ---------- */
function renderConvs() {
  const q = ($('#conv-search').value || '').toLowerCase();
  const list = $('#conv-list'); list.textContent = '';
  const items = S.convs.filter(c => !q || (c.name || '').toLowerCase().includes(q) || (c.preview || '').toLowerCase().includes(q));
  if (!items.length) { list.appendChild(el('div', 'empty-s', q ? 'Nenhuma conversa encontrada.' : 'Nenhuma conversa ainda.<br>Toque em + para começar.')); return; }
  items.forEach(c => {
    const b = el('button', 'citem' + (c.id === S.conv.id ? ' active' : ''),
      '<span class="ci">' + icon(c.folderKind === 'saf' ? 'folder' : 'msgs') + '</span><span class="ct"><b>' + esc(c.name) + '</b><span>' + esc(c.preview || 'Sem mensagens') + '</span></span><span class="cm">' + esc(ago(c.ts)) + '</span>');
    b.type = 'button';
    let timer = 0;
    b.addEventListener('click', () => { N.post({ a: 'openConv', id: c.id }); go('chat'); });
    b.addEventListener('touchstart', () => { timer = setTimeout(() => { timer = 0; convMenu(c); }, 520); }, { passive: true });
    ['touchend', 'touchmove', 'touchcancel'].forEach(ev => b.addEventListener(ev, () => { if (timer) clearTimeout(timer); timer = 0; }, { passive: true }));
    b.addEventListener('contextmenu', e => { e.preventDefault(); convMenu(c); });
    list.appendChild(b);
  });
}
function convMenu(c) {
  openSheet({
    title: c.name, actions: [
      { label: 'Abrir', icon: 'msgs', onClick: () => { N.post({ a: 'openConv', id: c.id }); go('chat'); } },
      { label: 'Renomear', icon: 'edit', onClick: () => promptSheet('Renomear conversa', c.name, 'Salvar', v => v.trim() && N.post({ a: 'renameConv', id: c.id, name: v.trim() })) },
      { label: 'Excluir', kind: 'danger', icon: 'trash', onClick: () => confirmSheet('Excluir conversa?', 'O histórico desta conversa será apagado. Os arquivos da pasta não são removidos.', 'Excluir', () => N.post({ a: 'deleteConv', id: c.id })) },
      { label: 'Fechar' }]
  });
}
$('#conv-search').addEventListener('input', renderConvs);
$('#conv-new').addEventListener('click', () => N.post({ a: 'newConv' }));

/* ---------- projetos ---------- */
function renderProject() {
  const f = S.conv.folder || { kind: 'none' }, s = S.conv.sync || {};
  const card = $('#proj-card');
  const title = f.kind === 'saf' ? (f.name || 'Pasta do celular') : f.kind === 'internal' ? 'Pasta interna do app' : 'Nenhuma pasta escolhida';
  const sub = f.kind === 'saf'
    ? (s.state === 'syncing' ? 'Sincronizando com o Linux do app…' : s.state === 'error' ? 'Erro na última sincronização. Veja o relatório.' : s.last ? 'Sincronizado ' + ago(s.last) + (s.summary ? ' · ' + s.summary : '') : 'Ainda não sincronizado')
    : f.kind === 'internal' ? 'Os arquivos ficam só dentro do app. Escolha uma pasta do celular para acessá-los.'
      : 'Escolha onde guardar os projetos desta conversa.';
  card.innerHTML = '<div class="pc"><span class="ico c-folder">' + icon('folder') + '</span><div class="pt"><b>' + esc(title) + '</b><span>' + esc(sub) + '</span></div></div>';
  const btns = el('div', 'btns');
  const add = (label, cls, fn) => { const b = el('button', 'btn ' + cls, label); b.type = 'button'; b.addEventListener('click', fn); btns.appendChild(b); };
  add(f.kind === 'saf' ? 'Alterar pasta' : 'Escolher pasta', 'primary', () => N.post({ a: 'pickFolder' }));
  if (f.kind === 'saf') { add('Sincronizar agora', '', () => N.post({ a: 'syncNow' })); add('Relatório', '', () => N.post({ a: 'report' })); add('Usar só a interna', '', () => N.post({ a: 'useInternal' })); }
  card.appendChild(btns);
  renderFs();
}
function lsDir(path) { fs.loading = true; fs.path = path || ''; renderFs(); N.post({ a: 'ls', path: fs.path }); }
function renderFs() {
  const crumbs = $('#fs-crumbs'); crumbs.textContent = '';
  const root = el('button', '', 'projeto'); root.type = 'button'; root.addEventListener('click', () => lsDir('')); crumbs.appendChild(root);
  const parts = fs.path ? fs.path.split('/') : [];
  parts.forEach((p, i) => { crumbs.appendChild(document.createTextNode('›')); const b = el('button', '', esc(p)); b.type = 'button'; b.addEventListener('click', () => lsDir(parts.slice(0, i + 1).join('/'))); crumbs.appendChild(b); });
  const list = $('#fs-list'); list.textContent = '';
  if (fs.loading) { list.appendChild(el('div', 'empty-s', 'Carregando…')); return; }
  if (fs.error) { list.appendChild(el('div', 'empty-s', esc(fs.error))); return; }
  if (!fs.entries.length) { list.appendChild(el('div', 'empty-s', 'Esta pasta está vazia.<br>Peça à IA para criar um projeto!')); return; }
  fs.entries.forEach(e => {
    const r = el('button', 'frow', '<span class="fi ' + (e.dir ? 'dir' : '') + '">' + icon(e.dir ? 'folder' : 'file') + '</span><span class="ft"><b>' + esc(e.name) + '</b><span>' + (e.dir ? 'pasta' : sizeFmt(e.size || 0)) + ' · ' + esc(ago(e.mtime)) + '</span></span>');
    r.type = 'button';
    r.addEventListener('click', () => { const p = (fs.path ? fs.path + '/' : '') + e.name; if (e.dir) lsDir(p); else N.post({ a: 'cat', path: p }); });
    list.appendChild(r);
  });
}
$('#fs-refresh').addEventListener('click', () => lsDir(fs.path));

/* ---------- configurações ---------- */
function renderSettings() {
  const seg = $('#mode-seg'); seg.textContent = '';
  const cur = (S.settings || {}).mode || 'auto';
  MODES.forEach(m => { const b = el('button', cur === m.id ? 'on' : '', esc(m.t)); b.type = 'button'; b.addEventListener('click', () => N.post({ a: 'setting', key: 'mode', value: m.id })); seg.appendChild(b); });
  $('#mode-desc').textContent = (MODES.find(m => m.id === cur) || MODES[0]).d;
  const [t, k] = srvText();
  $('#srv-title').textContent = t;
  $('#srv-sub').textContent = S.server.state === 'READY' ? 'Funcionando em segundo plano neste aparelho' : (S.server.msg || 'Aguarde alguns instantes');
  $('#srv-dot').className = 'ri ' + (k === 'ok' ? 'c-prov' : k === 'bad' ? 'c-login' : 'c-folder');
  $('#sw-mascot').checked = !!S.mascot;
}
$('#sw-mascot').addEventListener('change', e => N.post({ a: 'setting', key: 'mascot', value: e.target.checked }));

/* ---------- ações dos botões data-act ---------- */
function act(a) {
  switch (a) {
    case 'newConv': N.post({ a: 'newConv' }); break;
    case 'pickFolder': N.post({ a: 'pickFolder' }); break;
    case 'admin': N.post({ a: 'admin' }); break;
    case 'login': N.post({ a: 'admin', focus: 'oauth' }); toast('Em “Provedores”, escolha ChatGPT e toque em Conectar.'); break;
    case 'restartServer': N.post({ a: 'restartServer' }); toast('Reiniciando o assistente…'); break;
    case 'terminal': confirmSheet('Abrir o terminal?', 'É uma área técnica, só para diagnóstico. Você não precisa dela para conversar com a IA.', 'Abrir terminal', () => N.post({ a: 'terminal' })); break;
    case 'serverLog': N.post({ a: 'serverLog' }); break;
    case 'report': N.post({ a: 'report' }); break;
    case 'profile': promptSheet('Seu nome', S.user.name, 'Salvar', v => N.post({ a: 'profile', name: v.trim() })); break;
    case 'notices': showNotices(); break;
  }
}
document.addEventListener('click', e => { const b = e.target.closest && e.target.closest('[data-act]'); if (b) act(b.dataset.act); });
$('#avatar').addEventListener('click', () => act('profile'));
$('#bell').addEventListener('click', showNotices);
function showNotices() {
  const list = S.notices.slice().reverse();
  const html = list.length ? list.map(n => '<div class="nitem"><b>' + esc(n.text) + '</b><span>' + esc(ago(n.ts)) + '</span></div>').join('') : '<p>Nenhuma notificação por aqui.</p>';
  openSheet({ title: 'Notificações', html, actions: [list.length ? { label: 'Limpar', onClick: () => N.post({ a: 'clearNotices' }) } : null, { label: 'Fechar' }].filter(Boolean) });
  N.post({ a: 'readNotices' });
}
$('#chat-back').addEventListener('click', () => go('convs'));
$('#chat-title-wrap').addEventListener('click', () => promptSheet('Renomear conversa', S.conv.name, 'Salvar', v => v.trim() && N.post({ a: 'renameConv', id: S.conv.id, name: v.trim() })));
$('#chat-menu').addEventListener('click', () => openSheet({
  title: S.conv.name, actions: [
    { label: 'Nova conversa', icon: 'plus', onClick: () => N.post({ a: 'newConv' }) },
    { label: 'Pasta e arquivos', icon: 'folder', onClick: () => go('projects') },
    { label: 'Sincronizar agora', icon: 'refresh', onClick: () => N.post({ a: 'syncNow' }) },
    { label: 'Excluir conversa', kind: 'danger', icon: 'trash', onClick: () => confirmSheet('Excluir conversa?', 'O histórico será apagado. Os arquivos da pasta não são removidos.', 'Excluir', () => N.post({ a: 'deleteConv', id: S.conv.id })) },
    { label: 'Fechar' }]
}));

/* ---------- API chamada pelo Android ---------- */
window.FCC = {
  state(p) {
    const prevFolder = (S.conv.folder || {}).kind, prevId = S.conv.id;
    Object.assign(S, p);
    renderChrome();
    if (S.conv.id && S.conv.id !== prevId) { askedFolder[S.conv.id] = askedFolder[S.conv.id] || false; }
    if (S.conv.id && (S.conv.folder || {}).kind === 'none' && !askedFolder[S.conv.id] && !S.busy && !$('#sheet-bg').classList.contains('on')) {
      askedFolder[S.conv.id] = true; askFolder();
    }
    if (prevFolder !== (S.conv.folder || {}).kind && view === 'projects') lsDir(fs.path);
  },
  messages(convId, arr) { if (convId !== S.conv.id) return; msgs = arr || []; renderMsgs(true); },
  msg(convId, m) {
    if (convId !== S.conv.id) return;
    const i = msgs.findIndex(x => x.id === m.id);
    if (i < 0) msgs.push(m); else msgs[i] = m;
    updateMsgDom(m);
  },
  toast,
  needFolder() {
    if (draft) { $('#input').value = draft.text; autosize(); pending = draft.files || []; renderPending(); }
    askFolder();
  },
  attached(files) { (files || []).forEach(f => pending.push(f)); renderPending(); },
  ls(path, data) { if (path !== fs.path) return; fs.loading = false; fs.error = data.error || ''; fs.entries = data.entries || []; renderFs(); },
  cat(path, data) {
    const name = path.split('/').pop();
    if (data.error) { toast(data.error); return; }
    const pre = el('pre'); pre.textContent = data.text + (data.truncated ? '\n\n… (arquivo cortado)' : '');
    openSheet({ title: name, node: pre, actions: [{ label: 'Copiar conteúdo', icon: 'copy', keep: true, onClick: () => { N.post({ a: 'copy', text: data.text }); toast('Copiado'); } }, { label: 'Fechar' }] });
  },
  report(text) { const pre = el('pre'); pre.textContent = text; openSheet({ title: 'Relatório da sincronização', node: pre, actions: [{ label: 'Fechar' }] }); },
  log(title, text) { const pre = el('pre'); pre.textContent = text; openSheet({ title, node: pre, actions: [{ label: 'Fechar' }] }); },
  back() {
    if ($('#sheet-bg').classList.contains('on')) { closeSheet(); return true; }
    if (S.busy) return true;
    if (view === 'chat') { go('convs'); return true; }
    if (view !== 'home') { go('home'); return true; }
    return false;
  },
  go(name) { go(name); },
};

/* ---------- inicialização ---------- */
paintIcons(document);
buildSugg($('#sugg-home')); buildSugg($('#sugg-empty'));
go('home');
renderPending();
autosize();
N.post({ a: 'ready' });
})();

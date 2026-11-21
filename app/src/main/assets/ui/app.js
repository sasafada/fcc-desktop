(function () {
  'use strict';

  /* ======================================================================
   * FCC AI — Interface (frontend). O backend (FCC Server + Claude Code)
   * roda no Linux interno e conversa com esta tela pela ponte "AndroidBridge".
   * ====================================================================== */

  const $ = (s, el) => (el || document).querySelector(s);
  const $$ = (s, el) => Array.from((el || document).querySelectorAll(s));

  /* ---------- Utilidades ---------- */
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&', '<': '<', '>': '>', '"': '"', "'": ''' }[c]));
  const ago = (ts) => {
    if (!ts) return '';
    const d = (Date.now() - ts) / 1000;
    if (d < 45) return 'agora';
    if (d < 3600) return Math.round(d / 60) + ' min';
    if (d < 86400) return Math.round(d / 3600) + ' h';
    if (d < 172800) return 'ontem';
    return new Date(ts).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
  };
  const sizeFmt = (n) => { if (n < 1024) return n + ' B'; if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'; return (n / 1048576).toFixed(1) + ' MB'; };
  const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };

  /* ---------- Toast ---------- */
  function toast(t, type = 'info') {
    const container = $('#toast-container');
    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.textContent = t;
    container.appendChild(el);
    setTimeout(() => { el.style.animation = 'slideDown 0.2s var(--ease-in) forwards'; setTimeout(() => el.remove(), 200); }, 3000);
  }

  /* ---------- Sheet (Bottom Sheet) ---------- */
  function openSheet(o) {
    $('#sheet-title').textContent = o.title || '';
    const body = $('#sheet-body'); body.innerHTML = '';
    if (o.html != null) body.innerHTML = o.html; else if (o.node) body.appendChild(o.node);
    const acts = $('#sheet-actions'); acts.innerHTML = '';
    (o.actions || []).forEach(a => {
      const b = el('button', 'btn ' + (a.kind || '') + ' block', (a.icon ? '<svg class="icon icon-sm"><use href="#' + a.icon + '"></use></svg>' : '') + esc(a.label));
      b.addEventListener('click', () => { if (a.keep !== true) closeSheet(); if (a.onClick) a.onClick(); });
      acts.appendChild(b);
    });
    $('#sheet-backdrop').classList.add('on');
    if (o.focus) setTimeout(() => { const i = $(o.focus); if (i) { i.focus(); i.select && i.select(); } }, 120);
  }
  function closeSheet() { $('#sheet-backdrop').classList.remove('on'); }
  $('#sheet-backdrop').addEventListener('click', e => { if (e.target.id === 'sheet-bg') closeSheet(); });

  function promptSheet(title, value, okLabel, cb) {
    openSheet({
      title, html: '<input type="text" id="p-input" maxlength="80" class="input">', focus: '#p-input',
      actions: [{ label: okLabel, kind: 'primary', onClick: () => cb(($('#p-input') || {}).value || '') }, { label: 'Cancelar' }]
    });
    const i = $('#p-input'); i.value = value || '';
    i.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); const v = i.value; closeSheet(); cb(v); } });
  }
  function confirmSheet(title, text, okLabel, cb) {
    openSheet({ title, html: '<p>' + esc(text) + '</p>', actions: [{ label: okLabel, kind: 'danger', onClick: cb }, { label: 'Cancelar' }] });
  }

  /* ---------- Ponte com o Android ---------- */
  window.__posts = [];
  const N = {
    post(o) {
      window.__posts.push(o);
      try { window.AndroidBridge.post(JSON.stringify(o)); } catch (e) { /* sem Android (teste em navegador) */ }
    }
  };

  /* ---------- Estado ---------- */
  const S = {
    version: '1.0', busy: null, user: { name: '' }, mascot: true,
    conv: { id: '', name: 'Nova conversa', folder: { kind: 'none', name: '' }, sync: { state: 'idle', last: 0, summary: '' } },
    convs: [], server: { state: 'starting', port: 0, msg: '' }, gen: { running: false, convId: '' },
    notices: [], settings: { mode: 'auto', theme: 'dark' },
  };
  let msgs = [];
  let view = 'chat';
  let pending = [];
  let draft = null;
  let fs = { path: '', entries: [], loading: false, error: '' };
  let askedFolder = {};

  const MODES = [
    { id: 'auto', t: 'Automático', d: 'Recomendado: a IA edita arquivos e roda comandos, bloqueando ações arriscadas.' },
    { id: 'bypassPermissions', t: 'Total', d: 'A IA executa tudo sem pedir confirmação. Use com atenção.' },
    { id: 'acceptEdits', t: 'Cautelosa', d: 'A IA só edita arquivos; comandos de terminal ficam bloqueados.' },
  ];

  /* ---------- Utilidades DOM ---------- */
  function measure() {
    const h = $('#composer').offsetHeight || 0;
    document.documentElement.style.setProperty('--comp', (document.body.classList.contains('comp') ? h + 16 : 0) + 'px');
  }

  /* ---------- Chrome (estado geral) ---------- */
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
    const b = S.busy, bo = $('#busy-overlay');
    bo.hidden = !b;
    if (b) {
      $('#busy-title').textContent = b.title || 'Preparando';
      $('#busy-text').textContent = b.text || '';
      const bar = $('.busy-bar', bo);
      $('#busy-retry').hidden = !b.error;
      bar.style.display = b.error ? 'none' : 'block';
      if (b.pct == null || b.pct < 0) { bar.classList.add('ind'); $('#busy-pct').textContent = ''; }
      else { bar.classList.remove('ind'); $('#busy-bar').style.width = Math.round(b.pct * 100) + '%'; $('#busy-pct').textContent = Math.round(b.pct * 100) + '%'; }
    }
    const send = $('#send-btn');
    send.classList.toggle('sending', !!S.gen.running);
    if (view === 'chat') renderChatHeader();
    if (view === 'convs') renderConvs();
    if (view === 'projects') renderProject();
    if (view === 'settings') renderSettings();
    if (view === 'skills') renderSkills();
    if (view === 'plugins') renderPlugins();
    if (view === 'about') renderAbout();
  }
  function renderChatHeader() {
    $('#chat-title').textContent = S.conv.name || 'Conversa';
    const f = $('#chat-folder'); f.textContent = folderText() + (syncText() ? ' · ' + syncText() : '');
    f.className = 'pill' + ((S.conv.sync || {}).state === 'error' ? ' bad' : '');
    const [t, k] = srvText(); const s = $('#chat-server'); s.textContent = t; s.className = 'pill srv ' + k;
  }

  /* ---------- Markdown / Código ---------- */
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
      const head = el('div', 'code-head', '<span>' + esc(lang || 'código') + '</span><button class="btn btn-ghost btn-sm copy" type="button"><svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> Copiar</button>');
      pre.parentNode.insertBefore(wrap, pre); wrap.appendChild(head); wrap.appendChild(pre);
    });
    return d;
  }
  document.addEventListener('click', e => {
    const cp = e.target.closest && e.target.closest('.code .copy');
    if (cp) {
      const code = $('code', cp.closest('.code')); const txt = code ? code.textContent : '';
      N.post({ a: 'copy', text: txt });
      cp.classList.add('done'); cp.innerHTML = '<svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg> Copiado';
      setTimeout(() => { cp.classList.remove('done'); cp.innerHTML = '<svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> Copiar'; }, 1800);
      return;
    }
    const a = e.target.closest && e.target.closest('.lnk');
    if (a) { e.preventDefault(); const u = a.dataset.href || ''; if (/^https?:\/\//i.test(u)) N.post({ a: 'open', url: u }); }
  });

  /* ---------- Mensagens ---------- */
  const msgsEl = $('#messages');
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
    const st = b.status === 'run' ? '<span class="st"><i class="spinner spinner-sm"></i></span>' : '<span class="st"><svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + (b.status === 'err' ? '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>' : '<polyline points="20 6 9 17 4 12"/>') + '</svg></span>';
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
    if (prov) { const b1 = el('button', 'btn btn-primary', 'Abrir provedores'); b1.addEventListener('click', () => N.post({ a: 'admin' })); btns.appendChild(b1); }
    const b2 = el('button', 'btn', 'Tentar de novo'); b2.addEventListener('click', retryLast); btns.appendChild(b2);
    c.appendChild(btns); return c;
  }
  function noteNode(m) {
    const n = el('div', 'note' + (m.noteKind === 'wait' ? ' wait' : ''), '<i class="spinner spinner-sm"></i><span>' + esc(m.note) + '</span>');
    if (m.noteKind === 'retry') {
      const b = el('button', 'btn btn-ghost', 'Provedores'); b.style.marginLeft = 'auto'; b.addEventListener('click', () => N.post({ a: 'admin' })); n.appendChild(b);
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
        const acts = el('div', 'acts'); const c = el('button', 'btn btn-ghost btn-sm', 'Copiar resposta');
        c.addEventListener('click', () => { N.post({ a: 'copy', text }); toast('Resposta copiada'); });
        acts.appendChild(c); col.appendChild(acts);
      }
    }
  }
  function fillUser(bubble, m) {
    bubble.textContent = '';
    (m.blocks || []).forEach(b => {
      if (b.kind === 'file') bubble.appendChild(el('span', 'fchip', '<svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg>' + esc(b.name || b.detail)));
    });
    const t = (m.blocks || []).filter(b => b.kind === 'text').map(b => b.text).join('\n');
    if (t) { const s = el('div'); s.textContent = t; bubble.appendChild(s); }
  }
  function buildMsgEl(m) {
    const w = el('div', 'msg ' + m.role); w.dataset.id = m.id;
    if (m.role === 'user') { const b = el('div', 'bubble'); fillUser(b, m); w.appendChild(b); }
    else {
      const av = el('div', 'av'); av.textContent = '⚡';
      const col = el('div', 'col'); fillAssistant(col, m); w.appendChild(av); w.appendChild(col);
    }
    return w;
  }
  function renderMsgs(force) {
    msgsEl.textContent = '';
    msgs.forEach(m => msgsEl.appendChild(buildMsgEl(m)));
    $('#empty-state').classList.toggle('hidden', msgs.length > 0);
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
      $('#empty-state').classList.toggle('hidden', msgs.length > 0);
      msgsEl.style.display = msgs.length === 0 ? 'none' : 'flex';
      scrollBottom(false);
    });
  }

  /* ---------- Envio ---------- */
  function autosize() { const t = $('#input'); t.style.height = 'auto'; t.style.height = Math.min(t.scrollHeight, 140) + 'px'; measure(); }
  $('#input').addEventListener('input', autosize);
  function renderPending() {
    const r = $('#attachments-row'); r.textContent = '';
    pending.forEach((f, i) => {
      const c = el('span', 'achip', '<svg class="icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><polyline points="13 2 13 9 20 9"/></svg><span>' + esc(f.name) + '</span>');
      const x = el('button', '', '<svg class="icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>'); x.type = 'button'; x.addEventListener('click', () => { pending.splice(i, 1); renderPending(); });
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
  $('#send-btn').addEventListener('click', () => { if (S.gen.running) N.post({ a: 'stop' }); else send(); });
  $('#attach-btn').addEventListener('click', () => N.post({ a: 'attach' }));
  $('#busy-retry').addEventListener('click', () => N.post({ a: 'retryExtract' }));

  /* ---------- Pasta / Sugestões ---------- */
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

  /* ---------- Conversas ---------- */
  function renderConvs() {
    const q = ($('#conv-search').value || '').toLowerCase();
    const list = $('#conv-list'); list.textContent = '';
    const items = S.convs.filter(c => !q || (c.name || '').toLowerCase().includes(q) || (c.preview || '').toLowerCase().includes(q));
    if (!items.length) { list.appendChild(el('div', 'empty-s', q ? 'Nenhuma conversa encontrada.' : 'Nenhuma conversa ainda.<br>Toque em + para começar.')); return; }
    items.forEach(c => {
      const b = el('button', 'citem' + (c.id === S.conv.id ? ' active' : ''),
        '<span class="ci"><svg class="icon icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z"/></svg></span><span class="ct"><b>' + esc(c.name) + '</b><span>' + esc(c.preview || 'Sem mensagens') + '</span></span><span class="cm">' + esc(ago(c.ts)) + '</span>');
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

  /* ---------- Projetos ---------- */
  function renderProject() {
    const f = S.conv.folder || { kind: 'none' }, s = S.conv.sync || {};
    const card = $('#proj-card');
    const title = f.kind === 'saf' ? (f.name || 'Pasta do celular') : f.kind === 'internal' ? 'Pasta interna do app' : 'Nenhuma pasta escolhida';
    const sub = f.kind === 'saf'
      ? (s.state === 'syncing' ? 'Sincronizando com o Linux do app…' : s.state === 'error' ? 'Erro na última sincronização. Veja o relatório.' : s.last ? 'Sincronizado ' + ago(s.last) + (s.summary ? ' · ' + s.summary : '') : 'Ainda não sincronizado')
      : f.kind === 'internal' ? 'Os arquivos ficam só dentro do app. Escolha uma pasta do celular para acessá-los.'
        : 'Escolha onde guardar os projetos desta conversa.';
    card.innerHTML = '<div class="pc"><span class="ico c-folder"><svg class="icon icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg></span><div class="pt"><b>' + esc(title) + '</b><span>' + esc(sub) + '</span></div></div>';
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
      const r = el('button', 'frow', '<span class="fi ' + (e.dir ? 'dir' : '') + '"><svg class="icon icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg></span><span class="ft"><b>' + esc(e.name) + '</b><span>' + (e.dir ? 'pasta' : sizeFmt(e.size || 0)) + ' · ' + esc(ago(e.mtime)) + '</span></span>');
      r.type = 'button';
      r.addEventListener('click', () => { const p = (fs.path ? fs.path + '/' : '') + e.name; if (e.dir) lsDir(p); else N.post({ a: 'cat', path: p }); });
      list.appendChild(r);
    });
  }
  $('#fs-refresh').addEventListener('click', () => lsDir(fs.path));

  /* ---------- Skills ---------- */
  function renderSkills() {
    const container = $('#skills-list'); container.textContent = '';
    // Placeholder - será preenchido via backend
    container.appendChild(el('div', 'empty-s', 'Carregando skills...'));
    N.post({ a: 'listSkills' });
  }

  /* ---------- Plugins ---------- */
  function renderPlugins() {
    const container = $('#plugins-list'); container.textContent = '';
    container.appendChild(el('div', 'empty-s', 'Carregando plugins...'));
    N.post({ a: 'listPlugins' });
  }

  /* ---------- About ---------- */
  function renderAbout() {
    $('#about-version').textContent = 'Versão ' + S.version;
  }

  /* ---------- Configurações ---------- */
  function renderSettings() {
    const seg = $('#mode-seg'); seg.textContent = '';
    const cur = (S.settings || {}).mode || 'auto';
    MODES.forEach(m => { const b = el('button', cur === m.id ? 'on' : '', esc(m.t)); b.type = 'button'; b.addEventListener('click', () => N.post({ a: 'setting', key: 'mode', value: m.id })); seg.appendChild(b); });
    $('#mode-desc').textContent = (MODES.find(m => m.id === cur) || MODES[0]).d;
    const [t, k] = srvText();
    $('#srv-title').textContent = t;
    $('#srv-sub').textContent = S.server.state === 'READY' ? 'Funcionando em segundo plano neste aparelho' : (S.server.msg || 'Aguarde alguns instantes');
    $('#srv-dot').className = 'ri ' + (k === 'ok' ? 'c-prov' : k === 'bad' ? 'c-login' : 'c-folder');
    $('#theme-dark').checked = (S.settings.theme || 'dark') === 'dark';
    $('#theme-light').checked = (S.settings.theme || 'dark') === 'light';
  }
  $('#theme-dark').addEventListener('change', e => { if (e.target.checked) N.post({ a: 'setting', key: 'theme', value: 'dark' }); });
  $('#theme-light').addEventListener('change', e => { if (e.target.checked) N.post({ a: 'setting', key: 'theme', value: 'light' }); });
  $('#sw-mascot').addEventListener('change', e => N.post({ a: 'setting', key: 'mascot', value: e.target.checked }));

  /* ---------- Ações dos botões data-act ---------- */
  function act(a) {
    switch (a) {
      case 'newChat': N.post({ a: 'newConv' }); go('chat'); break;
      case 'projects': go('projects'); break;
      case 'skills': go('skills'); break;
      case 'plugins': go('plugins'); break;
      case 'admin': N.post({ a: 'admin' }); break;
      case 'login': N.post({ a: 'admin', focus: 'oauth' }); toast('Em "Provedores", escolha o provedor e toque em Conectar.'); break;
      case 'restartServer': N.post({ a: 'restartServer' }); toast('Reiniciando o assistente…'); break;
      case 'terminal': confirmSheet('Abrir o terminal?', 'É uma área técnica, só para diagnóstico. Você não precisa dela para conversar com a IA.', 'Abrir terminal', () => N.post({ a: 'terminal' })); break;
      case 'serverLog': N.post({ a: 'serverLog' }); break;
      case 'report': N.post({ a: 'report' }); break;
      case 'profile': promptSheet('Seu nome', S.user.name, 'Salvar', v => N.post({ a: 'profile', name: v.trim() })); break;
      case 'notices': showNotices(); break;
      case 'personalize': go('settings'); closeUserMenu(); break;
      case 'providers': N.post({ a: 'admin', focus: 'providers' }); closeUserMenu(); break;
      case 'exit': confirmSheet('Sair do aplicativo?', 'Isso encerrará completamente o aplicativo, incluindo o servidor local e processos em segundo plano.', 'Sair', () => N.post({ a: 'exit' })); closeUserMenu(); break;
      case 'about': go('about'); break;
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
      { label: 'Projetos e arquivos', icon: 'folder', onClick: () => go('projects') },
      { label: 'Sincronizar agora', icon: 'refresh', onClick: () => N.post({ a: 'syncNow' }) },
      { label: 'Excluir conversa', kind: 'danger', icon: 'trash', onClick: () => confirmSheet('Excluir conversa?', 'O histórico será apagado. Os arquivos da pasta não são removidos.', 'Excluir', () => N.post({ a: 'deleteConv', id: S.conv.id })) },
      { label: 'Fechar' }]
  }));

  /* ---------- Sidebar ---------- */
  const sidebar = $('#sidebar');
  const sidebarOverlay = $('#sidebar-overlay');
  let sidebarOpen = false;
  function toggleSidebar() {
    sidebarOpen = !sidebarOpen;
    sidebar.classList.toggle('open', sidebarOpen);
    sidebarOverlay.classList.toggle('open', sidebarOpen);
    document.body.style.overflow = sidebarOpen ? 'hidden' : '';
  }
  function closeSidebar() {
    sidebarOpen = false;
    sidebar.classList.remove('open');
    sidebarOverlay.classList.remove('open');
    document.body.style.overflow = '';
  }
  $('#sidebar-toggle').addEventListener('click', toggleSidebar);
  sidebarOverlay.addEventListener('click', closeSidebar);
  $$('.sidebar-item').forEach(btn => {
    btn.addEventListener('click', () => { if (btn.dataset.act) act(btn.dataset.act); closeSidebar(); });
  });

  /* ---------- User Menu ---------- */
  const userMenu = $('#user-menu');
  const userMenuTrigger = $('#user-menu-trigger');
  const userMenuBtn = $('#user-menu-btn');
  let userMenuOpen = false;
  function toggleUserMenu() {
    userMenuOpen = !userMenuOpen;
    userMenu.classList.toggle('open', userMenuOpen);
  }
  function closeUserMenu() {
    userMenuOpen = false;
    userMenu.classList.remove('open');
  }
  [userMenuTrigger, userMenuBtn].forEach(btn => btn?.addEventListener('click', e => { e.stopPropagation(); toggleUserMenu(); }));
  document.addEventListener('click', () => closeUserMenu());
  $$('#user-menu .dropdown-item').forEach(btn => {
    btn.addEventListener('click', () => { if (btn.dataset.act) act(btn.dataset.act); });
  });

  /* ---------- Navegação ---------- */
  function go(name) {
    view = name;
    $$('.view').forEach(v => v.classList.toggle('active', v.id === 'v-' + name));
    const comp = name === 'chat';
    document.body.classList.toggle('comp', comp);
    document.body.classList.toggle('nocomp', !comp);
    $('#input').placeholder = name === 'chat' ? 'Pergunte qualquer coisa…' : 'Digite sua mensagem aqui…';
    measure();
    if (name === 'chat') { renderChatHeader(); renderMsgs(true); }
    if (name === 'convs') renderConvs();
    if (name === 'projects') { renderProject(); lsDir(fs.path); }
    if (name === 'settings') renderSettings();
    if (name === 'skills') renderSkills();
    if (name === 'plugins') renderPlugins();
    if (name === 'about') renderAbout();
    if (name !== 'chat') closeSidebar();
    const v = $('#v-' + name); if (v && name !== 'chat') v.scrollTop = 0;
  }
  $$('.sidebar-item[data-act]').forEach(btn => {
    btn.addEventListener('click', () => { if (btn.dataset.act) { if (btn.dataset.act.startsWith('go-')) go(btn.dataset.act.slice(3)); else act(btn.dataset.act); } });
  });

  /* ---------- API chamada pelo Android ---------- */
  window.FCC = {
    state(p) {
      const prevFolder = (S.conv.folder || {}).kind, prevId = S.conv.id;
      Object.assign(S, p);
      renderChrome();
      if (S.conv.id && S.conv.id !== prevId) { askedFolder[S.conv.id] = askedFolder[S.conv.id] || false; }
      if (S.conv.id && (S.conv.folder || {}).kind === 'none' && !askedFolder[S.conv.id] && !S.busy && !$('#sheet-backdrop').classList.contains('on')) {
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
      if ($('#sheet-backdrop').classList.contains('on')) { closeSheet(); return true; }
      if (S.busy) return true;
      if (view === 'chat') { go('convs'); return true; }
      if (view !== 'chat') { go('chat'); return true; }
      return false;
    },
    go(name) { go(name); },
  };

  /* ---------- Inicialização ---------- */
  paintIcons(document);
  buildSugg($('#sugg-home')); buildSugg($('#sugg-empty'));
  go('chat');
  renderPending();
  autosize();
  N.post({ a: 'ready' });
})();
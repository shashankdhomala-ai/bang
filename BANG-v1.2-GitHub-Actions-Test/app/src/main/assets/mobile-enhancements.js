(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  let toastTimer;

  function toast(message) {
    let el = $('bang-mobile-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'bang-mobile-toast';
      el.className = 'bang-mobile-toast';
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2400);
  }

  const style = document.createElement('style');
  style.textContent = `
    * { -webkit-tap-highlight-color: transparent; }
    button { touch-action: manipulation; }
    button:active { transform: scale(.97); }
    .bang-mobile-nav { display:none; }
    .bang-mobile-toast { position:fixed; left:50%; bottom:94px; z-index:10000; max-width:calc(100% - 30px); padding:12px 16px; border-radius:14px; color:#fff; background:#202943; box-shadow:0 12px 35px rgba(0,0,0,.4); transform:translate(-50%,18px); opacity:0; pointer-events:none; transition:.2s; text-align:center; font-size:14px; }
    .bang-mobile-toast.show { transform:translate(-50%,0); opacity:1; }
    .bang-home-grid { display:grid; grid-template-columns:repeat(3,1fr); gap:12px; padding:18px; }
    .bang-home-card { min-height:105px; border:1px solid #ffffff12; border-radius:18px; background:#ffffff08; color:#fff; padding:16px; text-align:left; font-weight:800; }
    .bang-home-card span { display:block; font-size:28px; margin-bottom:10px; }
    .bang-chat-back { border:0; background:transparent; color:#fff; font-size:24px; padding:5px 10px 5px 0; }
    .bang-chat-title { display:flex; align-items:center; gap:8px; }
    .bang-chat-tools { display:flex; align-items:center; gap:5px; }
    .bang-icon-btn { width:42px; height:42px; border:0; border-radius:12px; background:#ffffff0d; color:#fff; font-size:20px; }
    .bang-attachment { display:none; }
    .bang-file-name { display:none; padding:7px 12px; color:#b8c0dd; font-size:12px; border-top:1px solid #ffffff10; }
    @media(max-width:760px) {
      body { padding-bottom:84px; overflow-x:hidden; }
      .shell { width:100%; padding:10px; }
      .panel { display:none !important; }
      .layout { display:block; }
      .chatbox { min-height:calc(100dvh - 90px); border-radius:18px; }
      .messages { min-height:0; padding:14px; }
      .msg { max-width:88%; font-size:14px; }
      .composer { padding:9px; }
      .composer input,.field { min-height:46px; font-size:16px; }
      .bang-mobile-nav { display:flex; position:fixed; left:8px; right:8px; bottom:max(8px,env(safe-area-inset-bottom)); height:70px; padding:5px; gap:3px; background:rgba(13,19,36,.98); border:1px solid #ffffff18; border-radius:20px; z-index:9998; box-shadow:0 12px 35px #0008; }
      .bang-mobile-nav button { flex:1; min-width:0; border:0; border-radius:15px; background:transparent; color:#9ba5c4; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:3px; }
      .bang-mobile-nav button.active { color:#fff; background:#ffffff12; }
      .bang-mobile-nav span { font-size:18px; line-height:1; }
      .bang-mobile-nav small { font-size:9px; }
      .bang-home-grid { grid-template-columns:repeat(2,1fr); padding:12px; }
      .bang-home-card { min-height:100px; }
    }
  `;
  document.head.appendChild(style);

  function closeSocket() {
    try { if (window.ws) { window.ws.close(); window.ws = null; } } catch (_) {}
  }

  function active(view) {
    document.querySelectorAll('.bang-mobile-nav button').forEach(b => b.classList.toggle('active', b.dataset.view === view));
  }

  function nav() {
    if (!$('app') || document.querySelector('.bang-mobile-nav')) return;
    const n = document.createElement('nav');
    n.className = 'bang-mobile-nav';
    n.setAttribute('aria-label', 'Bottom navigation');
    const items = [
      ['chats','💬','Chats', () => window.chatView?.()],
      ['calls','📞','Calls', () => window.callsView?.()],
      ['contacts','👥','Contacts', () => window.friendsView?.()],
      ['settings','⚙️','Settings', () => window.settingsView?.()]
    ];
    items.forEach(([id, icon, label, action]) => {
      const b = document.createElement('button');
      b.type = 'button'; b.dataset.view = id;
      b.innerHTML = `<span>${icon}</span><small>${label}</small>`;
      b.onclick = () => { action(); active(id); };
      n.appendChild(b);
    });
    document.body.appendChild(n);
  }

  function homeView() {
    closeSocket();
    const main = $('main');
    if (!main) return;
    main.innerHTML = `
      <div class="chat-head">
        <div><div class="title">Home</div><div class="muted">Welcome back, ${escName(window.me?.displayName || window.me?.username || 'BANG user')}</div></div>
        <button class="bang-icon-btn" onclick="window.profileView()">👤</button>
      </div>
      <div class="bang-home-grid">
        <button class="bang-home-card" onclick="window.searchView()"><span>🔍</span>Search</button>
        <button class="bang-home-card" onclick="window.profileView()"><span>👤</span>Profile</button>
        <button class="bang-home-card" onclick="window.chatView();active('chats')"><span>💬</span>Chats</button>
        <button class="bang-home-card" onclick="window.callsView();active('calls')"><span>📞</span>Calls</button>
        <button class="bang-home-card" onclick="window.friendsView();active('contacts')"><span>👥</span>Contacts</button>
        <button class="bang-home-card" onclick="window.settingsView();active('settings')"><span>⚙️</span>Settings</button>
      </div>
      <div class="card" style="margin:0 12px">
        <b>🛰️ Mesh</b>
        <div class="muted small">BANG Mesh runs automatically when Bluetooth and Nearby devices permission are available.</div>
      </div>`;
    nav();
    active('chats');
  }

  function escName(value) {
    return String(value || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  }

  function profileView() {
    const main = $('main'); if (!main) return;
    closeSocket();
    const user = window.me || {};
    main.innerHTML = `
      <div class="chat-head"><div class="bang-chat-title"><button class="bang-chat-back" onclick="window.homeView()">←</button><div><div class="title">Profile</div><div class="muted">Your BANG account</div></div></div></div>
      <div style="padding:20px">
        <div class="profile"><div class="profile-row"><div class="avatar">${escName((user.displayName || user.username || 'B')[0]).toUpperCase()}</div><div><b>${escName(user.displayName || user.username || 'BANG')}</b><div class="muted">@${escName(user.username || '')}</div></div></div></div>
        <div class="card"><div class="muted small">ACCOUNT CODE</div><div class="code">${escName(user.accountCode || '—')}</div></div>
      </div>`;
    active('chats');
  }

  function searchView() {
    const main = $('main'); if (!main) return;
    closeSocket();
    main.innerHTML = `
      <div class="chat-head"><div class="bang-chat-title"><button class="bang-chat-back" onclick="window.homeView()">←</button><div><div class="title">Search</div><div class="muted">Find BANG accounts</div></div></div></div>
      <div style="padding:16px"><input id="bangSearch" class="field" placeholder="Search username…" autocomplete="off"><div id="bangSearchResults" class="list"></div></div>`;
    const input = $('bangSearch');
    input.oninput = () => {
      const q = input.value.trim().toLowerCase();
      const results = $('bangSearchResults');
      if (!q) { results.innerHTML = '<div class="muted">Type a username to search nearby accounts.</div>'; return; }
      if (typeof window.nearby === 'function') { window.nearby(); setTimeout(() => { const src = $('nearbyList'); if (src) results.innerHTML = src.innerHTML || '<div class="muted">No matching accounts.</div>'; }, 250); }
    };
    active('chats'); input.focus();
  }

  function enhanceChat() {
    const main = $('main'); if (!main) return;
    const head = main.querySelector('.chat-head');
    if (head && !head.querySelector('.bang-chat-back')) {
      const left = document.createElement('div');
      left.className = 'bang-chat-title';
      left.innerHTML = '<button class="bang-chat-back" type="button">←</button>' + head.firstElementChild.outerHTML;
      head.firstElementChild.replaceWith(left);
      left.querySelector('button').onclick = () => homeView();
    }
    const title = head?.querySelector('.title');
    if (title) title.textContent = title.textContent === 'Nearby room' ? 'Chat' : title.textContent;
    const status = head?.querySelector('.muted');
    if (status) status.textContent = '🟢 Online / 📡 Nearby when Mesh is available';

    const composer = main.querySelector('.composer');
    if (!composer || composer.dataset.enhanced === '1') return;
    composer.dataset.enhanced = '1';
    const input = $('text');
    const send = $('sendButton');
    if (input && send) {
      const attach = document.createElement('button');
      attach.className = 'bang-icon-btn'; attach.type = 'button'; attach.title = 'Attachment'; attach.textContent = '📎';
      const file = document.createElement('input'); file.type = 'file'; file.className = 'bang-attachment'; file.accept = 'image/*,video/*,audio/*,.pdf,.txt';
      const voice = document.createElement('button');
      voice.className = 'bang-icon-btn'; voice.type = 'button'; voice.title = 'Voice'; voice.textContent = '🎤';
      const fileName = document.createElement('div'); fileName.className = 'bang-file-name';
      attach.onclick = () => file.click();
      file.onchange = () => { const f = file.files?.[0]; if (!f) return; fileName.textContent = '📎 ' + f.name; fileName.style.display = 'block'; toast('Attachment selected'); };
      voice.onclick = () => { if (window.BangMesh?.startWifiCalls) { window.BangMesh.startWifiCalls(); toast('Starting voice…'); } else toast('Voice is available in the Android app.'); };
      composer.insertBefore(attach, input); composer.insertBefore(voice, send); composer.after(fileName);
    }
    nav(); active('chats');
  }

  function wrap(name, fn) {
    if (typeof window[name] !== 'function') return;
    const original = window[name];
    window[name] = function () { const result = fn(original, this, arguments); return result; };
  }

  wrap('chatView', (original, self, args) => { closeSocket(); const r = original.apply(self, args); setTimeout(enhanceChat, 0); return r; });
  wrap('callsView', (original, self, args) => { closeSocket(); const r = original.apply(self, args); nav(); active('calls'); return r; });
  wrap('friendsView', (original, self, args) => { closeSocket(); const r = original.apply(self, args); nav(); active('contacts'); return r; });
  wrap('settingsView', (original, self, args) => { closeSocket(); const r = original.apply(self, args); nav(); active('settings'); return r; });

  window.homeView = homeView;
  window.profileView = profileView;
  window.searchView = searchView;

  if (typeof window.showHome === 'function') {
    const originalShowHome = window.showHome;
    window.showHome = function () { const r = originalShowHome.apply(this, arguments); setTimeout(homeView, 0); return r; };
  }

  if (typeof window.api === 'function') {
    const originalApi = window.api;
    window.api = async function (path, options = {}) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 15000);
      try { return await originalApi(path, { ...options, signal: controller.signal }); }
      catch (e) { if (e?.name === 'AbortError') throw new Error('Server took too long to respond.'); if (e instanceof TypeError) throw new Error('Cannot connect to the BANG server.'); throw e; }
      finally { clearTimeout(timer); }
    };
  }

  document.addEventListener('keydown', e => {
    if (e.key === 'Enter' && e.target?.id === 'text') window.sendMessage?.();
  });

  nav();
  window.BangMobileEnhancements = { version:'2.0.0', homeView, profileView, searchView };
})();

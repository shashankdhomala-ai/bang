(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  let toastTimer;

  function showMobileToast(message) {
    let el = $('bang-mobile-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'bang-mobile-toast';
      el.className = 'bang-mobile-toast';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
  }

  const style = document.createElement('style');
  style.textContent = `
    button { -webkit-tap-highlight-color: transparent; touch-action: manipulation; transition: transform .15s ease, opacity .15s ease, background .15s ease; }
    button:not(:disabled):active { transform: scale(.97); }
    button:disabled { cursor:not-allowed; opacity:.5; }
    button:focus-visible, input:focus-visible { outline:3px solid rgba(108,99,255,.45); outline-offset:2px; }
    .bang-mobile-nav { display:none; }
    .bang-mobile-toast { position:fixed; left:50%; bottom:25px; z-index:10000; max-width:calc(100% - 30px); padding:13px 17px; border-radius:13px; color:#fff; background:#202943; box-shadow:0 12px 35px rgba(0,0,0,.35); transform:translate(-50%,20px); opacity:0; pointer-events:none; transition:.25s ease; text-align:center; font-size:14px; }
    .bang-mobile-toast.show { transform:translate(-50%,0); opacity:1; }
    @media(max-width:760px) {
      body { padding-bottom:88px; overflow-x:hidden; }
      #app { min-height:100dvh; }
      .shell { width:100%; padding:14px; }
      .top { position:sticky; top:0; z-index:10; padding:10px 1px; background:rgba(7,11,22,.88); backdrop-filter:blur(18px); }
      .brand { font-size:24px; }
      .layout { grid-template-columns:1fr; gap:12px; }
      .panel { display:none !important; }
      .chatbox { min-height:calc(100dvh - 105px); border-radius:17px; }
      .messages { min-height:0; padding:14px; }
      .msg { max-width:88%; font-size:14px; }
      .composer { padding:10px; padding-bottom:max(10px,env(safe-area-inset-bottom)); }
      .composer input, .field { min-height:46px; font-size:16px; }
      .btn { min-height:44px; }
      .bang-mobile-nav { display:flex; position:fixed; left:8px; right:8px; bottom:max(8px,env(safe-area-inset-bottom)); height:70px; padding:5px; gap:3px; background:rgba(13,19,36,.97); border:1px solid rgba(255,255,255,.12); border-radius:20px; backdrop-filter:blur(20px); box-shadow:0 12px 35px rgba(0,0,0,.4); z-index:9998; }
      .bang-mobile-nav button { flex:1; min-width:0; min-height:58px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:2px; padding:4px 1px; border:0; background:transparent; color:#9ba5c4; border-radius:15px; font-size:10px; }
      .bang-mobile-nav button.active { color:#fff; background:#ffffff12; }
      .bang-mobile-nav span { font-size:17px; line-height:1; }
      .bang-mobile-nav small { font-size:9px; line-height:1; }
      .bang-mobile-nav button:active { transform:scale(.95); }
    }
    @media(max-width:380px) { .bang-mobile-nav small { font-size:8px; } .bang-mobile-nav span { font-size:15px; } .shell { padding:10px; } }
  `;
  document.head.appendChild(style);

  function closeSocketSafe() {
    try {
      if (typeof ws !== 'undefined' && ws) {
        try { ws.close(); } catch (_) {}
        ws = null;
      }
    } catch (_) {}
  }

  function setActiveMobile(view) {
    document.querySelectorAll('.bang-mobile-nav button').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
  }

  function addMobileNav() {
    if (!$('app') || document.querySelector('.bang-mobile-nav')) return;
    const nav = document.createElement('nav');
    nav.className = 'bang-mobile-nav';
    nav.setAttribute('aria-label', 'Mobile navigation');
    const items = [['home','⌂','Home'],['chat','💬','Chats'],['friends','👥','Friends'],['nearby','📍','Nearby'],['calls','📞','Calls'],['settings','⚙️','Settings']];
    items.forEach(([view, icon, label]) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.dataset.view = view;
      b.innerHTML = '<span>' + icon + '</span><small>' + label + '</small>';
      b.addEventListener('click', () => {
        if (view === 'home') window.showHome?.();
        else if (view === 'chat') window.chatView?.();
        else if (view === 'friends') window.friendsView?.();
        else if (view === 'nearby') { window.friendsView?.(); setTimeout(() => window.nearby?.(), 0); }
        else if (view === 'calls') window.callsView?.();
        else if (view === 'settings') window.settingsView?.();
        setActiveMobile(view);
      });
      nav.appendChild(b);
    });
    document.body.appendChild(nav);
  }

  function enhanceChat() {
    const input = $('text') || $('messageInput');
    if (input) setTimeout(() => { try { input.focus(); } catch (_) {} }, 50);
  }

  if (typeof window.chatView === 'function') {
    const originalChatView = window.chatView;
    window.chatView = function () { closeSocketSafe(); originalChatView.apply(this, arguments); addMobileNav(); setActiveMobile('chat'); enhanceChat(); };
  }
  if (typeof window.callsView === 'function') {
    const originalCallsView = window.callsView;
    window.callsView = function () { closeSocketSafe(); originalCallsView.apply(this, arguments); addMobileNav(); setActiveMobile('calls'); };
  }
  if (typeof window.friendsView === 'function') {
    const originalFriendsView = window.friendsView;
    window.friendsView = function () { closeSocketSafe(); originalFriendsView.apply(this, arguments); addMobileNav(); setActiveMobile('friends'); };
  }
  if (typeof window.settingsView === 'function') {
    const originalSettingsView = window.settingsView;
    window.settingsView = function () { closeSocketSafe(); originalSettingsView.apply(this, arguments); addMobileNav(); setActiveMobile('settings'); };
  }
  if (typeof window.showHome === 'function') {
    const originalShowHome = window.showHome;
    window.showHome = function () { closeSocketSafe(); originalShowHome.apply(this, arguments); addMobileNav(); setActiveMobile('chat'); };
  }

  if (typeof window.api === 'function') {
    const originalApi = window.api;
    window.api = async function (path, options = {}) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 15000);
      try {
        const opts = { ...options, signal: controller.signal };
        return await originalApi(path, opts);
      } catch (err) {
        if (err && err.name === 'AbortError') throw new Error('The server took too long to respond.');
        if (err instanceof TypeError) throw new Error('Cannot connect to the BANG server.');
        throw err;
      } finally { clearTimeout(timeout); }
    };
  }

  const observer = new MutationObserver(() => {
    document.querySelectorAll('button[onclick*="requestByName"], button[onclick*="acceptFriend"]').forEach((button) => {
      if (button.dataset.bangSafeBound === '1') return;
      const onclick = button.getAttribute('onclick') || '';
      const requestMatch = onclick.match(/requestByName\((.*)\)/);
      const acceptMatch = onclick.match(/acceptFriend\((.*)\)/);
      if (requestMatch) {
        let value = ''; try { value = JSON.parse(requestMatch[1]); } catch (_) {}
        button.removeAttribute('onclick'); button.dataset.bangSafeBound = '1'; button.addEventListener('click', () => window.requestByName?.(value));
      } else if (acceptMatch) {
        let value = ''; try { value = JSON.parse(acceptMatch[1]); } catch (_) {}
        button.removeAttribute('onclick'); button.dataset.bangSafeBound = '1'; button.addEventListener('click', () => window.acceptFriend?.(value));
      }
    });
  });
  observer.observe(document.body, { childList:true, subtree:true });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    const active = document.activeElement;
    if (!active) return;
    if (['u','p','username','password','n','displayName'].includes(active.id)) {
      if (typeof window.login === 'function') { event.preventDefault(); window.login(); }
    }
  });

  addMobileNav();
  if ($('app')) new MutationObserver(addMobileNav).observe($('app'), { childList:true, subtree:true });

  if (typeof window.toast === 'function') {
    const originalToast = window.toast;
    window.toast = function (message) { try { originalToast(message); } catch (_) {} if (window.innerWidth <= 760) showMobileToast(message); };
  } else {
    window.toast = showMobileToast;
  }

  window.BangMobileEnhancements = { version:'1.0.0', closeSocket:closeSocketSafe, addMobileNav, showToast:showMobileToast };
})();

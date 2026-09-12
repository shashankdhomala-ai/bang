(function () {
  'use strict';

  function addControls() {
    const main = document.getElementById('main');
    if (!main || document.getElementById('bang-offline-v2')) return;

    const box = document.createElement('div');
    box.id = 'bang-offline-v2';
    box.className = 'card';
    box.style.margin = '0 14px 14px';
    box.innerHTML = `
      <b>🛰️ Offline / Local</b>
      <div class="muted small" id="bang-local-status" style="margin-top:5px">
        Start Local mode to discover nearby BANG phones. Internet is not required.
      </div>
      <div class="actions" style="margin-top:10px">
        <button class="btn" id="bang-local-start">Start Local</button>
        <button class="btn secondary" id="bang-local-stop">Stop Local</button>
      </div>
      <div class="muted small" style="margin-top:10px">
        A→B→C chat relay: B can forward messages between nearby peers. Hop limit is 4.
      </div>
      <div class="actions" style="margin-top:8px">
        <button class="btn secondary" id="bang-relay-start">Enable Relay</button>
        <button class="btn secondary" id="bang-relay-stop">Disable Relay</button>
      </div>
    `;
    main.appendChild(box);

    document.getElementById('bang-local-start').onclick = function () {
      if (!window.BangMesh) return toast('Local mode is available in the Android app build.');
      window.BangMesh.start();
    };
    document.getElementById('bang-local-stop').onclick = function () {
      window.BangMesh?.stop();
    };
    document.getElementById('bang-relay-start').onclick = function () {
      if (!window.BangMesh) return toast('Relay is available in the Android app build.');
      window.BangMesh.startRelay();
    };
    document.getElementById('bang-relay-stop').onclick = function () {
      window.BangMesh?.stopRelay();
    };
  }

  const originalChatView = window.chatView;
  if (typeof originalChatView === 'function') {
    window.chatView = function () {
      originalChatView.apply(this, arguments);
      addControls();
    };
  }

  const originalCallsView = window.callsView;
  if (typeof originalCallsView === 'function') {
    window.callsView = function () {
      originalCallsView.apply(this, arguments);
      addCallRelayControls();
    };
  }

  function addCallRelayControls() {
    const main = document.getElementById('main');
    if (!main || document.getElementById('bang-call-relay-v2')) return;
    const box = document.createElement('div');
    box.id = 'bang-call-relay-v2';
    box.className = 'card';
    box.style.margin = '14px 20px';
    box.innerHTML = `
      <b>📞 Local A→B→C calling</b>
      <p class="muted small">B acts only as a transport relay. This is a prototype transport, not production VoIP.</p>
      <div class="actions">
        <button class="btn" id="bang-call-relay-start">Start B Relay</button>
        <button class="btn secondary" id="bang-call-relay-stop">Stop B Relay</button>
      </div>
    `;
    main.appendChild(box);
    document.getElementById('bang-call-relay-start').onclick = () => window.BangMesh?.startRelay();
    document.getElementById('bang-call-relay-stop').onclick = () => window.BangMesh?.stopRelay();
  }

  window.bangMeshEvent = (function (previous) {
    return function (type, text) {
      if (previous) previous(type, text);
      const status = document.getElementById('bang-local-status');
      if (status && (type === 'status' || type === 'relay_status' || type === 'error')) status.textContent = text;
    };
  })(window.bangMeshEvent);

  const observer = new MutationObserver(addControls);
  observer.observe(document.body, { childList: true, subtree: true });
  addControls();
})();

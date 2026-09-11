const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = path.resolve(__dirname, '..');
const DB_FILE = path.join(__dirname, 'bang-data.json');

function loadDb() {
  try { return JSON.parse(fs.readFileSync(DB_FILE, 'utf8')); }
  catch { return { users: {}, sessions: {}, invites: {}, friendRequests: [], friends: {} }; }
}
function saveDb() {
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DB_FILE);
}
let db = loadDb();
db.users ||= {}; db.sessions ||= {}; db.invites ||= {}; db.friendRequests ||= []; db.friends ||= {};
if (!db.serverCode) { db.serverCode = 'BANG-' + crypto.randomBytes(4).toString('hex').toUpperCase(); saveDb(); }

function json(res, status, data) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  });
  res.end(JSON.stringify(data));
}
function body(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', c => { raw += c; if (raw.length > 64 * 1024) req.destroy(); });
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch { reject(new Error('Invalid JSON')); } });
    req.on('error', reject);
  });
}
function safeUser(u) { return { username: u.username, displayName: u.displayName, accountCode: u.accountCode }; }
function validUsername(v) { return typeof v === 'string' && /^[A-Za-z0-9_]{3,20}$/.test(v); }
function validPassword(v) { return typeof v === 'string' && v.length >= 6 && v.length <= 128; }
function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.pbkdf2Sync(password, salt, 120000, 32, 'sha256').toString('hex');
  return { salt, hash };
}
function checkPassword(password, u) {
  const { hash } = hashPassword(password, u.salt);
  return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(u.hash, 'hex'));
}
function token() { return crypto.randomBytes(32).toString('hex'); }
function code() { return crypto.randomBytes(6).toString('base64url').slice(0, 8).toUpperCase(); }
function auth(req) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : '';
  const username = db.sessions[t];
  return username && db.users[username] ? db.users[username] : null;
}
function addFriend(a, b) {
  db.friends[a] ||= [];
  db.friends[b] ||= [];
  if (!db.friends[a].includes(b)) db.friends[a].push(b);
  if (!db.friends[b].includes(a)) db.friends[b].push(a);
}

async function handleApi(req, res, url) {
  if (req.method === 'OPTIONS') return json(res, 204, {});

  try {
    if (req.method === 'POST' && url.pathname === '/api/signup') {
      const b = await body(req);
      const username = String(b.username || '').trim();
      const displayName = String(b.displayName || username).trim().slice(0, 40);
      if (!validUsername(username)) return json(res, 400, { error: 'Username must be 3–20 letters, numbers or _.' });
      if (!validPassword(b.password)) return json(res, 400, { error: 'Password must be 6–128 characters.' });
      if (db.users[username]) return json(res, 409, { error: 'Username already exists.' });
      const hp = hashPassword(b.password);
      db.users[username] = { username, displayName: displayName || username, accountCode: 'BANG-' + crypto.randomBytes(5).toString('hex').toUpperCase(), ...hp, createdAt: Date.now() };
      const t = token(); db.sessions[t] = username; saveDb();
      return json(res, 201, { token: t, user: safeUser(db.users[username]) });
    }

    if (req.method === 'POST' && url.pathname === '/api/login') {
      const b = await body(req); const username = String(b.username || '').trim();
      const u = db.users[username];
      if (!u || !validPassword(b.password) || !checkPassword(b.password, u)) return json(res, 401, { error: 'Invalid username or password.' });
      const t = token(); db.sessions[t] = username; saveDb();
      return json(res, 200, { token: t, user: safeUser(u) });
    }

    if (req.method === 'GET' && url.pathname.startsWith('/api/invite/')) {
      const c = url.pathname.split('/').pop().toUpperCase();
      const inv = db.invites[c];
      if (!inv || inv.expiresAt < Date.now()) return json(res, 404, { error: 'Invite expired or not found.' });
      return json(res, 200, { code: c, owner: safeUser(db.users[inv.owner]) });
    }

    const me = auth(req);
    if (!me) return json(res, 401, { error: 'Please log in.' });

    if (req.method === 'GET' && url.pathname === '/api/me') return json(res, 200, { user: safeUser(me) });
    if (req.method === 'GET' && url.pathname === '/api/server') return json(res, 200, { serverCode: db.serverCode });
    if (req.method === 'POST' && url.pathname === '/api/server/join') {
      const b = await body(req);
      if (String(b.code || '').trim().toUpperCase() !== db.serverCode) return json(res, 403, { error: 'Invalid server code.' });
      return json(res, 200, { ok: true, serverCode: db.serverCode });
    }
    if (req.method === 'GET' && url.pathname === '/api/nearby') {
      const names = [...new Set([...clients.values()].filter(x => x.username && x.username !== me.username && Date.now() - x.seen < 30000).map(x => x.username))];
      return json(res, 200, { users: names.map(n => db.users[n]).filter(Boolean).map(safeUser) });
    }

    if (req.method === 'POST' && url.pathname === '/api/invite') {
      let c; do { c = code(); } while (db.invites[c]);
      db.invites[c] = { owner: me.username, createdAt: Date.now(), expiresAt: Date.now() + 7 * 24 * 3600 * 1000 };
      saveDb();
      return json(res, 201, { code: c });
    }

    if (req.method === 'GET' && url.pathname === '/api/friends') {
      const names = db.friends[me.username] || [];
      const friends = names.map(n => db.users[n]).filter(Boolean).map(safeUser);
      const incoming = db.friendRequests.filter(r => r.to === me.username && r.status === 'pending').map(r => ({ from: safeUser(db.users[r.from]), id: r.id }));
      return json(res, 200, { friends, incoming });
    }

    if (req.method === 'POST' && url.pathname === '/api/friends/request') {
      const b = await body(req); const username = String(b.username || '').trim();
      if (!db.users[username]) return json(res, 404, { error: 'User not found.' });
      if (username === me.username) return json(res, 400, { error: 'You cannot add yourself.' });
      if ((db.friends[me.username] || []).includes(username)) return json(res, 409, { error: 'Already friends.' });
      const existing = db.friendRequests.find(r => r.from === me.username && r.to === username && r.status === 'pending');
      if (existing) return json(res, 409, { error: 'Request already sent.' });
      const reverse = db.friendRequests.find(r => r.from === username && r.to === me.username && r.status === 'pending');
      if (reverse) { reverse.status = 'accepted'; addFriend(me.username, username); saveDb(); return json(res, 200, { ok: true, accepted: true }); }
      db.friendRequests.push({ id: token().slice(0, 12), from: me.username, to: username, status: 'pending', createdAt: Date.now() });
      saveDb(); return json(res, 201, { ok: true });
    }

    if (req.method === 'POST' && url.pathname === '/api/friends/accept') {
      const b = await body(req); const r = db.friendRequests.find(x => x.id === b.id && x.to === me.username && x.status === 'pending');
      if (!r) return json(res, 404, { error: 'Friend request not found.' });
      r.status = 'accepted'; addFriend(me.username, r.from); saveDb(); return json(res, 200, { ok: true });
    }

    return json(res, 404, { error: 'API route not found.' });
  } catch (e) {
    console.error(e);
    return json(res, 500, { error: 'Server error.' });
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname.startsWith('/api/')) return handleApi(req, res, url);
  if (url.pathname.startsWith('/join/')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(ROOT, 'index.html')).pipe(res);
  }
  let requested = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
  const file = path.normalize(path.join(ROOT, requested));
  if (!file.startsWith(ROOT)) { res.writeHead(403); return res.end('Forbidden'); }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('Not found'); }
    const type = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.css': 'text/css; charset=utf-8' }[path.extname(file)] || 'application/octet-stream';
    res.writeHead(200, { 'Content-Type': type }); fs.createReadStream(file).pipe(res);
  });
});

const wss = new WebSocket.Server({ server });
const clients = new Map();
function broadcast(room, message, skip) {
  for (const [ws, meta] of clients) if (meta.room === room && ws !== skip && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
}
wss.on('connection', ws => {
  clients.set(ws, { room: 'default', username: 'guest', name: 'Guest', seen: Date.now() });
  ws.on('message', raw => {
    let m; try { m = JSON.parse(raw.toString()); } catch { return; }
    const meta = clients.get(ws); if (!meta) return; meta.seen = Date.now();
    if (m.type === 'join') {
      meta.username = String(m.username || 'guest').slice(0, 20); meta.name = String(m.name || meta.username).slice(0, 40); meta.room = String(m.room || 'default').slice(0, 80);
      broadcast(meta.room, { type: 'presence', text: `${meta.name} joined BANG` });
    }
    if (m.type === 'chat') {
      const text = String(m.text || '').trim().slice(0, 2000); if (!text) return;
      broadcast(meta.room, { type: 'chat', name: meta.name, username: meta.username, text, at: Date.now() }, ws);
    }
  });
  ws.on('close', () => { const meta = clients.get(ws); if (meta) broadcast(meta.room, { type: 'presence', text: `${meta.name} left BANG` }, ws); clients.delete(ws); });
});
server.listen(PORT, HOST, () => console.log(`BANG running on ${HOST}:${PORT}`));

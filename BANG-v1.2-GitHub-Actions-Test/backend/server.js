const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = path.resolve(__dirname, '..');
const DB_FILE = process.env.DB_FILE || path.join(__dirname, 'bang-data.json');
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_BODY_BYTES = 64 * 1024;
const MAX_WS_MESSAGE_BYTES = 16 * 1024;
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN || '*';
const PRODUCTION = process.env.NODE_ENV === 'production';

if (PRODUCTION && ALLOWED_ORIGIN === '*') {
  console.warn('WARNING: ALLOWED_ORIGIN=* in production. Set an exact HTTPS origin.');
}

function loadDb() {
  try { return JSON.parse(fs.readFileSync(DB_FILE, 'utf8')); }
  catch { return { users: {}, sessions: {}, invites: {}, friendRequests: [], friends: {} }; }
}
function saveDb() {
  const dir = path.dirname(DB_FILE);
  fs.mkdirSync(dir, { recursive: true });
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, DB_FILE);
}
let db = loadDb();
db.users ||= {}; db.sessions ||= {}; db.invites ||= {}; db.friendRequests ||= []; db.friends ||= {};
if (!db.serverCode) { db.serverCode = process.env.BANG_SERVER_CODE || 'BANG-' + crypto.randomBytes(4).toString('hex').toUpperCase(); saveDb(); }

const rateBuckets = new Map();
function clientIp(req) {
  const forwarded = req.headers['x-forwarded-for'];
  return String(forwarded || req.socket.remoteAddress || 'unknown').split(',')[0].trim();
}
function rateLimit(req, limit = 120, windowMs = 60_000) {
  const key = clientIp(req);
  const now = Date.now();
  const current = rateBuckets.get(key);
  if (!current || now - current.start >= windowMs) {
    rateBuckets.set(key, { start: now, count: 1 });
    return true;
  }
  current.count += 1;
  return current.count <= limit;
}
setInterval(() => {
  const cutoff = Date.now() - 10 * 60_000;
  for (const [key, bucket] of rateBuckets) if (bucket.start < cutoff) rateBuckets.delete(key);
}, 10 * 60_000).unref();

function corsOrigin(req) {
  if (ALLOWED_ORIGIN === '*') return '*';
  const origin = req.headers.origin || '';
  return origin === ALLOWED_ORIGIN ? origin : null;
}
function securityHeaders(res, origin) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.setHeader('Permissions-Policy', 'microphone=(self), camera=()');
  if (PRODUCTION) res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  if (origin) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    if (origin !== '*') res.setHeader('Vary', 'Origin');
  }
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
}
function json(res, status, data, origin = '*') {
  securityHeaders(res, origin);
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.writeHead(status);
  res.end(JSON.stringify(data));
}
function body(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', c => {
      raw += c;
      if (Buffer.byteLength(raw, 'utf8') > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('Request body too large.'), { statusCode: 413 }));
        req.destroy();
      }
    });
    req.on('end', () => { try { resolve(raw ? JSON.parse(raw) : {}); } catch { reject(Object.assign(new Error('Invalid JSON.'), { statusCode: 400 })); } });
    req.on('error', reject);
  });
}
function safeUser(u) { return { username: u.username, displayName: u.displayName, accountCode: u.accountCode }; }
function validUsername(v) { return typeof v === 'string' && /^[A-Za-z0-9_]{3,20}$/.test(v); }
function validPassword(v) { return typeof v === 'string' && v.length >= 8 && v.length <= 128; }
function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.pbkdf2Sync(password, salt, 210000, 32, 'sha256').toString('hex');
  return { salt, hash };
}
function checkPassword(password, u) {
  const { hash } = hashPassword(password, u.salt);
  const a = Buffer.from(hash, 'hex');
  const b = Buffer.from(u.hash || '', 'hex');
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function token() { return crypto.randomBytes(32).toString('hex'); }
function code() { return crypto.randomBytes(6).toString('base64url').slice(0, 8).toUpperCase(); }
function auth(req) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : '';
  const entry = db.sessions[t];
  if (!entry) return null;
  if (typeof entry === 'string') return db.users[entry] || null;
  if (!entry.username || entry.expiresAt < Date.now()) {
    delete db.sessions[t];
    return null;
  }
  return db.users[entry.username] || null;
}
function createSession(username) {
  const t = token();
  db.sessions[t] = { username, createdAt: Date.now(), expiresAt: Date.now() + SESSION_TTL_MS };
  return t;
}
function addFriend(a, b) {
  db.friends[a] ||= [];
  db.friends[b] ||= [];
  if (!db.friends[a].includes(b)) db.friends[a].push(b);
  if (!db.friends[b].includes(a)) db.friends[b].push(a);
}

async function handleApi(req, res, url) {
  const origin = corsOrigin(req);
  if (req.method === 'OPTIONS') {
    if (ALLOWED_ORIGIN !== '*' && !origin) return json(res, 403, { error: 'Origin not allowed.' }, '*');
    return json(res, 204, {}, origin || '*');
  }
  if (ALLOWED_ORIGIN !== '*' && req.headers.origin && !origin) return json(res, 403, { error: 'Origin not allowed.' }, '*');
  if (!rateLimit(req, url.pathname === '/api/login' || url.pathname === '/api/signup' ? 20 : 180)) return json(res, 429, { error: 'Too many requests. Try again later.' }, origin || '*');

  try {
    if (req.method === 'GET' && url.pathname === '/healthz') return json(res, 200, { ok: true, service: 'bang-backend', time: Date.now() }, origin || '*');

    if (req.method === 'POST' && url.pathname === '/api/signup') {
      const b = await body(req);
      const username = String(b.username || '').trim();
      const displayName = String(b.displayName || username).trim().slice(0, 40);
      if (!validUsername(username)) return json(res, 400, { error: 'Username must be 3–20 letters, numbers or _.' }, origin || '*');
      if (!validPassword(b.password)) return json(res, 400, { error: 'Password must be 8–128 characters.' }, origin || '*');
      if (db.users[username]) return json(res, 409, { error: 'Username already exists.' }, origin || '*');
      const hp = hashPassword(b.password);
      db.users[username] = { username, displayName: displayName || username, accountCode: 'BANG-' + crypto.randomBytes(5).toString('hex').toUpperCase(), ...hp, createdAt: Date.now() };
      const t = createSession(username); saveDb();
      return json(res, 201, { token: t, user: safeUser(db.users[username]) }, origin || '*');
    }

    if (req.method === 'POST' && url.pathname === '/api/login') {
      const b = await body(req); const username = String(b.username || '').trim();
      const u = db.users[username];
      if (!u || !validPassword(b.password) || !checkPassword(b.password, u)) return json(res, 401, { error: 'Invalid username or password.' }, origin || '*');
      const t = createSession(username); saveDb();
      return json(res, 200, { token: t, user: safeUser(u) }, origin || '*');
    }

    if (req.method === 'GET' && url.pathname.startsWith('/api/invite/')) {
      const c = url.pathname.split('/').pop().toUpperCase();
      const inv = db.invites[c];
      if (!inv || inv.expiresAt < Date.now()) return json(res, 404, { error: 'Invite expired or not found.' }, origin || '*');
      return json(res, 200, { code: c, owner: safeUser(db.users[inv.owner]) }, origin || '*');
    }

    const me = auth(req);
    if (!me) return json(res, 401, { error: 'Please log in.' }, origin || '*');

    if (req.method === 'GET' && url.pathname === '/api/me') return json(res, 200, { user: safeUser(me) }, origin || '*');
    if (req.method === 'GET' && url.pathname === '/api/server') return json(res, 200, { serverCode: db.serverCode }, origin || '*');
    if (req.method === 'POST' && url.pathname === '/api/server/join') {
      const b = await body(req);
      if (String(b.code || '').trim().toUpperCase() !== db.serverCode) return json(res, 403, { error: 'Invalid server code.' }, origin || '*');
      return json(res, 200, { ok: true, serverCode: db.serverCode }, origin || '*');
    }
    if (req.method === 'GET' && url.pathname === '/api/nearby') {
      const names = [...new Set([...clients.values()].filter(x => x.username && x.username !== me.username && Date.now() - x.seen < 30000).map(x => x.username))];
      return json(res, 200, { users: names.map(n => db.users[n]).filter(Boolean).map(safeUser) }, origin || '*');
    }

    if (req.method === 'POST' && url.pathname === '/api/invite') {
      let c; do { c = code(); } while (db.invites[c]);
      db.invites[c] = { owner: me.username, createdAt: Date.now(), expiresAt: Date.now() + 7 * 24 * 3600 * 1000 };
      saveDb();
      return json(res, 201, { code: c }, origin || '*');
    }

    if (req.method === 'GET' && url.pathname === '/api/friends') {
      const names = db.friends[me.username] || [];
      const friends = names.map(n => db.users[n]).filter(Boolean).map(safeUser);
      const incoming = db.friendRequests.filter(r => r.to === me.username && r.status === 'pending').map(r => ({ from: safeUser(db.users[r.from]), id: r.id }));
      return json(res, 200, { friends, incoming }, origin || '*');
    }

    if (req.method === 'POST' && url.pathname === '/api/friends/request') {
      const b = await body(req); const username = String(b.username || '').trim();
      if (!db.users[username]) return json(res, 404, { error: 'User not found.' }, origin || '*');
      if (username === me.username) return json(res, 400, { error: 'You cannot add yourself.' }, origin || '*');
      if ((db.friends[me.username] || []).includes(username)) return json(res, 409, { error: 'Already friends.' }, origin || '*');
      const existing = db.friendRequests.find(r => r.from === me.username && r.to === username && r.status === 'pending');
      if (existing) return json(res, 409, { error: 'Request already sent.' }, origin || '*');
      const reverse = db.friendRequests.find(r => r.from === username && r.to === me.username && r.status === 'pending');
      if (reverse) { reverse.status = 'accepted'; addFriend(me.username, username); saveDb(); return json(res, 200, { ok: true, accepted: true }, origin || '*'); }
      db.friendRequests.push({ id: token().slice(0, 12), from: me.username, to: username, status: 'pending', createdAt: Date.now() });
      saveDb(); return json(res, 201, { ok: true }, origin || '*');
    }

    if (req.method === 'POST' && url.pathname === '/api/friends/accept') {
      const b = await body(req); const r = db.friendRequests.find(x => x.id === b.id && x.to === me.username && x.status === 'pending');
      if (!r) return json(res, 404, { error: 'Friend request not found.' }, origin || '*');
      r.status = 'accepted'; addFriend(me.username, r.from); saveDb(); return json(res, 200, { ok: true }, origin || '*');
    }

    return json(res, 404, { error: 'API route not found.' }, origin || '*');
  } catch (e) {
    console.error(e);
    return json(res, e.statusCode || 500, { error: e.statusCode ? e.message : 'Server error.' }, origin || '*');
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname.startsWith('/api/') || url.pathname === '/healthz') return handleApi(req, res, url);
  if (url.pathname.startsWith('/join/')) {
    securityHeaders(res, corsOrigin(req) || '*');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(ROOT, 'index.html')).pipe(res);
  }
  let requested;
  try { requested = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname); }
  catch { res.writeHead(400); return res.end('Bad request'); }
  const file = path.normalize(path.join(ROOT, requested));
  if (!file.startsWith(ROOT + path.sep) && file !== ROOT) { res.writeHead(403); return res.end('Forbidden'); }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('Not found'); }
    const type = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.css': 'text/css; charset=utf-8' }[path.extname(file)] || 'application/octet-stream';
    securityHeaders(res, corsOrigin(req) || '*');
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': path.extname(file) === '.html' ? 'no-store' : 'public, max-age=300' });
    fs.createReadStream(file).pipe(res);
  });
});

const wss = new WebSocket.Server({ server, maxPayload: MAX_WS_MESSAGE_BYTES });
const clients = new Map();
function broadcast(room, message, skip) {
  for (const [ws, meta] of clients) if (meta.room === room && ws !== skip && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
}
wss.on('connection', (ws, req) => {
  const origin = req.headers.origin;
  if (PRODUCTION && ALLOWED_ORIGIN !== '*' && origin && origin !== ALLOWED_ORIGIN) return ws.close(1008, 'Origin not allowed');
  clients.set(ws, { room: 'default', username: 'guest', name: 'Guest', seen: Date.now() });
  ws.on('message', raw => {
    if (raw.length > MAX_WS_MESSAGE_BYTES) return ws.close(1009, 'Message too large');
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

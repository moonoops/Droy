import { timingSafeEqual, createHash } from 'node:crypto';
import { translateBackendText } from './backend-i18n.mjs';

export function authorized(req, token = process.env.DROY_WEB_TOKEN) {
  if (!token || token.length < 32) return false;
  const host = req.headers.host;
  if (!/^127\.0\.0\.1:\d+$/.test(host ?? '')) return false;
  if (req.headers.origin && req.headers.origin !== `http://${host}`) return false;
  const cookies = (req.headers.cookie ?? '').split(';').map(part => part.trim()).filter(part => part.startsWith('droy_session='));
  if (cookies.length !== 1) return false;
  const supplied = cookies[0].slice('droy_session='.length);
  const digest = value => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(supplied), digest(token));
}

export function requireMobileSession(req, res) {
  if (authorized(req)) return true;
  res.writeHead(401, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(translateBackendText('DroyAgent 会话未授权，请从应用打开。', process.env.DROY_LOCALE));
  return false;
}

export function requireMobileUpgrade(req, socket) {
  if (authorized(req)) return true;
  socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n');
  return false;
}

// session_cmp.mjs — جرد مقارن لنسخ المشروع المتوازية (جلستنا مقابل بقية الجلسات)
import fs from 'fs';
import path from 'path';

const FEAT = [
  ['B18-router', 'rbToggleStreamKind'],
  ['B18-mode-switch', 'rbModeSwitchButtons'],
  ['B18-studio-v3', 'rb-studio-v3'],
  ['B18-mp-player', 'rb-mp-'],
  ['B18-liveclock', 'rbLiveClockEnsure'],
  ['B17-recovery', 'rbStreamRec'],
  ['B17-audio-nostop', 'toggleLiveAudio'],
  ['B17-switch-lock', 'cameraSwitchBusy'],
  ['B16-engine', 'rbBcStart'],
  ['B16-announce', 'rbAnnounce'],
  ['B16-fit', 'rbFitExact'],
  ['B16-fallback', 'broadcastViewMode'],
  ['B15-check-rhythm', 'rbConnectCheckTick'],
  ['B15-attestation', 'agents.md'],
  ['B14-map', 'rbSavedPoints'],
  ['B12-reconnect', 'forceReconnect'],
];

function dashInfo(root) {
  const p = path.join(root, 'web-dashboard', 'index.html');
  if (!fs.existsSync(p)) return null;
  const st = fs.statSync(p);
  const s = fs.readFileSync(p, 'utf8');
  const feats = {};
  for (const [key, needle] of FEAT) feats[key] = (s.match(new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []).length;
  const studioAliases = ['rb-studio-v3', 'rb-studio-video'];
  return { size: st.size, mtime: st.mtime.toISOString(), feats };
}

function goInfo(root) {
  const p = path.join(root, 'main.go');
  if (!fs.existsSync(p)) return null;
  const st = fs.statSync(p);
  const s = fs.readFileSync(p, 'utf8');
  return { size: st.size, mtime: st.mtime.toISOString(), servesDash: s.includes('web-dashboard'), lines: s.split(/\r?\n/).length };
}

function appInfo(root) {
  const base = path.join(root, 'app', 'src', 'main', 'java', 'com', 'remotebridge');
  if (!fs.existsSync(base)) return null;
  let kt = 0;
  (function walk(dir) { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, e.name); if (e.isDirectory()) walk(p); else if (e.name.endsWith('.kt')) kt++; } })(base);
  return { ktFiles: kt };
}

const roots = [
  ['JALSA(المرجع)', 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA'],
  ['جلسة GEMINI3.5F', 'F:/Ai/agent folders/Editing By opencode/??opencodeGLM5.3FLASHAGEMINI3.5F'],
  ['جلسة Advanced', 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHAAdvanced'],
  ['جلسة copy', 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA-copy'],
  ['جلسة pro-studio', 'F:/Ai/agent folders/Editing By opencode/remotebridge-pro-studio by opencode'],
];

for (const [name, root] of roots) {
  console.log('===== ' + name + ' =====');
  if (!fs.existsSync(root)) { console.log('  (المسار غير موجود)'); continue; }
  const d = dashInfo(root);
  if (d) {
    console.log('  dash: size=' + d.size + ' mtime=' + d.mtime);
    const f = Object.entries(d.feats).map(([k, v]) => k + ':' + v).filter(x => !x.endsWith(':0'));
    console.log('  features-present: ' + f.join(', '));
    const missing = Object.entries(d.feats).filter(([, v]) => v === 0).map(([k]) => k);
    if (missing.length) console.log('  features-missing: ' + missing.join(', '));
  } else console.log('  dash: ABSENT');
  const g = goInfo(root);
  if (g) console.log('  main.go: size=' + g.size + ' lines=' + g.lines + ' servesDash=' + g.servesDash + ' mtime=' + g.mtime); else console.log('  main.go: ABSENT');
  const a = appInfo(root);
  if (a) console.log('  app: ktFiles=' + a.ktFiles);
  const apk = (fs.readdirSync(root).filter(f => f.endsWith('.apk')))[0] || null;
  console.log('  apk: ' + (apk || 'none'));
}

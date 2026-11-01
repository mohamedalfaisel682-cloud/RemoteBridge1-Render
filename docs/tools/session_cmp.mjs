// session_cmp.mjs — مقارنة شاملة بين جلستنا والجلستين المتوازيتين (Advanced و GEMINI3.5F و غيره)
import fs from 'fs';
import path from 'path';

const BASE = 'F:/Ai/agent folders/Editing By opencode';

const FEAT = [
  ['B18-الموجه', 'rbToggleStreamKind'],
  ['B18-مفاتيح-النمط', 'rbModeSwitchButtons'],
  ['B18-استوديو-ثالث', 'rb-studio-v3'],
  ['B18-المشغل', 'rb-mp-'],
  ['B18-الساعة-الحيّة', 'rbLiveClockEnsure'],
  ['B17-حرس-التعافي', 'rbStreamRec'],
  ['B17-الصوت-بلا-إيقاف', 'toggleLiveAudio'],
  ['B17-قفل-الكاميرا', 'cameraSwitchBusy'],
  ['B16-محرك-البرمجي', 'rbBcStart'],
  ['B16-الإعلان', 'rbAnnounce'],
  ['B16-النمط-الثلاثي', 'broadcastViewMode'],
  ['B15-الإيقاع', 'rbConnectTick'],
  ['B12-الاستقرار', 'forceReconnect'],
];

function norm(n) { return n.normalize('NFC').replace(/[\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, ''); }

function pickDir(matcher) {
  return fs.readdirSync(BASE, { withFileTypes: true })
    .filter(e => e.isDirectory())
    .map(e => e.name)
    .find(n => matcher(norm(n)));
}

function analyze(root) {
  const p = path.join(root, 'web-dashboard', 'index.html');
  if (!fs.existsSync(p)) return null;
  const st = fs.statSync(p);
  const s = fs.readFileSync(p, 'utf8');
  const feats = {};
  for (const [key, needle] of FEAT) feats[key] = (s.split(needle)).length - 1;
  return { size: st.size, lines: s.split(/\r?\n/).length, feats, mtime: st.mtime.toISOString() };
}

function goInfo(root) {
  const p = path.join(root, 'main.go');
  if (!fs.existsSync(p)) return null;
  const st = fs.statSync(p);
  const s = fs.readFileSync(p, 'utf8');
  return { size: st.size, lines: s.split(/\r?\n/).length, servesDash: /web-dashboard/.test(s), mtime: st.mtime.toISOString() };
}

function appInfo(root) {
  const gradle = path.join(root, 'app', 'build.gradle.kts');
  let version = null;
  if (fs.existsSync(gradle)) {
    const s = fs.readFileSync(gradle, 'utf8');
    const vn = (s.match(/versionName\s*=\s*"([^"]+)"/) || [null, null])[1];
    const vc = (s.match(/versionCode\s*=\s*(\d+)/) || [null, null])[1];
    version = 'v' + (vn || '؟') + ' / code ' + (vc || '؟');
  }
  const base = path.join(root, 'app', 'src', 'main', 'java', 'com', 'remotebridge');
  let kt = 0;
  if (fs.existsSync(base)) (function walk(dir) { for (const e of fs.readdirSync(dir, { withFileTypes: true })) { const fp = path.join(dir, e.name); if (e.isDirectory()) walk(fp); else if (e.name.endsWith('.kt')) kt++; } })(base);
  const state = fs.existsSync(path.join(root, 'project_state.md'));
  return { version, ktFiles: kt, hasState: state };
}

const targets = [
  ['جلستنا (المرجع)', n => n === 'opencodeGLM5.3FLASHA'],
  ['جلسة Advanced', n => n === 'opencodeGLM5.3FLASHAAdvanced'],
  ['جلسة GEMINI3.5F', n => /opencodeGLM5\.3FLASHAGEMINI3\.5F$/.test(n)],
  ['جلسة pro-studio', n => n === 'remotebridge-pro-studio by opencode'],
];

for (const [name, matcher] of targets) {
  const dir = pickDir(matcher);
  console.log('===== ' + name + ' =====');
  if (!dir) { console.log('  (لم يُعثر على المجلد)'); continue; }
  const root = path.join(BASE, dir);
  console.log('  اسم المجلد الحرفي: ' + dir);
  const d = analyze(root);
  if (d) {
    console.log('  اللوحة: ' + d.size + ' بايتاً | ' + d.lines + ' سطراً | آخر تعديل: ' + d.mtime);
    const present = Object.entries(d.feats).filter(([, v]) => v > 0).map(([k, v]) => k + '(' + v + ')');
    const absent = Object.entries(d.feats).filter(([, v]) => v === 0).map(([k]) => k);
    console.log('  ميزات حاضرة: ' + (present.join(', ') || 'لا شيء'));
    console.log('  ميزات غائبة: ' + (absent.join(', ') || 'لا شيء'));
  } else console.log('  اللوحة: مفقودة');
  const g = goInfo(root);
  if (g) console.log('  الخادم main.go: ' + g.size + ' بايتاً | ' + g.lines + ' سطراً | يخدم اللوحة: ' + (g.servesDash ? 'نعم' : 'لا'));
  else console.log('  الخادم main.go: مفقود');
  const a = appInfo(root);
  console.log('  التطبيق: إصدار ' + (a.version || 'غير متوفر') + ' | ملفات Kotlin: ' + a.ktFiles + ' | project_state.docحال: ' + (a.hasState ? 'موجود' : 'مفقود'));
  const files = fs.readdirSync(root);
  const apk = files.find(f => f.toLowerCase().endsWith('.apk')) || null;
  console.log('  APK في الجذر: ' + (apk || 'لا شيء'));
}

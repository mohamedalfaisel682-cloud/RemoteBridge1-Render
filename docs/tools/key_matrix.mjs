// key_matrix.mjs — مصفوفة مفاتيح الميزات الحساسة عبر الجلسات الأربع (المسارات الصحيحة)
import fs from 'fs';
import path from 'path';

const BASE = 'F:/Ai/agent folders/Editing By opencode';

function dirFor(matcher) {
  return fs.readdirSync(BASE, { withFileTypes: true })
    .filter(e => e.isDirectory())
    .map(e => e.name)
    .find(n => matcher(n.normalize('NFC').replace(/[\u200e\u200f]/g, '')));
}

const rows = [
  ['جلستنا (المرجع)', () => 'opencodeGLM5.3FLASHA'],
  ['جلسة Advanced', () => 'opencodeGLM5.3FLASHAAdvanced'],
  ['جلسة GEMINI3.5F', () => dirFor(n => /FLASHAGEMINI3\.5F$/.test(n))],
  ['جلسة pro-studio', () => 'remotebridge-pro-studio by opencode'],
];

const checks = [
  ['قفل-الكاميرا-المتزامن', 'app/src/main/java/com/remotebridge/core/webrtc/PeerConnectionManager.kt', 'cameraSwitchBusy'],
  ['حفظ-مرجع-الصوت', 'app/src/main/java/com/remotebridge/core/webrtc/PeerConnectionManager.kt', 'audioSource'],
  ['إعادة-الربط-القسري', 'app/src/main/java/com/remotebridge/core/signaling/SignalingClient.kt', 'forceReconnect'],
  ['حرس-التعافي-الحي', 'web-dashboard/index.html', 'rbStreamRec'],
  ['المشغل-العريض', 'web-dashboard/index.html', 'rb-mp-'],
  ['استوديو-ثالث', 'web-dashboard/index.html', 'rb-studio-v3'],
  ['الموجّه-المركزي', 'web-dashboard/index.html', 'rbToggleStreamKind'],
];

for (const [name, resolver] of rows) {
  const dir = resolver();
  if (!dir) { console.log('==== ' + name + ' ==== مجلد غير موجود'); continue; }
  const root = path.join(BASE, dir);
  console.log('==== ' + name + ' ==== (' + dir + ')');
  for (const [label, file, needle] of checks) {
    const p = path.join(root, file);
    if (!fs.existsSync(p)) { console.log('  ' + label + ': ملف مفقود؛ المسار المفحوص ' + file); continue; }
    const s = fs.readFileSync(p, 'utf8');
    const n = s.split(needle).length - 1;
    if (n > 0) console.log('  ' + label + ': موجود (' + n + ' مرة)');
    else console.log('  ' + label + ': مفقود');
  }
  console.log('');
}

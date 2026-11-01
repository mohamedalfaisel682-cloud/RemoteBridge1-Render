// copy_apks.mjs - place rebuilt historical shells + B09 (reuse of B08 shell) into trees A+B
import fs from 'fs';
import path from 'path';

const ROOT = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
// [2026-11-01] مسار الخرج qabel للتمرير: node copy_apks.mjs <outDir> — الافتراضي rb_build_out بجانب المستودع
const OUT = process.argv[2] || path.join(ROOT, 'rb_build_out');

const MAP = [
  ['B01', '4e1d9ca', '20261024', 'v1.0.0', 'B01-app-debug.apk'],
  ['B02', '331be0d', '20261024', 'v1.0.0', 'B02-app-debug.apk'],
  ['B03', '561db36', '20261024', 'v1.0.0', 'B03-app-debug.apk'],
  ['B04', '2701ccd', '20261024', 'v1.0.0', 'B04-app-debug.apk'],
  ['B05', 'b9c5159', '20261025', 'v1.0.0', 'B05-app-debug.apk'],
  ['B08', '278c432', '20261025', 'v1.0.0', 'B08-app-debug.apk'],
  ['B09', 'f547639', '20261026', 'v1.0.0', 'B08-app-debug.apk'],   // Kotlin unchanged in B09 -> B08 shell is the valid historical build
  ['B11', 'f6f3076', '20261026', 'v1.2.0', 'B11-app-debug.apk'],
];

for (const [tag, sha, date, ver, src] of MAP) {
  const base = `RBstudioPRO_${tag}-${sha}_${date}_${ver.replace('.', '.')}`;
  for (const tree of ['apks versions', 'apks versions reverted']) {
    const dir = path.join(ROOT, tree, `${tag}-${sha}`);
    fs.mkdirSync(dir, { recursive: true });
    fs.copyFileSync(path.join(OUT, src), path.join(dir, `${base}.apk`));
  }
  const a = fs.statSync(path.join(ROOT, 'apks versions', `${tag}-${sha}`, `${base}.apk`));
  console.log(`${tag}: ${base}.apk (${a.size}B) -> trees A+B`);
}
console.log('APK_COPY_DONE');

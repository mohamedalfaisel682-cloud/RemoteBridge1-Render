// [تاريخي 2026-11-01] أداة المزامنة قبل توحيد المرجع — عملت على rb_phase2_git (مُلغي) ونسخة مرآة -copy.
// بقيت للتوثيق فقط؛ لإعادة الاستخدام مرّر مسارات جديدة في الثوابت CLONE/M1/M2 أدناه.
import fs from 'fs';
import { createHash } from 'crypto';
import path from 'path';
import { execFileSync } from 'child_process';

const CLONE = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode/rb_phase2_git';
const M1 = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const M2 = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA-copy/opencodeGLM5.3FLASHA';

// 1) قائمة الملفات المتتبعة من git
const list = execFileSync('git', ['ls-files'], { cwd: CLONE, encoding: 'utf8' })
  .split(/\r?\n/).filter(Boolean);
console.log('TRACKED_FILES=' + list.length);

// 2) نسخ كل ملف من المستنسخ إلى المرايا
function copyTo(rel, dstRoot) {
  const dst = path.join(dstRoot, rel);
  fs.mkdirSync(path.dirname(dst), { recursive: true });
  fs.copyFileSync(path.join(CLONE, rel), dst);
}
let copied = 0;
for (const rel of list) { copyTo(rel, M1); copyTo(rel, M2); copied++; }
console.log('COPIED=' + copied);

// 3) مقارنة SHA256 بمحتوى مطبَّع (تسوية نهايات الأسطر)
const norm = (buf) => {
  let s = buf.toString('utf8');
  return s.replace(/\r\n/g, '\n');
};
const sha = (s) => createHash('sha256').update(s, 'utf8').digest('hex');
let diffs = [];
for (const rel of list) {
  const base = fs.readFileSync(path.join(CLONE, rel));
  const b = sha(norm(base));
  for (const [name, root] of [['M1', M1], ['M2', M2]]) {
    const p = path.join(root, rel);
    if (!fs.existsSync(p)) { diffs.push(name + ' MISSING ' + rel); continue; }
    const h = sha(norm(fs.readFileSync(p)));
    if (h !== b) diffs.push(name + ' DIFF ' + rel);
  }
}
console.log('NORMALIZED_DIFFS=' + diffs.length);
diffs.slice(0, 20).forEach(d => console.log('  ' + d));

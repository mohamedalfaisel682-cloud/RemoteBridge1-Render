// [تاريخي 2026-11-01] أداة تدقيق جلسة إلغاء المساحات المؤقتة — الخلاصة بعد تسوية الأسطر: كل شجرة HTML مطابقة لالتزام التاريخ.
// tree_cmp.mjs — مقارنة شجرية نهائية بين rb_phase2_git (شجرة عمل) والمستودع الرئيسي
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';

const mainRepo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const tmpRepo = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode/rb_phase2_git';
const skip = /(^|[\\/])(\.git|\.gradle|\.kotlin|build|\.idea)([\\/]|$)/i;

function walk(dir, out) {
  let ents; try { ents = readdirSync(dir, { withFileTypes: true }); } catch (_) { return; }
  for (const e of ents) {
    const p = join(dir, e.name);
    if (skip.test(p)) continue;
    if (e.isDirectory()) walk(p, out);
    else if (e.isFile()) { try { out.set(relative(dir, p).replace(/[\\]/g, '/'), createHash('sha256').update(readFileSync(p)).digest('hex')); } catch (_) {} }
  }
  return out;
}

const mainMap = walk(mainRepo, new Map());
const tmpMap = walk(tmpRepo, new Map());

// 1) أربط المسارات المختلفة الرئيسي<->المؤقت (نفس البنية)
const onlyMain = [], onlyTmp = [], diff = []; let same = 0;
for (const [p, h] of mainMap) {
  if (!tmpMap.has(p)) onlyMain.push(p);
  else if (tmpMap.get(p) === h) same++;
  else diff.push(p);
}
for (const [p] of tmpMap) if (!mainMap.has(p)) onlyTmp.push(p);

console.log('main files: ' + mainMap.size + ' | tmp files: ' + tmpMap.size);
console.log('identical: ' + same);
console.log('diff content: ' + diff.length);
console.log('only in main: ' + onlyMain.length);
console.log('only in tmp: ' + onlyTmp.length);

if (diff.length) { console.log('--- content diff ---'); for (const d of diff) console.log('  ' + d); }
if (onlyMain.length) { console.log('--- only in main (first 40) ---'); for (const d of onlyMain.slice(0, 40)) console.log('  ' + d); }
if (onlyTmp.length) { console.log('--- only in tmp (first 40) ---'); for (const d of onlyTmp.slice(0, 40)) console.log('  ' + d); }

// 2) أداة فحص HEAD: هل شجرة العمل في tmp مطابقة HEAD؟
const dirty = execFileSync('git', ['-C', tmpRepo, 'status', '--porcelain'], { encoding: 'utf8' });
console.log('tmp repo status: ' + (dirty.trim() ? 'DIRTY' : 'CLEAN'));
if (dirty.trim()) console.log(dirty);

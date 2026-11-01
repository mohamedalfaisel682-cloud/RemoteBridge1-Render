// [تاريخي 2026-11-01] أداة ما قبل إلغاء مجلدات Temp — نسخة محسّمة بتسوية نهايات الأسطر (النتيجة: المطابقة مؤكدة تكليفياً).
// pre_del_check2.mjs — إعادة فحص rb_wt_* HTML مقابل التاريخ بعد تسوية نهايات الأسطر
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';

const repo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const tmpRoot = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode';
const norm = (buf) => buf.toString('utf8').replace(/\r\n/g, '\n');
const shaN = (p) => createHash('sha256').update(norm(readFileSync(p)), 'utf8').digest('hex');

const commits = execFileSync('git', ['-C', repo, 'log', '--all', '--format=%H', '--', 'web-dashboard/index.html'], { encoding: 'utf8' })
  .split(/\r?\n/).filter(l => /^[0-9a-f]{40}$/.test(l));
console.log('commits touching index.html: ' + commits.length);

for (const wt of ['B01','B02','B03','B04','B05','B08','B11']) {
  const p = `${tmpRoot}/rb_wt_${wt}/web-dashboard/index.html`;
  if (!existsSync(p)) { console.log(`rb_wt_${wt}: ABSENT`); continue; }
  const h = shaN(p);
  let matched = null;
  for (const c of commits) {
    try {
      const b = execFileSync('git', ['-C', repo, 'show', `${c}:web-dashboard/index.html`], { maxBuffer: 32 * 1024 * 1024 });
      if (createHash('sha256').update(norm(b), 'utf8').digest('hex') === h) { matched = c.slice(0, 8); break; }
    } catch (_) {}
  }
  console.log(`rb_wt_${wt}: ${matched ? 'MATCH-NORM ' + matched : 'REAL-UNIQUE'}`);
}

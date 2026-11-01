// [تاريخي 2026-11-01] أداة تدقيق جلسة إلغاء المساحات المؤقتة — المسارات المؤقتة أُلغيت؛ النتيجة المؤكدة في pre_del_check2.
// wt_hist.mjs — فحص مطابقة شجرة worktree الأرشيفية مع التاريخ الكامل في git (الرئيسي)
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const repo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

// 1) كل الالتزامات (كل التاريخ) — أي التزامات متاحة في التاريخ؟
const commits = execFileSync('git', ['-C', repo, 'log', '--all', '--format=%H %h %s'], { encoding: 'utf8' })
  .split(/\r?\n/).filter(l => /^[0-9a-f]{40}/.test(l));
console.log('total commits in history: ' + commits.length);
console.log('--- worktree tree vs history ---');
const wtDirs = ['rb_wt_B01','rb_wt_B02','rb_wt_B03','rb_wt_B04','rb_wt_B05','rb_wt_B08','rb_wt_B11'];
const wtBase = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode';
for (const wt of wtDirs) {
  const p = wtBase + '/' + wt + '/web-dashboard/index.html';
  let h;
  try { h = sha(readFileSync(p)); } catch (_) { console.log(wt + ': READ-FAIL'); continue; }
  // Check against all commits' index.html
  let matched = null;
  for (const line of commits) {
    const c = line.split(' ')[0];
    try {
      const b = execFileSync('git', ['-C', repo, 'show', `${c}:web-dashboard/index.html`], { maxBuffer: 32 * 1024 * 1024 });
      if (sha(b) === h) { matched = c.slice(0, 8); break; }
    } catch (_) { /* file not present in that commit */ }
  }
  console.log(wt + ': tmpsha16=' + h.slice(0, 16) + ' | ' + (matched ? 'MATCH-HIST ' + matched : 'NO-MATCH-IN-HIST'));
}

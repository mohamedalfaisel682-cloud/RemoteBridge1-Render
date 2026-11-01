// [تاريخي 2026-11-01] أداة ما قبل إلغاء مجلدات Temp — النتيجة الحقوقية الأولى أُفسِّرت بالسطر النهائي (فروق CRLF خالصة).
// pre_del_check.mjs — فحوص أمان نهائية قبل حذف مجلدات Temp
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';

const repo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const tmpRoot = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode';
const sha = (buf) => createHash('sha256').update(buf).digest('hex');
const shalite = (p) => sha(readFileSync(p));

// 1) HTML شجرات rb_wt_* مقابل blob التاريخ في المستودع الرئيسي
console.log('== rb_wt_* web-dashboard/index.html vs git history ==');
for (const wt of ['B01','B02','B03','B04','B05','B08','B11']) {
  const p = `${tmpRoot}/rb_wt_${wt}/web-dashboard/index.html`;
  if (!existsSync(p)) { console.log(`rb_wt_${wt}: ABSENT`); continue; }
  const h = shalite(p);
  let matched = false;
  const commits = execFileSync('git', ['-C', repo, 'log', '--all', '--format=%H', '--', 'web-dashboard/index.html'], { encoding: 'utf8' })
    .split(/\r?\n/).filter(l => /^[0-9a-f]{40}$/.test(l));
  for (const c of commits) {
    try {
      const b = execFileSync('git', ['-C', repo, 'show', `${c}:web-dashboard/index.html`], { maxBuffer: 32 * 1024 * 1024 });
      if (sha(b) === h) { console.log(`rb_wt_${wt}: MATCH ${c.slice(0,8)}`); matched = true; break; }
    } catch (_) {}
  }
  if (!matched) console.log(`rb_wt_${wt}: NO-MATCH tmpsha16=${h.slice(0,16)}`);
}

// 2) APK لقطة rb_toastsafe مقابل APK المستودع الرئيسي
const snapApk = `${tmpRoot}/rb_toastsafe/RBstudioPRO_v1.3.0.apk`;
const repoApk = `${repo}/RBstudioPRO_v1.3.0.apk`;
if (existsSync(snapApk) && existsSync(repoApk)) {
  const same = shalite(snapApk) === shalite(repoApk);
  console.log('rb_toastsafe APK vs repo APK: ' + (same ? 'IDENTICAL' : 'DIFFERENT'));
} else console.log('APK check: file(s) absent');

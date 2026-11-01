// verify_archive.mjs - final verification of both archive trees + repo worktree state
import fs from 'fs';
import path from 'path';
import { execSync } from 'child_process';

const ROOT = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const lines = [];

for (const tree of ['apks versions', 'apks versions reverted']) {
  const base = path.join(ROOT, tree);
  const dirs = fs.readdirSync(base).filter(d => fs.statSync(path.join(base, d)).isDirectory()).sort();
  let files = 0, bytes = 0;
  const rows = [];
  for (const d of dirs) {
    const inner = fs.readdirSync(path.join(base, d));
    let dFiles = 0, dBytes = 0;
    for (const f of inner) {
      const st = fs.statSync(path.join(base, d, f));
      if (st.isFile()) { dFiles++; dBytes += st.size; }
    }
    files += dFiles; bytes += dBytes;
    rows.push(`${d.padEnd(15)} files=${dFiles}  ${(dBytes / 1048576).toFixed(1)}MB  [${inner.sort().join(', ').slice(0, 200)}]`);
  }
  lines.push(`=== ${tree}: dirs=${dirs.length} files=${files} total=${(bytes / 1048576).toFixed(1)}MB`);
  lines.push(...rows);
}
const manifest = fs.statSync(path.join(ROOT, 'apks versions', 'MANIFEST.md'));
lines.push(`MANIFEST.md: ${manifest.size}B`);

// worktrees: expect only the main one (المستودع الرئيسي بعد توحيده 2026-11-01)
const wt = execSync('git worktree list --porcelain', { cwd: ROOT }).toString();
lines.push('WORKTREES=' + wt.trim().split('\n').filter(l => l.startsWith('worktree ')).map(l => l.slice(9)).join(' | '));

fs.writeFileSync(path.join(ROOT, 'docs', 'tools', 'verify_out.txt'), lines.join('\n'), 'utf8');
console.log('WROTE verify_out.txt, trees checked');

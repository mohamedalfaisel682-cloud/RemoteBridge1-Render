// hist_match.mjs v2 — فحص مطابقة لقطة مع التاريخ الكامل في git
// [2026-11-01] عامة: node hist_match.mjs <snapshot-root> (قائمة `targets` أدناه ما زالت بنية لقطة RBstudioPRO)
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';

const repo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const tmp = process.argv[2] || '';
if (!tmp || !existsSync(tmp)) {
  console.error('usage: node hist_match.mjs <snapshot-root> — e.g. ' + repo);
  process.exit(1);
}
const targets = [
  'app/src/main/java/com/remotebridge/core/webrtc/PeerConnectionManager.kt',
  'app/src/main/java/com/remotebridge/host/service/HostForegroundService.kt',
  'app/src/main/AndroidManifest.xml',
  'app/src/main/java/com/remotebridge/core/model/Models.kt',
  'app/src/main/java/com/remotebridge/host/provider/HostDataProvider.kt',
  'app/src/main/java/com/remotebridge/ui/screens/host/HostScreen.kt',
  'app/src/main/java/com/remotebridge/ui/screens/settings/SettingsScreen.kt',
  'app/build.gradle.kts',
  'main.go',
  'Dockerfile',
  'web-dashboard/index.html',
  'docs/phase-two-progress-ar.md',
  'docs/batch-b16-ar.md',
  'project_state.md',
  'agents.md',
  'skill.md',
];
const sha = (buf) => createHash('sha256').update(buf).digest('hex');

// 1) بصمات التاريخ لكل مسار (كل الالتزامات)
const hist = {};
for (const gitPath of targets) {
  const commits = execFileSync('git', ['-C', repo, 'log', '--all', '--format=%H', '--', gitPath], { encoding: 'utf8' })
    .split(/\r?\n/).filter(l => /^[0-9a-f]{40}$/.test(l));
  const blobs = new Set();
  for (const c of commits) {
    try {
      const b = execFileSync('git', ['-C', repo, 'show', `${c}:${gitPath}`], { maxBuffer: 64 * 1024 * 1024 });
      blobs.add(sha(b));
    } catch (_) {}
  }
  hist[gitPath] = { commits: commits.length, blobs };
  console.log(`HIST ${gitPath}: commits=${commits.length} uniqueBlobs=${blobs.size}`);
}

// 2) مطابقة اللقطة
console.log('--- snapshot matching ---');
let unmatched = 0;
for (const gitPath of targets) {
  const tmpPath = tmp + '/' + gitPath;
  if (!existsSync(tmpPath)) { console.log(`SNAP ${gitPath}: ABSENT-IN-SNAP`); continue; }
  const h = sha(readFileSync(tmpPath));
  const inHist = hist[gitPath] && hist[gitPath].blobs.has(h);
  let inCur = false;
  try { inCur = sha(execFileSync('git', ['-C', repo, 'show', `HEAD:${gitPath}`], { maxBuffer: 64 * 1024 * 1024 })) === h; } catch (_) {}
  if (!inHist && !inCur) unmatched++;
  console.log(`SNAP ${gitPath}: tmpsha16=${h.slice(0,16)} ${inHist ? 'MATCH-HIST' : ''} ${inCur ? 'MATCH-HEAD' : ''} ${(!inHist && !inCur) ? 'NO-MATCH' : ''}`);
}
console.log(unmatched === 0 ? 'ALL SNAPSHOT FILES MATCH GIT HISTORY' : 'UNMATCHED FILES: ' + unmatched);

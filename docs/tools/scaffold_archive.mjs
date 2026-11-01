// scaffold_archive.mjs - create archive trees, extract authentic controller htmls (raw bytes),
// copy v1.3.0 shell APK for B12/B15/B16, and write ASCII git-ref.txt for all folders.
// Working solely from the git repo objects - no PowerShell, no stdin redirection.
import fs from 'fs';
import path from 'path';
import { spawnSync } from 'child_process';

const ROOT = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const REPO = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const APK_V130 = path.join(ROOT, 'RBstudioPRO_v1.3.0.apk');
const TREE_A = 'apks versions';
const TREE_B = 'apks versions reverted';
const PATH_IN_TREE = 'web-dashboard/index.html';

const VERSIONS = [
  { tag: 'B01', sha: '4e1d9ca', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'studio tab + auto-connect/fetch + host flash/status + saved hosts + auto-scan toggle + P2P diagnostics fix' },
  { tag: 'B02', sha: '331be0d', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'stream blank-window root-cause fix + click-to-connect + snapshot/recording engine' },
  { tag: 'B03', sha: '561db36', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'host permissions hardened (FGS types + camera/mic permission gating for streams)' },
  { tag: 'B04', sha: '2701ccd', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'Link WebRTC button replacing Connection Test + P2P board collapsed + vault' },
  { tag: 'B05', sha: 'b9c5159', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'live WebRTC streaming root-cause fix (host acceptOffer rework + sender transceivers)' },
  { tag: 'B08', sha: '278c432', ver: '1.0.0', artifact: 'rebuilt-from-this-commit',
    role: 'DataChannel content pipeline (storage_open/vault_open + chunk finalize)' },
  { tag: 'B09', sha: 'f547639', ver: '1.0.0', artifact: 'reuse-B08-shell (Kotlin unchanged in B09)',
    role: 'live-stream UI redesign (source-cards grid + real P2P connectionState/dataChannel chip)' },
  { tag: 'B11', sha: 'f6f3076', ver: '1.2.0', artifact: 'rebuilt-from-this-commit',
    role: 'embedded web dashboard inside the app (WebDashboardScreen + RBAndroid JS bridge)' },
  { tag: 'B12', sha: '4a19706', ver: '1.3.0', artifact: 'copy-of-saved-RBstudioPRO_v1.3.0.apk (this batch built it)',
    role: 'connection stability v1.3.0 (anti-reconnect race + forceReconnect + network-back watcher)' },
  { tag: 'B15', sha: 'a86a357', ver: '1.3.0', artifact: 'copy-of-saved-RBstudioPRO_v1.3.0.apk (shell unchanged since B12)',
    role: 'auto-connect to active host without click + 1-minute re-check cadence' },
  { tag: 'B16', sha: '5c03d2e', ver: '1.3.0', artifact: 'copy-of-saved-RBstudioPRO_v1.3.0.apk (shell unchanged since B12)',
    role: 'WebRTC announce stability + built-in broadcast engine (broadcastViewMode) - latest, live-verified successful connection' }
];

const LINEAGE = 'ae94371 (pre-window) > 4e1d9ca (B01) > 331be0d (B02) > 561db36 (B03) > 2701ccd (B04) > b9c5159 (B05) > 76aedd2 (B06) > 31f8e7a (B07) > 432467c (B7c) > 278c432 (B08) > 0c02233 (B10) > f547639 (B09) > f6f3076 (B11) > eb04b44 (upload) > 4a19706 (B12) > 2b82b8d (B13) > 2ab134a (B14) > 1758374 > 6a8912b > a86a357 (B15) > 5c03d2e (B16)';

function git(args) {
  const r = spawnSync('git', args, { cwd: REPO });
  if (r.status !== 0) {
    throw new Error('git ' + args.join(' ') + ' FAILED: ' + r.stderr.toString().slice(0, 300));
  }
  return r.stdout; // Buffer (raw bytes)
}
function gitStr(args) {
  return git(args).toString('utf8').trim();
}

const report = [];
for (const v of VERSIONS) {
  const parent = gitStr(['rev-parse', '--short', v.sha + '^']);
  const iso = gitStr(['show', '-s', '--format=%cI', v.sha]);
  const ymd = iso.slice(0, 4) + iso.slice(5, 7) + iso.slice(8, 10);
  const hm = iso.slice(11, 16);
  const subject = gitStr(['show', '-s', '--format=%s', v.sha]).replace(/\s*\|.*$/, '');
  const base = `RBstudioPRO_${v.tag}-${v.sha}_${ymd}_${v.ver === '1.0.0' ? 'v1.0.0' : 'v' + v.ver}`;
  const dirA = path.join(ROOT, TREE_A, `${v.tag}-${v.sha}`);
  const dirB = path.join(ROOT, TREE_B, `${v.tag}-${v.sha}`);
  fs.mkdirSync(dirA, { recursive: true });
  fs.mkdirSync(dirB, { recursive: true });

  // 1) authentic controller html (raw bytes, no re-encoding)
  const html = git(['show', `${v.sha}:${PATH_IN_TREE}`]);
  const htmlA = path.join(dirA, base + '.html');
  fs.writeFileSync(htmlA, html);

  // 2) APK copies where the shell artifact == saved v1.3.0 file
  let apkNote = 'APK: (pending - historical rebuild in progress)';
  if (v.artifact.startsWith('copy-of-saved')) {
    const apkName = base + '.apk';
    fs.copyFileSync(APK_V130, path.join(dirA, apkName));
    fs.copyFileSync(APK_V130, path.join(dirB, apkName));
    apkNote = 'APK: copied (saved v1.3.0 shell) into trees A and B';
  }

  // 3) git-ref.txt for tree A (original reference)
  const refA = [
    'RBstudioPRO archive - original version reference (tree A)',
    '==========================================================',
    'Tag            : ' + v.tag,
    'Commit (short) : ' + v.sha,
    'Parent         : ' + parent,
    'Commit date    : ' + iso.slice(0, 10) + ' ' + hm + '  (ISO: ' + iso + ')',
    'Subject        : ' + subject.slice(0, 118),
    'Batch role     : ' + v.role,
    'Shell (APK)    : versionName ' + v.ver + ' | ' + v.artifact,
    'Files here     : ' + base + '.apk',
    '                 ' + base + '.html   (= web-dashboard/index.html exactly at this commit)',
    apkNote,
    '',
    'Verify the commit date:',
    '  git show -s --format="%h %cI %s" ' + v.sha,
    '',
    'Return to this version (reconstruct files):',
    '  git worktree add --detach restore-' + v.tag + ' ' + v.sha,
    '  cd restore-' + v.tag,
    '  :: controller is already restored as web-dashboard/index.html by the worktree',
    '  gradlew.bat :app:assembleDebug   :: rebuild the app shell of this snapshot (optional)',
    '',
    'Lineage (oldest to newest):',
    '  ' + LINEAGE
  ].join('\r\n');
  fs.writeFileSync(path.join(dirA, 'git-ref.txt'), refA);

  // 4) git-ref.txt for tree B (relative revert reference; html arrives after revert surgery)
  const refB = [
    'RBstudioPRO archive - RELATIVE REVERT VARIANT (tree B: editable when needed)',
    '============================================================================',
    'Definition: the controller here = controller of version ' + v.tag,
    'with ONLY ' + v.tag + "'s own WebRTC-link + live-broadcast feature edits removed",
    '(inverse-applied restricted feature hunks of git diff ' + parent + '..' + v.sha + ' on ' + PATH_IN_TREE + ').',
    "ALL other edits of " + v.tag + ' are kept exactly as they were.',
    '',
    'IMPORTANT: ' + base + '.apk here is the SAME app shell as tree A. The app-side',
    '(Kotlin) WebRTC edits of ' + v.tag + ' are NOT reverted in this build. The revert',
    'targets the controller file only; app-side paths for manual revert are in',
    'MANIFEST.md of tree A.',
    '',
    'Tag            : ' + v.tag,
    'Commit (short) : ' + v.sha + '   Parent: ' + parent,
    'Commit date    : ' + iso.slice(0, 10) + ' ' + hm,
    'Shell (APK)    : versionName ' + v.ver + ' | ' + v.artifact,
    'Must-vanish / must-remain verification: see revert_report.txt (written after surgery)',
    '',
    'Lineage (oldest to newest):',
    '  ' + LINEAGE
  ].join('\r\n');
  fs.writeFileSync(path.join(dirB, 'git-ref.txt'), refB);

  report.push(`${v.tag} ${v.sha} parent=${parent} date=${iso.slice(0, 10)} ${hm} html=${html.length}B base=${base}`);
}

console.log(report.join('\n'));
console.log('SCAFFOLD_DONE');

// [تاريخي 2026-11-01] أداة تدقيق جلسة إلغاء المساحات المؤقتة — قست الفروق قبل إلغاء rb_phase2_git (لا فرق محتوى، CRLF خالص).
// crlf_cmp.mjs — مقارنة كل الفروق بين المستودع الرئيسي وrb_phase2_git بعد تسوية نهايات الأسطر
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const mainRepo = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const tmpRepo = 'C:/Users/alfaisalytec/AppData/Local/Temp/opencode/rb_phase2_git';
const files = [
  'agents.md', 'skill.md', 'project_state.md', 'commit_msg.txt', 'extract_check.mjs',
  'docs/batch-b11-ar.md', 'docs/batch-b13-ar.md', 'docs/batch-b14-ar.md', 'docs/batch-b15-ar.md', 'docs/batch-b16-ar.md',
  'docs/phase-two-progress-ar.md', 'docs/system-full-report-ar.md', 'docs/versions-archive-b16-ar.md', 'docs/webrtc-analysis-b8-ar.md',
  'docs/البيانات التفصيلية والتحليلية لRBstudio README.md',
  'app/src/main/AndroidManifest.xml', 'app/src/main/java/com/remotebridge/core/data/AppModePrefs.kt',
  'app/src/main/java/com/remotebridge/core/webrtc/PeerConnectionManager.kt',
  'app/src/main/java/com/remotebridge/host/HostVault.kt',
  'app/src/main/java/com/remotebridge/MainActivity.kt',
  'app/src/main/java/com/remotebridge/ui/navigation/Navigation.kt',
  'app/src/main/java/com/remotebridge/ui/screens/dashboard/DashboardScreen.kt',
  'app/src/main/java/com/remotebridge/ui/screens/home/HomeScreen.kt',
  'app/src/main/java/com/remotebridge/ui/screens/host/HostScreen.kt',
  'app/src/main/java/com/remotebridge/ui/screens/host/HostPermissionsSection.kt',
  'app/src/main/java/com/remotebridge/ui/screens/settings/SettingsScreen.kt',
  'app/src/main/java/com/remotebridge/ui/screens/host/WebDashboardScreen.kt',
  'app/src/main/java/com/remotebridge/ui/theme/UiThemePrefs.kt',
  'app/src/main/java/com/remotebridge/ui/viewmodel/SettingsViewModel.kt',
  'app/src/main/res/drawable/ic_launcher_background.xml',
  'app/src/main/res/drawable/ic_launcher_foreground.xml',
  'app/src/main/res/values/strings.xml',
];
const sha = (buf) => createHash('sha256').update(buf).digest('hex');
let realDiff = 0, crlfOnly = 0, same = 0;
for (const f of files) {
  let a, b;
  try { a = readFileSync(mainRepo + '/' + f); } catch (_) { console.log('MISSING-MAIN ' + f); continue; }
  try { b = readFileSync(tmpRepo + '/' + f); } catch (_) { console.log('MISSING-TMP ' + f); continue; }
  if (a.equals(b)) { same++; console.log('SAME ' + f); continue; }
  const an = a.toString('utf8').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const bn = b.toString('utf8').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (an === bn) { crlfOnly++; console.log('CRLF-ONLY ' + f); continue; }
  // real content diff — find first differing line
  const al = an.split('\n'), bl = bn.split('\n');
  let i = 0;
  while (i < Math.min(al.length, bl.length) && al[i] === bl[i]) i++;
  realDiff++;
  console.log('REAL-DIFF ' + f + ' | first diff line ' + (i + 1) + ':');
  console.log('   main: ' + (al[i] || '<EOF>').slice(0, 120));
  console.log('   tmp : ' + (bl[i] || '<EOF>').slice(0, 120));
}
console.log('=== SUMMARY: same=' + same + ' crlfOnly=' + crlfOnly + ' realDiff=' + realDiff + ' ===');

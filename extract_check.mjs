// extract_check.mjs — فحص معياري: استخراج كل كتل <script> من ملفات واجهة المتحكم
// والتحقق من صياغتها عبر node --check، ثم تحقق من وجود مفاتيح كلِّ ملف.
// الاستخدام: node extract_check.mjs [file1.html file2.html ...]
// الافتراضي: web-dashboard/index.html + web-dashboard/indexMergeFull.html (إن وُجد).
// الناتج المطلوب: NODE_CHECK=PASS عند النجاح.
import { readFileSync, writeFileSync, mkdtempSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';

const BASELINE_PATH = 'web-dashboard/indexMergeFull.sha256';
const rawArgs = process.argv.slice(2);
const updateBaseline = rawArgs.includes('--update-baseline');
const fileArgs = rawArgs.filter(a => !a.startsWith('--'));
const defaultTargets = ['web-dashboard/index.html', 'web-dashboard/indexMergeFull.html'].filter(existsSync);
const targets = fileArgs.length ? fileArgs : defaultTargets;
if (!targets.length) {
    console.log('NODE_CHECK=FAIL (no dashboard target found)');
    process.exit(1);
}

// مفاتيح الواجهات والموجّه المركزي (وجودها لازم في الأساس والمدمج معاً)
const baseKeys = [
    'rbToggleStreamKind', 'rbStopAllStreams', 'rbStopLocalStateOnly', 'rbStartStreamCore',
    'renderStudioLiveView', 'rb-studio-video', 'rb-studio-audio', 'rbModeSwitchButtons',
    'rbRebindMediaElements', 'rbLiveClockEnsure', 'rbAudioLevelFeedStart',
    'rbBcStart', 'rbBcStop', 'rbBcToggleAudio', 'renderBroadcastProPane',
    'rbMpFullscreen', 'rbMpPiP', 'rbMpToggleFit',
];
// مفاتيح ميزات الدمج B24 (لازمة في المدمج حصراً)
const b24Keys = [
    'renderQuickPane', 'openSettingsGroupTab', 'renderCommandsView',
    'maybeInitialSettingsSync', 'pushQuickOptsSync', 'pullHostSettingsSync',
    'handleIncomingSettingsSync', 'QUICK_OPTS_CARD_HTML', 'cmd-sms-number', 'schedules-box',
    'seedFromHostApp', 'case "settings_sync":',
    // B25 — آليات الاتصال المنقولة: عارض الترحيل + مبدّل الواجهات القديمة
    'case "live_frame":', 'function handleLiveFrame(',
    'legacy-iface-select', 'mrgLegacyIfaceSwitch',
    // B28 — اشتقاق نقاط النهاية وقت التشغيل وقائمة المرشحات (لا سحابة مثبتة)
    'deriveOriginServerUrl', 'serverCandidates', 'connectSignalingServer',
    'onServerUrlEdited', 'setServerScope', 'cfg-server-scope', 'cfg-active-server', 'isLocalWsUrl',
    'scheduleSignalingRetry',
    // TURN اختياري — الحقول والتهيئة
    'turnUrl', 'turnUsername', 'turnCredential',
    'cfg-turn-url', 'cfg-turn-username', 'cfg-turn-credential',
    // B27 — آليات البث الحيّ المستعادة: المسجّل والقوائم وأدوات المسرح والنقّاب
    'function toggleRecordStream(', 'function enhanceCameraTab(',
    'function refreshCameraRecordsBox(', 'function addCallRecordingsSection(',
    'onclick="toggleRecordStream()"', 'id="records-cam-box"', 'mrgEnhanceSweep',
    // B29 — تفصيل أذونات المضيف الكامل المستعلم مباشرة (permission_status + اللوحة الجديدة)
    'function renderHostPermsFull(', 'function refreshHostPermissionsDetail(',
    "cmd === 'permission_status'", 'host-perm-detail',
];

let failed = 0;
const tmp = mkdtempSync(join(tmpdir(), 'rb-extract-'));
for (const htmlPath of targets) {
    const raw = readFileSync(htmlPath);
    const html = raw.toString('utf8');
    const hash = createHash('sha256').update(raw).digest('hex');
    const blocks = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)];
    blocks.forEach((m, i) => {
        const code = m[1];
        if (!code || !code.trim()) return;
        const f = join(tmp, htmlPath.replace(/[\\/]/g, '_') + '-block-' + i + '.js');
        writeFileSync(f, code, 'utf8');
        try {
            execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
        } catch (e) {
            failed++;
            console.log(`FAIL [${htmlPath}] block ${i}:\n${String(e.stderr || e.message).slice(0, 2000)}`);
        }
    });
    // فحص علامات لا يجب أن توجد ضمن معالجات الأحداث (اقتباسات مكسورة)
    const attrFail = [];
    html.split('\n').forEach((l, i) => {
        if (/onclick="[^"]*[\u0600-\u06FF]/.test(l)) attrFail.push(`Arabic-in-attr L${i + 1}`);
    });

    const mustHave = baseKeys.concat(htmlPath.includes('indexMergeFull') ? b24Keys : []);
    const missing = mustHave.filter(m => !html.includes(m));

    console.log(`[${htmlPath}] script blocks: ${blocks.length}, missing keys: ${missing.length ? missing.join(', ') : 'none'}`);
    console.log(`[${htmlPath}] sha256: ${hash}`);
    if (attrFail.length) console.log(`[${htmlPath}] html attr warnings: ${attrFail.slice(0, 8).join(' | ')}`);
    if (missing.length) failed++;

    if (htmlPath.replace(/\\/g, '/').endsWith('indexMergeFull.html')) {
        if (updateBaseline) {
            writeFileSync(BASELINE_PATH, hash + '\n', 'utf8');
            console.log(`[baseline] updated: ${BASELINE_PATH}`);
        } else if (existsSync(BASELINE_PATH)) {
            const expected = readFileSync(BASELINE_PATH, 'utf8').trim().toLowerCase();
            if (expected && expected !== hash) {
                failed++;
                console.log(`[${htmlPath}] CANONICAL_HASH=FAIL expected=${expected} actual=${hash}`);
            } else {
                console.log(`[${htmlPath}] CANONICAL_HASH=PASS`);
            }
        } else {
            console.log(`[${htmlPath}] CANONICAL_HASH=SKIP (baseline missing)`);
        }
    }
}
console.log(failed === 0 ? 'NODE_CHECK=PASS' : 'NODE_CHECK=FAIL');
process.exit(failed === 0 ? 0 : 1);

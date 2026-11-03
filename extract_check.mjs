// extract_check.mjs — فحص معياري: استخراج كل كتل <script> من ملفات واجهة المتحكم
// والتحقق من صياغتها عبر node --check، ثم تحقق من وجود مفاتيح كلِّ ملف.
// الاستخدام: node extract_check.mjs [file1.html file2.html ...]
// الافتراضي: web-dashboard/index.html + web-dashboard/indexMergeFull.html (إن وُجد).
// الناتج المطلوب: NODE_CHECK=PASS عند النجاح.
import { readFileSync, writeFileSync, mkdtempSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const args = process.argv.slice(2);
const targets = args.length ? args : (existsSync('web-dashboard/indexMergeFull.html')
    ? ['web-dashboard/index.html', 'web-dashboard/indexMergeFull.html']
    : ['web-dashboard/index.html']);

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
    // B27 — آليات البث الحيّ المستعادة: المسجّل والقوائم وأدوات المسرح والنقّاب
    'function toggleRecordStream(', 'function enhanceCameraTab(',
    'function refreshCameraRecordsBox(', 'function addCallRecordingsSection(',
    'onclick="toggleRecordStream()"', 'id="records-cam-box"', 'mrgEnhanceSweep',
];

let failed = 0;
const tmp = mkdtempSync(join(tmpdir(), 'rb-extract-'));
for (const htmlPath of targets) {
    const html = readFileSync(htmlPath, 'utf8');
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
    if (attrFail.length) console.log(`[${htmlPath}] html attr warnings: ${attrFail.slice(0, 8).join(' | ')}`);
    if (missing.length) failed++;
}
console.log(failed === 0 ? 'NODE_CHECK=PASS' : 'NODE_CHECK=FAIL');
process.exit(failed === 0 ? 0 : 1);

// extract_check.mjs — فحص معياري: استخراج كل كتل <script> من web-dashboard/index.html
// والتحقق من صياغتها عبر node --check، ثم تحقق من وجود مفاتيح B18 الأساسية.
// الناتج المطلوب: NODE_CHECK=PASS عند النجاح.
import { readFileSync, writeFileSync, mkdtempSync, unlinkSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const htmlPath = 'web-dashboard/index.html';
const html = readFileSync(htmlPath, 'utf8');
const blocks = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)];
let failed = 0;
const tmp = mkdtempSync(join(tmpdir(), 'rb-extract-'));
blocks.forEach((m, i) => {
    const code = m[1];
    if (!code || !code.trim()) return;
    const f = join(tmp, `block-${i}.js`);
    writeFileSync(f, code, 'utf8');
    try {
        execFileSync(process.execPath, ['--check', f], { stdio: 'pipe' });
    } catch (e) {
        failed++;
        console.log(`FAIL in block ${i}:\n${String(e.stderr || e.message).slice(0, 2000)}`);
    }
});
// فحص علامات لا يجب أن توجد داخل معالجات الحدث (الاقتباسات المكسورة)
const badPatterns = [
    ['onclick quote break', /onclick="[^"]*\{[^}]*\}/],
];
const htmlFail = [];
const lines = html.split('\n');
lines.forEach((l, i) => {
    if (/onclick="[^"]*[\u0600-\u06FF]/.test(l)) htmlFail.push(`Arabic-in-attr L${i + 1}`);
});

// مفاتيح الواجهات والموجّه المركزي
const mustHave = [
    'rbToggleStreamKind', 'rbStopAllStreams', 'rbStopLocalStateOnly', 'rbStartStreamCore',
    'renderStudioLiveView', 'rb-studio-video', 'rb-studio-audio', 'rbModeSwitchButtons',
    'rbRebindMediaElements', 'rbLiveClockEnsure', 'rbAudioLevelFeedStart',
    'rbBcStart', 'rbBcStop', 'rbBcToggleAudio', 'renderBroadcastProPane',
    'rbMpFullscreen', 'rbMpPiP', 'rbMpToggleFit',
];
const missing = mustHave.filter(m => !html.includes(m));

console.log('script blocks:', blocks.length);
console.log('missing keys:', missing.length ? missing.join(', ') : 'none');
console.log('html attr warnings:', htmlFail.length ? htmlFail.slice(0, 5).join(' | ') : 'none');
if (missing.length) failed++;
console.log(failed === 0 ? 'NODE_CHECK=PASS' : 'NODE_CHECK=FAIL');
process.exit(failed === 0 ? 0 : 1);

// merge_build.mjs — B24: بناء الواجهة النهائية المدمجة web-dashboard/indexMergeFull.html
// الأصل: الأساس web-dashboard/index.html (إعدادات البث الحي والاتصال معيار لا يُمسَّ)
// الإضافة: كتل منقولة حصراً من web-dashboard/indexMerge.html بنطاقات مثبتة بالقراءة الحرفية.
// B27: استعادة آليات البث الحيّ التي كانت أسقطةً بحاجز التوافق (مشغّل المسرح + مسجّل البث + قوائم التسجيلات).
// الأمان: لا يُنتج الملف إلا إذا اجتازت كلَّ مرساةٍ وكلَّ شواهدَ الخروج؛ وإلا أُبطل البناء برسالةٍ واضحة.
import { readFileSync, writeFileSync } from "node:fs";

const BASE_PATH = "web-dashboard/index.html";
const DONOR_PATH = "web-dashboard/indexMerge.html";
const OUT_PATH = "web-dashboard/indexMergeFull.html";

const baseText = readFileSync(BASE_PATH, "utf8");
const donorText = readFileSync(DONOR_PATH, "utf8");
const DL = donorText.split(/\r?\n/);
const seg = (L, a, b) => L.slice(a - 1, b).join("\n");

/** عدّاد حرفي لوجود مقطع في نص */
function countNeedle(text, needle) {
    let c = 0, i = 0;
    while ((i = text.indexOf(needle, i)) !== -1) { c++; i += needle.length; }
    return c;
}
/** تقسيم آمن بلا دلالة خاصة لرمز $ عند الاستبدال */
function spliceInsert(text, needle, insert, position) {
    const total = countNeedle(text, needle);
    if (total !== 1) {
        console.error("MERGE_ABORT: المرساة («" + needle.slice(0, 60) + "…») وُجدت " + total + " مرة والمتوقع 1");
        process.exit(2);
    }
    const at = text.indexOf(needle);
    return position === "before"
        ? text.slice(0, at) + insert + text.slice(at)
        : text.slice(0, at + needle.length) + insert + text.slice(at + needle.length);
}
const insertAfter = (t, n, s) => spliceInsert(t, n, s, "after");
const insertBefore = (t, n, s) => spliceInsert(t, n, s, "before");
/** حِقنة قبل آخر تواجد للمرساة (لتعدد كتل النمط في الأساس) */
const insertBeforeLast = (t, n, s) => {
    const at = t.lastIndexOf(n);
    if (at === -1) { console.error("MERGE_ABORT: المرساة غير موجودة في النص أصلاً"); process.exit(2); }
    return t.slice(0, at) + s + t.slice(at);
};

// ————— 1) الكتل المنقولة من واجهة الدمج (نطاقات مثبتة بالقراءة الحرفية) —————
const blockStreamCss = seg(DL, 44, 64);            // أصناف أزرار الأوامر والوقفات (.stream-btn و .stream-stop)
const blockSeed = seg(DL, 1272, 1299);             // بذر إعدادات التطبيق المضيف من معاملات العنوان (IIFE)
const blockEngine = seg(DL, 1595, 1764);           // محرك settings_sync كاملاً (لقطتان + دفع/سحب + مستمعو التبديل)
const helpersPart = seg(DL, 3055, 3077);           // fmtKb و fmtWhen و rpcWait
const blobToBase64 = seg(DL, 3106, 3112);          // blobToBase64
const blockCommandsRaw = seg(DL, 3351, 3697);      // تبويب الأوامر كاملاً (شامل SVG_ICON و cmdCard وغيرها)
const quickCard = seg(DL, 4867, 4894);             // بطاقة خيارات استمرارية تشغيل المضيف (قالب حيّ)

// B27 — كتلا البث الحيّ المستعادتان من الدونور (كانتا مسقطةً بدليل «الأساس أقوى»، ونفيت الحقيقةُ: لا بديلَ فعليَّ لهما في الأساس):
const blockStageVars = seg(DL, 3050, 3053);        // حالة الأدوات: recCamFront/recScreenMode/remoteRecorder/remoteRecorderChunks
const blockStageToolsRaw = seg(DL, 3114, 3349);    // مشغّل المسرح + تسجيل البث الآلي + قوائم تسجيلات الكاميرا/الشاشة/المكالمات

// حارس استدعاء دالة التسجيلات المسقطة داخل كتلة الأوامر (يُستبدل بنداء محمي)
const guardNeedle = "if (activeTab === 'camera') refreshCameraRecordsBox();";
if (countNeedle(blockCommandsRaw, guardNeedle) !== 1) {
    console.error("MERGE_ABORT: موضع الحارس refreshCameraRecordsBox لم يُعثر عليه كما ثبت سابقاً");
    process.exit(2);
}
const blockCommands =
    blockCommandsRaw.slice(0, blockCommandsRaw.indexOf(guardNeedle)) +
    "if (activeTab === 'camera') { try { if (typeof refreshCameraRecordsBox === 'function') refreshCameraRecordsBox(); } catch (_) {} }" +
    blockCommandsRaw.slice(blockCommandsRaw.indexOf(guardNeedle) + guardNeedle.length);

// B27 — مكييفات الدونور الحصرية (لا تُضاعِف أدواتٍ موجودةً في القاعدة):
// 1) المسرح في القاعدة هو rb-mp-stage (وليس aspect-video الخاص بالمحرّك البرمجي فقط).
// 2) زرّا اللقطة وملء الشاشة أُسكوتا لوجودهما فعلاً في محرك القاعدة (rbMpScreenshot و rbMpFullscreen) — أما تسجيل البث ففجوةٌ حقيقية.
const stageSelectorNeedle = "const stage = panel.querySelector('.aspect-video');";
const shotBtnNeedle =
    "                            <button onclick=\"captureStreamSnapshot()\" title=\"التقاط صورة من الفيديو المعروض الآن (تُحفظ في مجلدات الأرشيف)\" class=\"stream-btn flex items-center justify-center gap-1 px-2 py-1.5 rounded-lg text-[10px] font-bold backdrop-blur bg-black/50 border border-slate-700\">\n" +
    "                                <svg viewBox=\"0 0 24 24\" class=\"w-3.5 h-3.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><rect x=\"3\" y=\"6\" width=\"18\" height=\"14\" rx=\"2\"/><path d=\"M12 10a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM9 6l1.2-2h3.6L15 6\"/></svg>\n" +
    "                                <span>لقطة</span>\n" +
    "                            </button>\n";
const fullscreenBtnNeedle =
    "                            <button onclick=\"openStageFullscreen()\" title=\"تكبير العرض لملء الشاشة\" class=\"stream-btn flex items-center justify-center gap-1 px-2 py-1.5 rounded-lg text-[10px] font-bold backdrop-blur bg-black/50 border border-slate-700\">\n" +
    "                                <svg viewBox=\"0 0 24 24\" class=\"w-3.5 h-3.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5\"/></svg>\n" +
    "                                <span>ملء الشاشة</span>\n" +
    "                            </button>\n";
if (countNeedle(blockStageToolsRaw, shotBtnNeedle) !== 1 || countNeedle(blockStageToolsRaw, fullscreenBtnNeedle) !== 1) {
    console.error("MERGE_ABORT_B27: أزرار المسرح المرشحة للإسكات وُجدت بعددٍ غير المتوقع (1 لكل زر)");
    process.exit(2);
}
const blockStageTools =
    blockStageToolsRaw
        .replace(stageSelectorNeedle, "const stage = panel.querySelector('.aspect-video, .rb-mp-stage');")
        .replace(shotBtnNeedle, "")
        .replace(fullscreenBtnNeedle, "");
if (countNeedle(blockStageTools, "onclick=\"toggleRecordStream()\"") !== 1) {
    console.error("MERGE_ABORT_B27: زر «تسجيل آلي» مفقود بعد المكييف");
    process.exit(2);
}

const blockHelpers = helpersPart + "\n" + blobToBase64;

// B27 — كتلة الاستعادة المجمعة: الحالة + المشغّل + القوائم + نقّابُ إعادة الإرفاق
const blockRestored27 =
    "        // ================================================================\n" +
    "        // B27 — استعادة آليات البث الحي من الدونور (كانت أسقطةً بحاجز التوافق):\n" +
    "        // تسجيل البث الآلي (MediaRecorder) حفظُه عبر saveArtifact، وقوائم تسجيلات الكاميرا (عدساتها)\n" +
    "        // والشاشة (لقطاتها/فيديوهاتها) والمكالمات (صوتُها) من مجلدات أرشيف الهاتف المضيف.\n" +
    "        // ================================================================\n" +
    blockStageVars + "\n" +
    blockStageTools + "\n" +
    "        /** نقّابُ إعادة الإرفاق: بعد أي إعادة رسمٍ من القاعدة لتبويبَي الكاميرا/المكالمات تُعاد الأدواتُ والقوائم */\n" +
    "        function mrgEnhanceSweep() {\n" +
    "            try {\n" +
    "                if (activeTab === 'camera' && (!document.getElementById('recordings-anchor') || !document.getElementById('stream-stage-tools'))) enhanceCameraTab();\n" +
    "                if (activeTab === 'calls') { const p = document.getElementById('panel-content'); if (p && !p.querySelector('#call-records-anchor')) addCallRecordingsSection(); }\n" +
    "            } catch (_) {}\n" +
    "        }\n" +
    "        try { setInterval(mrgEnhanceSweep, 1200); } catch (_) {}\n";

// B25 — كتلة بث الترحيل (MJPEG) المنقولة من واجهة الدمج القديمة ومكيَّفة للأساس:
// العارض ينشأ ديناميكياً داخل حاوية remote-video، وأولوية فيديو WebRTC تُحسب كل نبضة دون تعديل نواة الأساس.
const blockRelay = `
        // ================================================================
        // B25 — آلية البث المباشر عبر الترحيل (MJPEG) المنقولة من واجهة الدمج القديمة:
        // الإطارات تصل كرسائل live_frame (JPEG بترميز Base64) عبر قناة wss نفسها
        // دون الحاجة إلى WebRTC/STUN، ولفيديو WebRTC النشط الأولوية في العرض دائماً.
        // ================================================================
        let relayStreamActive = false;
        let relayLastDataURL = null;
        let relayFrameCount = 0;
        let webrtcVideoActive = false;

        /** هل يوجد مسار فيديو WebRTC حي داخل العنصر الرئيسي remote-video (يُحسب لحظياً في كل نبضة). */
        function mrgHasLiveWebRtcVideo() {
            try {
                const v = document.getElementById('remote-video');
                if (!v || !v.srcObject) return false;
                const ts = v.srcObject.getVideoTracks ? v.srcObject.getVideoTracks() : [];
                return ts.some(t => t.readyState === 'live');
            } catch (_) { return false; }
        }

        /** ضمان وجود عارض الإطارات relay-frame داخل حاوية الفيديو (يُنشأ مرة واحدة). */
        function mrgEnsureRelayImg() {
            try {
                let img = document.getElementById('relay-frame');
                if (img) return img;
                const srcEl = document.getElementById('remote-video') || document.getElementById('video-placeholder');
                if (!srcEl || !srcEl.parentElement) return null;
                img = document.createElement('img');
                img.id = 'relay-frame';
                img.alt = 'البث الحي عبر خادم الإشارات (وضع الترحيل)';
                img.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:contain;z-index:20;display:none;';
                let holder = srcEl.parentElement;
                try { if (holder && getComputedStyle(holder).position === 'static') holder.style.position = 'relative'; } catch (_) {}
                holder.appendChild(img);
                return img;
            } catch (_) { return null; }
        }

        function handleLiveFrame(msg) {
            try {
                if (!msg || !msg.b64) return;
                relayFrameCount++;
                if (mrgHasLiveWebRtcVideo()) {
                    console.log('[Relay] تجاهل إطار ' + (msg.seq || '') + ': فيديو WebRTC النشط له الأولوية');
                    return;
                }
                relayLastDataURL = 'data:image/jpeg;base64,' + msg.b64;
                const firstFrame = !relayStreamActive;
                relayStreamActive = true;
                if (firstFrame) {
                    showToast('بدأ استقبال البث الحي عبر خادم الإشارات (وضع الترحيل) 📡', 'success');
                    try {
                        if (typeof renderCameraStreamView === 'function' && activeTab === 'camera') renderCameraStreamView();
                    } catch (_) {}
                }
                const img = mrgEnsureRelayImg();
                if (img) {
                    img.style.display = 'block';
                    if (img.getAttribute('src') !== relayLastDataURL) img.src = relayLastDataURL;
                }
            } catch (e) {
                console.warn('[Relay Frame Error]', e);
            }
        }

        function clearRelayViewer() {
            relayStreamActive = false;
            relayLastDataURL = null;
            relayFrameCount = 0;
            try {
                const img = document.getElementById('relay-frame');
                if (img) { img.style.display = 'none'; img.removeAttribute('src'); }
            } catch (_) {}
        }

        function clearWebRtcVideoForRelayStart() {
            // عند بدء بث الترحيل: نُصفّي فيديو WebRTC القديم المنتهي كي لا يغطي عارض الإطارات
            try {
                const videoEl = document.getElementById('remote-video');
                if (!videoEl || !videoEl.srcObject) { webrtcVideoActive = false; return; }
                const vt = videoEl.srcObject.getVideoTracks ? videoEl.srcObject.getVideoTracks() : [];
                if (!vt.length || vt.every(t => t.readyState === 'ended')) {
                    videoEl.srcObject = null;
                    videoEl.classList.add('hidden');
                    webrtcVideoActive = false;
                }
            } catch (_) {}
        }

        /** نبضة الترحيل كل ثانية: تجديد أولوية WebRTC وإعادة آخر إطار بعد أي إعادة رسم للوحة. */
        function mrgRelayTick() {
            try {
                webrtcVideoActive = mrgHasLiveWebRtcVideo();
                if (!relayStreamActive || !relayLastDataURL) return;
                const img = mrgEnsureRelayImg();
                if (!img) return;
                if (webrtcVideoActive) { img.style.display = 'none'; return; }
                img.style.display = 'block';
                if (img.getAttribute('src') !== relayLastDataURL) img.src = relayLastDataURL;
            } catch (_) {}
        }
        try { setInterval(mrgRelayTick, 1000); } catch (_) {}
`;

// B25 — قائمة «واجهات الاتصال القديمة» وخياراتها بأسماء ملفات الواجهات الموجودة في المجلد
// (تُفتح من اللوحة الرئيسية جوار زر «تحديث البيانات»، ويحمل الانتقال الحالةَ عبر remotebridge_config المشترك)
const legacySelect = `
                        <select id="legacy-iface-select" onchange="mrgLegacyIfaceSwitch(this)" title="التبديل إلى واجهات الاتصال القديمة (آليات الربط الأصلية في المجلد)" class="bg-slate-800 border border-slate-700 text-slate-200 text-xs px-2.5 py-2 rounded-xl font-bold cursor-pointer outline-none focus:border-emerald-500/60">
                            <option value="" selected disabled>🧭 واجهات الاتصال القديمة</option>
                            <option value="indexMergeFull.html">indexMergeFull.html — الواجهة النهائية (هذه)</option>
                            <option value="index.html">index.html — الإصدار الأصلي (معيار البث والاتصال)</option>
                            <option value="indexMerge.html">indexMerge.html — واجهة الدمج القديمة (ترحيل MJPEG)</option>
                        </select>`;

const blockLegacySwitch = `
        // B25 — منطق مبدّل واجهات الاتصال القديمة: فتح ملف الواجهة المختار مع تمرير الحالة عبر remotebridge_config المشترك
        function mrgLegacyIfaceSwitch(sel) {
            let target = '';
            try { target = (sel && sel.value) ? sel.value : ''; if (sel) sel.selectedIndex = 0; } catch (_) {}
            if (!target) return;
            const here = (location.pathname.split('/').pop() || 'indexMergeFull.html');
            if (target === here) { try { showToast('أنت على الواجهة النهائية أصلاً — لا داعي للتبديل', 'info'); } catch (_) {} return; }
            try {
                const hand = { from: here, to: target, at: Date.now(),
                    serverUrl: (typeof appConfig !== 'undefined' && appConfig.serverUrl) || '',
                    targetDeviceId: (typeof appConfig !== 'undefined' && appConfig.targetDeviceId) || '' };
                localStorage.setItem('rb_legacy_handoff_v1', JSON.stringify(hand));
            } catch (_) {}
            const dir = location.pathname.replace(/[^/]*$/, '');
            location.href = location.origin + dir + target + location.search;
        }`;

// ————— 2) فحوص السلامة: تقاطعُ الأسماء قبلَ الدمج —————
const allInjected = [blockSeed, blockEngine, blockHelpers, blockCommands, quickCard, blockRestored27].join("\n");

// قائمة الدوال الأسقطة من الدونور عمداً => تُستبدَل بنداء صمّاء آمن.
// B27: أُخرجت منها آليات البث الحيّ المُستعادة (المسجّل/القوائم/المسرح) لأن القاعدة لا بديلَ لها فيها.
// باقي الأسقطة لها بدائل أقوى معيارية في الأساس: نافذة الحالة والأرشفة (openHostStatusWindow + deviceArchive)،
// التنزيلات عبر قناة البيانات (handleBinaryChunk + rbDlOnDone)، والاكتشاف الآلي (autoDiscoverAndConnectHosts).
const droppedNames = [
    "refreshSavedSessions", "openSavedSession", "closeSavedSession", "showStatusModal", "closeStatusModal",
    "updateStatusModalIfOpen", "autoSaveSessionSnapshot", "saveTextViaNative",
    "ensureDownloadCard", "updateDownloadCard", "removeDownloadCard",
    "beginFileDownload", "finalizeFileDownload", "startFileDownloadByIndex", "waitForFileInfoResponse",
    "setAutoDiscoveryInterval",
    "activeNativeSaver",
];
const referencedDropped = droppedNames.filter(n => new RegExp("\\b" + n + "\\b").test(allInjected));

// تعريفات المستوى الأعلى داخل الكتل المنقولة: يجب ألا تتقاطع مع أسماء الأساس
const declRe = /(?:^|\n)[ ]*(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/g;
const declTop = new Set([...allInjected.matchAll(declRe)].map(m => m[1]));
declTop.add("quickOpts");
const allowList = new Set([
    "v" // الأساس يعرِّف الدالة v محليّاً داخل دواله فقط (ظلٌّ نطاقيّ، بلا تعارضٍ في المستوى الأعلى)
]);
const colliding = [...declTop].filter(n => !allowList.has(n) && new RegExp("\\b" + n + "\\b").test(baseText));
if (colliding.length) {
    console.error("MERGE_ABORT: تعريفات متعارضة مع الأساس: " + colliding.join(", "));
    process.exit(2);
}

// ————— 3) تركيب الكتلة المجمّعة ومواضع الحشو —————
// بنداءُ الدوالِّ الأسقطةِ الحارسةِ (بلا أثرٍ حين تُستدعى)
const stubBlock =
    "        // B24 — حاجز التوافق: دوال أُسقطت عمداً من الدونور (الأساس أقوى في بدائلها) تُستبدَل بنداء صمّاء آمن (بلا ReferenceError)\n" +
    "        const __rbDroppedNames = [" + droppedNames.map(n => "'" + n + "'").join(", ") + "];\n" +
    "        __rbDroppedNames.forEach(n => { try { if (typeof window[n] === 'undefined') window[n] = function () {}; } catch (_) {} });\n";

// saveArtifact مهيَّأة على الأساس (عبر saveBlobAs لتستخدم جسر تطبيق المضيف window.RBAndroid ثم المتصفح)
const saveArtifactAdapted =
    "        /** حفظ ملف ناتج من المتحكم (لقطة/تسجيل) — B24: يمرَّ عبر saveBlobAs في الأساس ليستخدم جسر تطبيق المضيف window.RBAndroid ثم تنزيلات المتصفح. */\n" +
    "        function saveArtifact(name, mimeType, base64) {\n" +
    "            try {\n" +
    "                const bin = atob(base64);\n" +
    "                const arr = new Uint8Array(bin.length);\n" +
    "                for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);\n" +
    "                const blob = new Blob([arr], { type: mimeType || 'application/octet-stream' });\n" +
    "                const p = saveBlobAs(blob, name || 'rb_artifact', mimeType);\n" +
    "                if (p && p.then) { p.catch(e => showToast('تعذر حفظ الملف: ' + ((e && e.message) || String(e)), 'error')); }\n" +
    "            } catch (e) {\n" +
    "                showToast('تعذر حفظ الملف: ' + ((e && e.message) || String(e)), 'error');\n" +
    "            }\n" +
    "        }\n";

// الكتلة المجمّعة (تُحقن قبل قسم التبديل في الأساس)
const megablock =
    "        // ================================================================\n" +
    "        // B24 — محتوى منقول باتجاهٍ واحد (الدونور فقط): تبويب الأوامر ومحرك المزامنة السريعة والأدوات المساندة\n" +
    "        // المعيار: إعدادات البث الحي والاتصال لقاعدة الأساس تُتَّبَع كما هي ولا تُستبدَل بالكتل المنقولة\n" +
    "        // ================================================================\n" +
    stubBlock + "\n" +
    blockHelpers + "\n\n" +
    saveArtifactAdapted + "\n\n" +
    blockCommands + "\n\n" +
    blockEngine + "\n\n" +
    blockRestored27 + "\n\n" +
    "        // ================================================================\n" +
    "        // B24 — تبويب محرك المزامنة السريعة (🛰️) وتبويب مجموعات الإعدادات علوياً كتبويبَات مستقلة\n" +
    "        // ================================================================\n" +
    "        const QUICK_OPTS_CARD_HTML = `" + quickCard + "`;\n" +
    "        function renderQuickPane(panel) {\n" +
    "            const host = panel || document.getElementById('panel-content');\n" +
    "            if (!host) return;\n" +
    "            host.innerHTML = `\n" +
    "                <div class=\"flex flex-col sm:flex-row justify-between items-start sm:items-center pb-3 mb-3 border-b border-slate-800 gap-2\">\n" +
    "                    <div class=\"flex items-start gap-2.5\">\n" +
    "                        <span class=\"w-9 h-9 rounded-xl bg-sky-500/15 border border-sky-500/30 flex items-center justify-center text-sky-300 shrink-0\">🛰️</span>\n" +
    "                        <div>\n" +
    "                            <h2 class=\"text-lg md:text-xl font-bold text-slate-100\">محرك مزامنة الإعدادات السريعة</h2>\n" +
    "                            <p class=\"text-xs text-slate-400 mt-0.5\">استمرارية تشغيل المضيف عبر قناة <span class=\"font-mono text-sky-300\">settings_sync</span> بالدفع والسحب (الأحدث يفوز)</p>\n" +
    "                        </div>\n" +
    "                    </div>\n" +
    "                </div>\n" +
    "                ${QUICK_OPTS_CARD_HTML}\n" +
    "                <div class=\"mt-3 bg-slate-900/70 border border-slate-800 rounded-2xl p-3 text-[11px] text-slate-400 leading-relaxed\">\n" +
    "                    <p class=\"font-bold text-slate-300 mb-1\">كيف تعمل المزامنة؟</p>\n" +
    "                    <p>«دفع إعداداتي للمضيف» يرسل لقطتنا والتحديث الأحدث يفوز (طابع زمني جديد)، و«سحب إعدادات المضيف» يطلب لقطة الطرف الآخر (طابع = 0). التوجيه يتم عبر خادم الإشارات مباشرة، وهذه البطاقة هي المصدر الوحيد لعناصر <span class=\"font-mono\">qopt-*</span> في الواجهة كلها.</p>\n" +
    "                    <p class=\"mt-1\">وإعدادات البث الحي والاتصال تظل معيارَ واجهة الأساس دون أدنى مساس.</p>\n" +
    "                </div>`;\n" +
    "            try { updateQuickOptsUI(); } catch (_) {}\n" +
    "        }\n" +
    "\n" +
    "        // B24 — غلاف الفصل: يفتح تبويب الإعدادات ثم يوجّهه إلى المجموعة المستهدفة\n" +
    "        function openSettingsGroupTab(g) {\n" +
    "            try { switchTab('settings'); } catch (_) {}\n" +
    "            try { switchSettingsGroup(g); } catch (_) {}\n" +
    "            try {\n" +
    "                document.querySelectorAll('.rb-sg-tab').forEach(b => b.classList.toggle('rb-sg-tab-on', b.getAttribute('data-sg-key') === g));\n" +
    "            } catch (_) {}\n" +
    "        }\n\n";

let out = baseText.replace(/\r\n/g, "\n");

// (1) CSS: أصناف الأوامر المنقولة + إبراز تبويبات المجموعات (قبل آخر إغلاقِ كتلةِ نمطٍ في الأساس)
out = insertBeforeLast(out, "    </style>",
    "        /* B24 — أصناف أزرار تبويب الأوامر المنقولة + إبراز تبويبات مجموعات الإعدادات */\n" +
    blockStreamCss + "\n" +
    "        .rb-sg-tab-on {\n" +
    "            background: rgba(16,185,129,.22) !important;\n" +
    "            color: #86efac !important;\n" +
    "            border: 1px solid rgba(16,185,129,.45) !important;\n" +
    "            font-weight: 700;\n" +
    "        }\n");

// (2) بذر إعدادات المضيف (IIFE) قبيل مهلة تحديث البطارية
out = insertBefore(out, "        // Auto Battery Refresh Interval", blockSeed + "\n\n");

// (3) لحظة توثيق التسجيل (registered): مزامنة أولية بعد التسجيل
out = insertAfter(out,
    '                    showToast("تم تسجيل جهاز الويب كـ Controller بنجاح", "success");\n',
    "                    // B24 — مزامنة أولية للإعدادات السريعة بعد التسجيل: دفع لقطتنا إن وُجدت وإلا سحب لقطة المضيف\n" +
    "                    setTimeout(() => { try { maybeInitialSettingsSync(); } catch (_) {} }, 600);\n");

// (4) الحالة الجديدة settings_sync في مُوزع رسائل الخادم
out = insertBefore(out,
    '                case "push_event":\n',
    '                case "settings_sync":\n' +
    "                    // B24 — مزامنة الإعدادات السريعة ثنائية الاتجاه بين الطرفين (الأحدث يفوز)\n" +
    "                    try { handleIncomingSettingsSync(msg); } catch (e) { console.warn('settings_sync error:', e); }\n" +
    "                    break;\n");

// (4b) B25 — حالة live_frame في الموزع نفسه (عارض بث الترحيل MJPEG)
out = insertBefore(out,
    '                case "push_event":\n',
    '                case "live_frame":\n' +
    "                    // B25 — عارض بث الترحيل القادم من واجهة الدمج القديمة (فيديو WebRTC له الأولوية)\n" +
    "                    try { handleLiveFrame(msg); } catch (e) { console.warn('live_frame error:', e); }\n" +
    "                    break;\n");

// (5) كتلة ترحيل MJPEG (B25) ثم منطق المبدّل ثم الكتلة المجمّعة قبيل قسم التبديل
out = insertBefore(out, "        // Tab Switching\n", blockRelay + "\n\n");
out = insertBefore(out, "        // Tab Switching\n", blockLegacySwitch + "\n\n");
out = insertBefore(out, "        // Tab Switching\n", megablock);

// (6) حالات switchTab الجديدة (الأوامر + المزامنة)
out = insertAfter(out,
    "            } else if (tab === 'settings') {\n" +
    "                panel.innerHTML = renderSettingsView();\n" +
    "                switchSettingsGroup(currentSettingsGroup(), true);\n" +
    "            }",
    " else if (tab === 'commands') {\n" +
    "                renderCommandsView();\n" +
    "            } else if (tab === 'sync') {\n" +
    "                renderQuickPane(panel);\n" +
    "            }");

// (7) شريط الهاتف: أزرار الأوامر والمزامنة ومجموعات الإعدادات
out = insertAfter(out,
    "        <button onclick=\"switchTab('settings')\" class=\"tab-btn-mobile px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">⚙️ الإعدادات <span class=\"tab-state-dot\" data-tab-dot=\"settings\" title=\"حالة الميزة الحيّة\"></span></button>\n",
    "        <button onclick=\"switchTab('commands')\" class=\"tab-btn-mobile px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">⚡ الأوامر</button>\n" +
    "        <button onclick=\"switchTab('sync')\" class=\"tab-btn-mobile px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">🛰️ المزامنة</button>\n" +
    "        <button onclick=\"openSettingsGroupTab('connection')\" data-sg-key=\"connection\" class=\"tab-btn-mobile rb-sg-tab px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">🔌 اتصال</button>\n" +
    "        <button onclick=\"openSettingsGroupTab('ui')\" data-sg-key=\"ui\" class=\"tab-btn-mobile rb-sg-tab px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">🎨 واجهة</button>\n" +
    "        <button onclick=\"openSettingsGroupTab('quick')\" data-sg-key=\"quick\" class=\"tab-btn-mobile rb-sg-tab px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap text-slate-300 bg-slate-800/60\">✨ سريعة</button>\n");

// (8) الشريط الجانبي للحاسوب: الأوامر والمزامنة والمجموعات كتبويبات مستقلة
out = insertAfter(out,
    "                <div id=\"settings-submenu\" hidden>\n" +
    "                    <button onclick=\"switchSettingsGroup('connection')\" class=\"settings-subitem\" data-sg=\"connection\"><span>🔌</span><span>إعدادات الاتصال والربط</span></button>\n" +
    "                    <button onclick=\"switchSettingsGroup('ui')\" class=\"settings-subitem\" data-sg=\"ui\"><span>🎨</span><span>إعدادات الواجهة</span></button>\n" +
    "                    <button onclick=\"switchSettingsGroup('quick')\" class=\"settings-subitem\" data-sg=\"quick\"><span>⚡</span><span>إعدادات الخيارات السريعة</span></button>\n" +
    "                </div>\n",
    "                <button onclick=\"switchTab('commands')\" class=\"tab-btn w-full text-right px-3.5 py-2.5 rounded-xl hover:bg-slate-800/80 text-slate-300 font-medium transition flex items-center justify-between group\">\n" +
    "                    <span class=\"flex items-center gap-2.5 text-xs\"><span class=\"tab-emoji\">⚡</span> الأوامر والتحكم البعيد</span>\n" +
    "                </button>\n" +
    "                <button onclick=\"switchTab('sync')\" class=\"tab-btn w-full text-right px-3.5 py-2.5 rounded-xl hover:bg-slate-800/80 text-slate-300 font-medium transition flex items-center justify-between group\">\n" +
    "                    <span class=\"flex items-center gap-2.5 text-xs\"><span class=\"tab-emoji\">🛰️</span> محرك مزامنة الإعدادات السريعة</span>\n" +
    "                </button>\n" +
    "                <div class=\"px-3 py-1 text-[11px] font-bold text-slate-500 uppercase tracking-wider\">مجموعات الإعدادات كتبويبات</div>\n" +
    "                <button onclick=\"openSettingsGroupTab('connection')\" data-sg-key=\"connection\" class=\"tab-btn rb-sg-tab w-full text-right px-3.5 py-2 rounded-xl hover:bg-slate-800/80 text-slate-300 font-medium transition flex items-center justify-between group\">\n" +
    "                    <span class=\"flex items-center gap-2.5 text-xs\"><span class=\"tab-emoji\">🔌</span> إعدادات الاتصال والربط</span>\n" +
    "                </button>\n" +
    "                <button onclick=\"openSettingsGroupTab('ui')\" data-sg-key=\"ui\" class=\"tab-btn rb-sg-tab w-full text-right px-3.5 py-2 rounded-xl hover:bg-slate-800/80 text-slate-300 font-medium transition flex items-center justify-between group\">\n" +
    "                    <span class=\"flex items-center gap-2.5 text-xs\"><span class=\"tab-emoji\">🎨</span> إعدادات الواجهة</span>\n" +
    "                </button>\n" +
    "                <button onclick=\"openSettingsGroupTab('quick')\" data-sg-key=\"quick\" class=\"tab-btn rb-sg-tab w-full text-right px-3.5 py-2 rounded-xl hover:bg-slate-800/80 text-slate-300 font-medium transition flex items-center justify-between group\">\n" +
    "                    <span class=\"flex items-center gap-2.5 text-xs\"><span class=\"tab-emoji\">✨</span> الخيارات السريعة (مزامنة مع المضيف)</span>\n" +
    "                </button>\n");

// ————— 4) فحوص الخروج (شواهد الشمول السليم) —————
// (9) B25 — قائمة «واجهات الاتصال القديمة» بجانب زر تحديث البيانات في رأس اللوحة الرئيسية
out = insertAfter(out, '<span>تحديث البيانات</span> 🔄\n                        </button>', legacySelect + "\n");

// (10) B27 — تعليق أدوات البث المستعادة على تبويبَي الكاميرا والمكالمات (فوراً عند الرسم، والنقّابُ يعيد بعد كل إعادة رسم)
out = insertAfter(out,
    "            } else if (tab === 'camera') {\n                renderCameraStreamView();\n",
    "                setTimeout(() => { try { enhanceCameraTab(); } catch (_) {} }, 60);\n");
out = insertAfter(out,
    "            } else if (tab === 'calls') {\n                renderCallsListView();\n",
    "                setTimeout(() => { try { addCallRecordingsSection(); } catch (_) {} }, 60);\n");

const verify = [
    ["case \"settings_sync\":", 1],
    ["function renderQuickPane(", 1],
    ["function openSettingsGroupTab(", 1],
    ["function renderCommandsView(", 1],
    ["function maybeInitialSettingsSync(", 1],
    ["maybeInitialSettingsSync();", 1],
    ["function handleIncomingSettingsSync(", 1],
    ["function pushQuickOptsSync(", 1],
    ["function pullHostSettingsSync(", 1],
    ["function currentSyncTargetId(", 1],
    ["function saveArtifact(", 1],
    ["function rpcWait(", 1],
    ["function fmtKb(", 1],
    ["function fmtWhen(", 1],
    ["function blobToBase64(", 1],
    ["function cmdCard(", 1],
    ["function commandsBadge(", 1],
    ["function genericBtn(", 1],
    ["function renderQuickPane(panel) {", 1],
    ["const SVG_ICON = {", 1],
    ["const QUICK_OPTS_CARD_HTML = `", 1],
    ["QUICK_OPTS_CARD_HTML}", 1],
    ["const __rbDroppedNames", 1],
    ["remotebridge_quick_opts'", 2],
    ["seedFromHostApp", 1],
    ["rb-sg-tab-on", 2],
    ["rb-sg-tab ", 6],
    ["renderQuickPane(panel);", 1],
    ["renderCommandsView();", 1],
    ["openSettingsGroupTab('connection')", 2],
    ["openSettingsGroupTab('ui')", 2],
    ["openSettingsGroupTab('quick')", 2],
    // (B25) شواهد آليات الاتصال المنقولة: الترحيل + المبدّل
    ["case \"live_frame\":", 1],
    ["function handleLiveFrame(", 1],
    ["function clearRelayViewer(", 1],
    ["function clearWebRtcVideoForRelayStart(", 1],
    ["function mrgHasLiveWebRtcVideo(", 1],
    ["function mrgEnsureRelayImg(", 1],
    ["function mrgRelayTick(", 1],
    ["function mrgLegacyIfaceSwitch(", 1],
    ["mrgHasLiveWebRtcVideo()", 3],
    ["id=\"legacy-iface-select\"", 1],
    ["rb_legacy_handoff_v1", 1],
    ["واجهات الاتصال القديمة", 3],
    // (B27) شواهد استعادة آليات البث الحي: المشغّل + المسجّل + قوائم التسجيلات + النقّاب
    ["let recCamFront = true;", 1],
    ["let remoteRecorder = null;", 1],
    ["function openStageFullscreen(", 1],
    ["function captureStreamSnapshot(", 1],
    ["function toggleRecordStream(", 1],
    ["function enhanceCameraTab(", 1],
    ["function enhanceCameraTabForced(", 1],
    ["function cycleCameraRecords(", 1],
    ["function cycleScreenRecords(", 1],
    ["function recordItemHtml(", 1],
    ["function refreshCameraRecordsBox(", 1],
    ["function refreshScreenRecordsBox(", 1],
    ["function addCallRecordingsSection(", 1],
    ["function loadCallRecordings(", 1],
    ["function mrgEnhanceSweep(", 1],
    ["setInterval(mrgEnhanceSweep, 1200);", 1],
    ["panel.querySelector('.aspect-video, .rb-mp-stage');", 1],
    ["onclick=\"toggleRecordStream()\"", 1],
    ["id=\"stream-stage-tools\"", 1],
    ["id=\"recordings-anchor\"", 1],
    ["id=\"records-cam-box\"", 1],
    ["id=\"records-scr-box\"", 1],
    ["id=\"call-records-anchor\"", 1],
    ["setTimeout(() => { try { enhanceCameraTab(); } catch (_) {} }, 60);", 1],
    ["setTimeout(() => { try { addCallRecordingsSection(); } catch (_) {} }, 60);", 1],
];
const failures = verify.filter(([n, e]) => countNeedle(out, n) !== e)
    .map(([n, e]) => "«" + n + "» = " + countNeedle(out, n) + " والمتوقع " + e);
if (failures.length) {
    console.error("MERGE_ABORT: شواهد الخروج غير مطابقة:\n" + failures.join("\n"));
    process.exit(2);
}

writeFileSync(OUT_PATH, out, "utf8");

const lines = out.split(/\r?\n/).length;
const bytes = Buffer.byteLength(out, "utf8");
console.log("B24_MERGE=PASS");
console.log("out: " + OUT_PATH + " — " + lines + " سطر, " + bytes + " بايت");
console.log("الشواهد: " + verify.length + " شرطاً وكلها مطابقة");
console.log("قائمة الأسقطة المُحالة إلى حاجز صمّاء: " + referencedDropped.length + " من " + droppedNames.length + (referencedDropped.length ? (": " + referencedDropped.join(", ")) : ""));
console.log("تعريفات الدونور المحقونة: " + declTop.size + " تعريفاً (بلا تعارضٍ مع الأساس)");

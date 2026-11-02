// merge_build.mjs — B24: بناء الواجهة النهائية المدمجة web-dashboard/indexMergeFull.html
// الأصل: الأساس web-dashboard/index.html (إعدادات البث الحي والاتصال معيار لا يُمسَّ)
// الإضافة: كتل منقولة حصراً من web-dashboard/indexMerge.html بنطاقات مثبتة بالقراءة الحرفية.
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

const blockHelpers = helpersPart + "\n" + blobToBase64;

// ————— 2) فحوص السلامة: تقاطعُ الأسماء قبلَ الدمج —————
const allInjected = [blockSeed, blockEngine, blockHelpers, blockCommands, quickCard].join("\n");

// قائمة الدوال الأسقطة من الدونور عمداً (الأساس أقوى في بديلها) => تُستبدَل بنداء صمّاء آمن
const droppedNames = [
    "refreshCameraRecordsBox", "refreshScreenRecordsBox", "cycleCameraRecords", "cycleScreenRecords",
    "recordItemHtml", "enhanceCameraTab", "enhanceCameraTabForced", "addCallRecordingsSection", "loadCallRecordings",
    "refreshSavedSessions", "openSavedSession", "closeSavedSession", "showStatusModal", "closeStatusModal",
    "updateStatusModalIfOpen", "autoSaveSessionSnapshot", "saveTextViaNative",
    "ensureDownloadCard", "updateDownloadCard", "removeDownloadCard",
    "beginFileDownload", "finalizeFileDownload", "startFileDownloadByIndex", "waitForFileInfoResponse",
    "handleLiveFrame", "clearRelayViewer", "clearWebRtcVideoForRelayStart", "setAutoDiscoveryInterval",
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

// (5) الكتلة المجمّعة قبيل قسم التبديل
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

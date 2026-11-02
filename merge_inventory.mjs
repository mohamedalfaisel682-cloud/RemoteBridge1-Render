/**
 * merge_inventory.mjs — مخزون مقارن لملفَّي واجهة المتحكم قبل الدمج في indexMergeFull.html
 * الاستخدام: node merge_inventory.mjs <fileA.html> <fileB.html>
 * المخرجات لكل ملف: حجم، أسطر، التبويبات، مجموعات الإعدادات، عناصر لوحات، دوال عالمية،
 * مفاتيح localStorage، أوامر RPC، كتل CSS/JS — لاتخاذ قرار الانتقاء في الدمج.
 */
import fs from "node:fs";

const files = process.argv.slice(2);
if (files.length === 0) { console.error("USAGE: node merge_inventory.mjs fileA.html [fileB.html]"); process.exit(1); }

function inventory(path) {
  const s = fs.readFileSync(path, "utf8");
  const lines = s.split(/\r?\n/).length;
  const grab = (re) => [...s.matchAll(re)];
  const uniq = (arr) => [...new Set(arr)];

  const tabs = uniq(grab(/onclick\s*=\s*"switchTab\('([^']+)'\)"/g).map(m => m[1]));
  const groups = uniq(grab(/switchSettingsGroup\('([^']+)'\)/g).map(m => m[1]));
  const sgDots = uniq(grab(/data-sg="([^"]+)"/g).map(m => m[1]));
  const panelIds = uniq(grab(/id="([A-Za-z0-9_\-]{4,40})"/g).map(m => m[1]));
  const fns = uniq(grab(/function\s+([A-Za-z_$][A-Za-z0-9_$]{2,60})\s*\(/g).map(m => m[1]));
  const lsKeys = uniq(grab(/localStorage\.(getItem|setItem)\('([^']+)'/g).map(m => m[2]));
  const rpc = uniq(grab(/sendRPCCommand\('([^']+)'/g).map(m => m[1]));
  const cssSels = uniq(grab(/\.([\w\-]{3,40})\s*\{/g).map(m => m[1]));
  const wsEvents = uniq(grab(/event\s*===\s*'([^']+)'/g).map(m => m[1]));

  return {
    path, bytes: Buffer.byteLength(s, "utf8"), lines,
    tabs: tabs.join(", "), groups: groups.join(", "), sgDots: sgDots.join(", "),
    panels: panelIds.length, panelSample: panelIds.slice(0, 40).join(", "),
    fns: fns.length, fnSample: fns.slice(0, 30).join(", "),
    lsKeys: lsKeys.length, lsSample: lsKeys.slice(0, 20).join(", "),
    rpc: rpc.length, rpcSample: rpc.slice(0, 25).join(", "),
    css: cssSels.length, wsEvents: wsEvents.join(", ")
  };
}

const inv = files.map(inventory);
console.log(JSON.stringify(inv, null, 2));

// مصفوفة الدمج: دوال في A لا في B والعكس — للملفَّيْنِ معاً فقط
if (inv.length === 2) {
  const aOnly = inv[0].fns - inv[1].fns; // مطمئن فقط؛ التفصيل أدناه
  const da = new Set([...fs.readFileSync(files[0], "utf8").matchAll(/function\s+([A-Za-z_$][A-Za-z0-9_$]{2,60})\s*\(/g)].map(m => m[1]));
  const db = new Set([...fs.readFileSync(files[1], "utf8").matchAll(/function\s+([A-Za-z_$][A-Za-z0-9_$]{2,60})\s*\(/g)].map(m => m[1]));
  const onlyInA = [...da].filter(x => !db.has(x));
  const onlyInB = [...db].filter(x => !da.has(x));
  console.log("\n=== فقط في A (" + files[0] + ") ===\n" + onlyInA.join("\n"));
  console.log("\n=== فقط في B (" + files[1] + ") ===\n" + onlyInB.join("\n"));
}

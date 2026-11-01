// hash_all.mjs - compute sha256 for every file in both archive trees + check B02==B03 html
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

const ROOT = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA';
const trees = ['apks versions', 'apks versions reverted'];
const out = [];

for (const tree of trees) {
  const base = path.join(ROOT, tree);
  for (const dir of fs.readdirSync(base).filter(d => fs.statSync(path.join(base, d)).isDirectory()).sort()) {
    const files = fs.readdirSync(path.join(base, dir)).sort();
    for (const f of files) {
      const p = path.join(base, dir, f);
      if (!fs.statSync(p).isFile()) continue;
      if (f === 'git-ref.txt' || f === 'revert_report.txt') continue;
      const buf = fs.readFileSync(p);
      const h = crypto.createHash('sha256').update(buf).digest('hex');
      out.push(`${tree}|${dir}|${f}|${buf.length}|${h}`);
    }
  }
}
fs.writeFileSync(path.join(ROOT, 'docs', 'tools', 'hashes.txt'), out.join('\n'), 'utf8');

// equality B02 vs B03 controllers (tree A)
const a = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA/apks versions/B02-331be0d/RBstudioPRO_B02-331be0d_20261024_v1.0.0.html';
const b = 'F:/Ai/agent folders/Editing By opencode/opencodeGLM5.3FLASHA/apks versions/B03-561db36/RBstudioPRO_B03-561db36_20261024_v1.0.0.html';
const eq = Buffer.compare(fs.readFileSync(a), fs.readFileSync(b)) === 0;
const b02 = fs.readFileSync(a).length, b03r = fs.readFileSync(path.join(ROOT, 'apks versions reverted/B03-561db36/RBstudioPRO_B03-561db36_20261024_v1.0.0.html')).length;
console.log('B02html===B03html: ' + eq);
console.log('TOTAL_FILES=' + out.length);
console.log('LINES_FIRST=', out[0]);

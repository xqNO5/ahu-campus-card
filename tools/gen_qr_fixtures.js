// Emits Kotlin test fixtures containing reference QR matrices, produced by the verified
// Python encoder (tools/qr_port_check.py) so the Android build re-checks the Kotlin port.
//
// Run `python tools/qr_emit_fixtures.py > tools/qr_fixtures.json` first: the sandbox here
// forbids a Node process from capturing a child process's output.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, 'tools', 'qr_fixtures.json');
if (!fs.existsSync(src)) {
  console.error('missing ' + src + ' — run: python tools/qr_emit_fixtures.py > tools/qr_fixtures.json');
  process.exit(1);
}
const fixtures = JSON.parse(fs.readFileSync(src, 'utf8'));

const lines = [];
lines.push('package cn.edu.ahu.cardqr.qr');
lines.push('');
lines.push('/**');
lines.push(' * Reference QR matrices for a 20-digit pay code, produced by the verified Python');
lines.push(' * encoder (tools/qr_port_check.py, itself checked module-for-module against the');
lines.push(' * `qrcode` library and decoded with OpenCV). Regenerate with');
lines.push(' * `node tools/gen_qr_fixtures.js`.');
lines.push(' */');
lines.push('object QrFixtures {');
lines.push('    data class Fixture(val ecc: QrEncoder.Ecc, val version: Int, val rows: List<String>)');
lines.push('');
lines.push('    val PAYLOAD = ' + JSON.stringify(fixtures.payload));
lines.push('');
lines.push('    val ALL = listOf(');
for (const f of fixtures.cases) {
  lines.push(`        Fixture(QrEncoder.Ecc.${f.ecc}, ${f.version}, listOf(`);
  for (const row of f.rows) lines.push(`            ${JSON.stringify(row)},`);
  lines.push('        )),');
}
lines.push('    )');
lines.push('}');

const dest = path.join(root, 'android', 'app', 'src', 'test', 'java', 'cn', 'edu', 'ahu', 'cardqr', 'qr', 'QrFixtures.kt');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, lines.join('\n') + '\n', 'utf8');
console.log('wrote', dest, `(${fixtures.cases.length} fixtures)`);

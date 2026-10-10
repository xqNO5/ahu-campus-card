// Generates the Kotlin unit-test fixtures from the vectors captured off the live page,
// so the Kotlin and Python DES ports are pinned to the same reference data.
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const vectors = JSON.parse(fs.readFileSync(path.join(root, 'tools', 'des_vectors.json'), 'utf8'));

const lines = [];
lines.push('package cn.edu.ahu.cardqr.crypto');
lines.push('');
lines.push('/**');
lines.push(' * Test vectors produced by running the school\'s own des.js through Node');
lines.push(' * (see tools/gen_des_vectors.js). Shared verbatim with the Python port.');
lines.push(' */');
lines.push('object DesVectors {');
lines.push('    data class Vector(val input: String, val expected: String)');
lines.push('');
lines.push('    val ALL = listOf(');
for (const v of vectors) {
  const input = JSON.stringify(v.input);
  lines.push(`        Vector(${input}, "${v.expected}"),`);
}
lines.push('    )');
lines.push('}');

const out = path.join(root, 'android', 'app', 'src', 'test', 'java', 'cn', 'edu', 'ahu', 'cardqr', 'crypto', 'DesVectors.kt');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, lines.join('\n') + '\n', 'utf8');
console.log('wrote', out, `(${vectors.length} vectors)`);

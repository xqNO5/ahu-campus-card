// Generates 3DES test vectors by running the school's own des.js implementation.
//
// Usage: node tools/gen_des_vectors.js > tools/des_vectors.json
// Requires des_core.js (functions extracted from the served des.js -- see README).
//
// The inputs are deliberately synthetic: they exercise realistic lengths (a long
// username+password+CAS-token blob) without embedding any real account data.
const fs = require('fs');
const path = require('path');

const core = fs.readFileSync(path.join(__dirname, '..', 'des_core.js'), 'utf8');
eval(core);

const inputs = [
  'abc123',
  '12345678',
  'a',
  'abcd',
  // ~64 chars, the shape of "username + password + lt" on a real login
  'STUDENT0001P@ssw0rdLT-1082442-nwZfwnrkHP1yM0V9hSDijtI7LO3zYI-cas',
  'STUDENT0001P@ssw0rdLT-1234567890-abcdefghijklmnopqrstuvwxyz',
  'user',
  'P@ssw0rd!',
  'z',
  '0123456789abcdef',
];

const vectors = inputs.map((s) => ({ input: s, expected: strEnc(s, '1', '2', '3') }));
process.stdout.write(JSON.stringify(vectors, null, 2));

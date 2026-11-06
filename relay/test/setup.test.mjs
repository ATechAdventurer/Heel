import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {createHash} from 'node:crypto';
import {setup, validateDomain} from '../setup.mjs';
import {enrollmentConfig} from '../server.mjs';

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'heel-setup-'));
  t.after(() => rmSync(dir, {recursive: true, force: true}));
  return dir;
}
test('setup creates an accepted reusable enrollment hash, not plaintext in relay data', t => {
  const dir = fixture(t);
  assert.equal(setup(dir, 'HEEL.Example.com'), 'heel.example.com');
  const code = readFileSync(join(dir, '.setup-code'), 'utf8').trim();
  const json = readFileSync(join(dir, '.runtime/enrollment.json'), 'utf8');
  assert.match(code, /^[\w-]{43}$/);
  assert.ok(!json.includes(code));
  const hash = createHash('sha256').update(code).digest('hex');
  assert.deepEqual(enrollmentConfig(JSON.parse(json)), {enrollmentHashes: [], reusableEnrollmentHashes: [hash]});
  assert.equal(readFileSync(join(dir, '.env'), 'utf8'), 'HEEL_DOMAIN=heel.example.com\n');
});
test('rerunning preserves code, enrollment, and existing pair state', t => {
  const dir = fixture(t); setup(dir, 'heel.example.com');
  const files = ['.setup-code', '.runtime/enrollment.json', '.env'];
  const before = files.map(f => readFileSync(join(dir, f), 'utf8'));
  writeFileSync(join(dir, '.runtime/state.json'), 'existing-private-state');
  setup(dir, 'heel.example.com');
  assert.deepEqual(files.map(f => readFileSync(join(dir, f), 'utf8')), before);
  assert.equal(readFileSync(join(dir, '.runtime/state.json'), 'utf8'), 'existing-private-state');
  assert.throws(() => setup(dir, 'different.example.com'), /different .env/);
});
test('invalid hostnames fail before creating credentials', t => {
  const dir = fixture(t);
  for (const input of ['', 'https://heel.example.com', 'example.com:443', '127.0.0.1', 'localhost', '../escape',
    'example.com\nBAD=1', 'example.com/path', '-bad.example', 'a'.repeat(64)+'.com', 'example.com;whoami']) {
    assert.throws(() => setup(dir, input));
  }
  assert.equal(existsSync(join(dir, '.runtime')), false);
  assert.equal(validateDomain('xn--bcher-kva.example'), 'xn--bcher-kva.example');
});
test('missing or mismatched setup secrets never silently rotate enrollment', t => {
  const dir = fixture(t); setup(dir, 'heel.example.com');
  const enrollment = readFileSync(join(dir, '.runtime/enrollment.json'), 'utf8');
  writeFileSync(join(dir, '.setup-code'), 'a'.repeat(43));
  assert.throws(() => setup(dir, 'heel.example.com'), /does not match/);
  rmSync(join(dir, '.setup-code'));
  assert.throws(() => setup(dir, 'heel.example.com'), /missing/);
  assert.equal(readFileSync(join(dir, '.runtime/enrollment.json'), 'utf8'), enrollment);
});

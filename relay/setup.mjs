import {randomBytes, createHash} from 'node:crypto';
import {existsSync, mkdirSync, readFileSync, writeFileSync, chmodSync} from 'node:fs';
import {resolve, join} from 'node:path';
import {fileURLToPath} from 'node:url';

export function validateDomain(value) {
  const domain = String(value || '').toLowerCase();
  const labels = domain.split('.');
  if (domain.length > 253 || labels.length < 2 ||
      !labels.every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)) ||
      !/^[a-z]{2,63}$/.test(labels.at(-1))) {
    throw new Error('Use a public hostname such as relay.example.com, without https://, a port, or a path.');
  }
  return domain;
}

// Safe to rerun: never replace existing phone identities, pairs or setup codes.
export function setup(directory, value) {
  const domain = validateDomain(value);
  const runtime = join(directory, '.runtime');
  const enrollmentFile = join(runtime, 'enrollment.json');
  const codeFile = join(directory, '.setup-code');
  const envFile = join(directory, '.env');
  const env = `HEEL_DOMAIN=${domain}\n`;
  if (existsSync(envFile) && readFileSync(envFile, 'utf8') !== env) {
    throw new Error('This install already has a different .env. Keep the existing domain or update .env deliberately.');
  }
  if (existsSync(enrollmentFile) && !existsSync(codeFile)) {
    throw new Error('Enrollment already exists but .setup-code is missing. Restore it from your private backup; no identities were changed.');
  }
  const code = existsSync(codeFile) ? readFileSync(codeFile, 'utf8').trim() : randomBytes(32).toString('base64url');
  if (!/^[A-Za-z0-9_-]{43}$/.test(code)) throw new Error('Invalid saved setup code; refusing to overwrite it.');
  const digest = createHash('sha256').update(code).digest('hex');
  if (existsSync(enrollmentFile)) {
    const config = JSON.parse(readFileSync(enrollmentFile, 'utf8'));
    if (!Array.isArray(config.reusableHashes) || !config.reusableHashes.includes(digest)) {
      throw new Error('Saved setup code does not match enrollment.json. No configuration changed.');
    }
  }
  mkdirSync(runtime, {recursive: true, mode: 0o700});
  if (!existsSync(codeFile)) writeFileSync(codeFile, code + '\n', {flag: 'wx', mode: 0o600});
  if (!existsSync(enrollmentFile)) writeFileSync(enrollmentFile,
    JSON.stringify({oneTimeHashes: [], reusableHashes: [digest]}, null, 2) + '\n', {flag: 'wx', mode: 0o600});
  if (!existsSync(envFile)) writeFileSync(envFile, env, {flag: 'wx', mode: 0o600});
  for (const file of [codeFile, enrollmentFile, envFile]) chmodSync(file, 0o600);
  return domain;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const domain = setup(process.cwd(), process.argv[2]);
    console.log(`Configuration ready for https://${domain}. Private setup code saved in .setup-code.`);
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}

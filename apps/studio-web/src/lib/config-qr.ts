// QR de configuration et hachage du mot de passe de verrouillage.
// Format décrit dans spec/config-qr.md (partagé avec Kura Scan).
import { argon2id } from 'hash-wasm';

/** Au-delà, le QR devient trop dense pour être lu de façon fiable par un téléphone. */
export const MAX_QR_BYTES = 1200;

const PBKDF2_ITERATIONS = 120_000;

export function base64url(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function fromBase64url(text: string): Uint8Array<ArrayBuffer> {
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/');
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0));
}

const utf8 = (s: string) => new TextEncoder().encode(s);

export async function encryptPayload(
  plaintext: Uint8Array<ArrayBuffer>,
  pin: string,
  salt = crypto.getRandomValues(new Uint8Array(16)),
  iv = crypto.getRandomValues(new Uint8Array(12)),
): Promise<Uint8Array> {
  const material = await crypto.subtle.importKey('raw', utf8(pin), 'PBKDF2', false, ['deriveKey']);
  const key = await crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt'],
  );
  const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext));
  const out = new Uint8Array(salt.length + iv.length + cipher.length);
  out.set(salt, 0);
  out.set(iv, salt.length);
  out.set(cipher, salt.length + iv.length);
  return out;
}

/**
 * KURA1:  + base64url(JSON {"config": …})                 — jamais de secret
 * KURA1E: + base64url(sel16 | iv12 | AES-GCM(JSON {"config": …, "secret": …}))
 */
export async function buildConfigQr(config: object, protectedSecret?: { secret: string; pin: string }): Promise<string> {
  if (!protectedSecret) return 'KURA1:' + base64url(utf8(JSON.stringify({ config })));
  const plain = utf8(JSON.stringify({ config, secret: protectedSecret.secret }));
  return 'KURA1E:' + base64url(await encryptPayload(plain, protectedSecret.pin));
}

/** Hachage argon2id au format PHC (paramètres OWASP : 19 Mio, 2 passes). */
export async function hashPassword(password: string): Promise<string> {
  return argon2id({
    password,
    salt: crypto.getRandomValues(new Uint8Array(16)),
    parallelism: 1,
    iterations: 2,
    memorySize: 19456,
    hashLength: 32,
    outputType: 'encoded',
  });
}

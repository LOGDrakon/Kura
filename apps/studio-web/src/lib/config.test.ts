import { describe, expect, it } from 'vitest';
import { authHeaders, checkConfig, defaultConfig, expandPath, joinUrl, pruneConfig, stripDefaults } from './config';
import { base64url, buildConfigQr, encryptPayload, fromBase64url, hashPassword } from './config-qr';

const valid = (): Record<string, any> => ({ ...defaultConfig('Atelier'), server: { ...defaultConfig().server, base_url: 'https://gmao.fr/api' } });

describe('config', () => {
  it('la config par défaut, une fois l’URL saisie, est valide', () => {
    expect(checkConfig(pruneConfig(valid()))).toEqual([]);
  });

  it('erreurs lisibles en français', () => {
    const c = valid();
    c.scan.format = 'delimited';
    const issues = checkConfig(c);
    expect(issues.some((i) => i.message.includes('fields'))).toBe(true);
    c.server.base_url = 'http://gmao.exemple.fr';
    expect(checkConfig(c).some((i) => i.level === 'warning')).toBe(true);
  });

  it('retire les valeurs par défaut sans perdre le reste', () => {
    const s = stripDefaults(pruneConfig(valid()));
    expect(s.server).toEqual({ base_url: 'https://gmao.fr/api', auth: { type: 'bearer' } });
    expect(s.scan).toEqual({});
    expect(s.display.show_fields).toEqual(['name', 'location', 'quantity']);
    expect(checkConfig(s)).toEqual([]);
    expect(JSON.stringify(s).length).toBeLessThan(JSON.stringify(valid()).length / 2);
  });

  it('chemins et en-têtes', () => {
    expect(joinUrl('https://a/api/', '/items/x')).toBe('https://a/api/items/x');
    expect(expandPath('/items/{ref}', { ref: 'A/1 b' })).toBe('/items/A%2F1%20b');
    expect(authHeaders({ server: { auth: { type: 'basic', username: 'jo' } } }, 'é')).toEqual({ Authorization: 'Basic am86w6k=' });
    expect(authHeaders({ server: { auth: { type: 'api_key' } } }, 'k')).toEqual({ 'X-Api-Key': 'k' });
  });
});

describe('QR de configuration', () => {
  it('KURA1 : JSON en base64url, sans secret', async () => {
    const qr = await buildConfigQr({ a: 1 });
    expect(qr.startsWith('KURA1:')).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(fromBase64url(qr.slice(6))))).toEqual({ config: { a: 1 } });
  });

  it('KURA1E : sel | iv | AES-GCM, déchiffrable avec le PIN', async () => {
    const qr = await buildConfigQr({ a: 1 }, { secret: 'tok', pin: '123456' });
    expect(qr.startsWith('KURA1E:')).toBe(true);
    const bytes = fromBase64url(qr.slice(7));
    const salt = bytes.slice(0, 16), iv = bytes.slice(16, 28), data = bytes.slice(28);
    const material = await crypto.subtle.importKey('raw', new TextEncoder().encode('123456'), 'PBKDF2', false, ['deriveKey']);
    const key = await crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: 120000, hash: 'SHA-256' }, material, { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
    const plain = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, data)));
    expect(plain).toEqual({ config: { a: 1 }, secret: 'tok' });
  });

  it('base64url aller-retour', () => {
    const b = new Uint8Array([0, 251, 255, 62, 63]);
    expect(base64url(b)).not.toMatch(/[+/=]/);
    expect([...fromBase64url(base64url(b))]).toEqual([...b]);
    expect(encryptPayload).toBeTypeOf('function');
  });

  it('mot de passe : argon2id au format PHC', async () => {
    expect(await hashPassword('secret123')).toMatch(/^\$argon2id\$v=19\$m=19456,t=2,p=1\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$/);
  });
});

describe('vecteurs de spec/config-qr.md', () => {
  const hex = (h: string) => Uint8Array.from(h.match(/../g)!.map((b) => parseInt(b, 16)));

  it('§4.1 KURA1 en clair', async () => {
    const qr = await buildConfigQr({ kura_config_version: 1, server: { base_url: 'http://10.0.2.2:8787' }, scan: { format: 'id' } });
    expect(qr).toBe('KURA1:eyJjb25maWciOnsia3VyYV9jb25maWdfdmVyc2lvbiI6MSwic2VydmVyIjp7ImJhc2VfdXJsIjoiaHR0cDovLzEwLjAuMi4yOjg3ODcifSwic2NhbiI6eyJmb3JtYXQiOiJpZCJ9fX0');
  });

  it('§4.2 KURA1E chiffré', async () => {
    const payload = '{"config":{"kura_config_version":1,"server":{"base_url":"https://gmao.example.fr/api","auth":{"type":"bearer"}},"scan":{"format":"id"}},"secret":"jeton-de-démo"}';
    const bytes = await encryptPayload(new TextEncoder().encode(payload), '482913', hex('000102030405060708090a0b0c0d0e0f'), hex('101112131415161718191a1b'));
    expect('KURA1E:' + base64url(bytes)).toBe('KURA1E:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG03Z2DfWlrQsxWg1ahW1gxAi655SsboV_5KQ-U899HgrnDDmMaqtV16WA5IuZB4FTnRBvRlmTPOYXiEL9nQ4fJLU8VrixdrWEZSe_abTvSMbKYqK8ev5LgDUbx9Cf83qTeEHwZDZVVavO4J7SmMUzoMXKW4E_H5m90_8kjBon6oL8Zx7yhwhFtwhg4yI8aDNnWUejjgNr3e-X-mR9X57pPMeZac3cTkWiXkWPypjzoCEmbg');
  });
});

describe('withDefaults', () => {
  it('complète les sections absentes sans toucher aux valeurs ni à l’authentification', async () => {
    const { withDefaults } = await import('./config');
    const cfg = withDefaults({
      kura_config_version: 1,
      server: { base_url: 'http://localhost:8787', auth: { type: 'none' } },
      scan: { format: 'id', symbologies: ['qr_code'] },
    });
    expect(cfg.scan.trigger.aim_area).toBe(0.6);
    expect(cfg.scan.symbologies).toEqual(['qr_code']);
    expect(cfg.server.auth).toEqual({ type: 'none' });
    expect(cfg.burst.enabled).toBe(true);
  });
});

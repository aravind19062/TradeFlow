// The browser's WebAuthn API takes and returns binary data; TradeFlow's API uses base64url.

type Json = Record<string, unknown>;

function toBytes(value: string): ArrayBuffer {
  const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function toText(buffer: ArrayBuffer): string {
  let binary = '';
  for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export const passkeysSupported = () => typeof window !== 'undefined' && !!window.PublicKeyCredential;

/** True when the user dismissed the browser's passkey prompt, which isn't an error worth showing. */
export const passkeyCancelled = (e: unknown) => e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError');

export async function createPasskey(options: Json) {
  const user = options.user as Json;
  const exclude = (options.excludeCredentials as { type: 'public-key'; id: string }[]).map((c) => ({ ...c, id: toBytes(c.id) }));
  const credential = await navigator.credentials.create({
    publicKey: {
      ...options,
      challenge: toBytes(options.challenge as string),
      user: { ...user, id: toBytes(user.id as string) },
      excludeCredentials: exclude,
    } as unknown as PublicKeyCredentialCreationOptions,
  }) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAttestationResponse;
  return { clientDataJSON: toText(response.clientDataJSON), attestationObject: toText(response.attestationObject) };
}

export async function usePasskey(options: Json) {
  const credential = await navigator.credentials.get({
    publicKey: { ...options, challenge: toBytes(options.challenge as string), allowCredentials: [] } as unknown as PublicKeyCredentialRequestOptions,
  }) as PublicKeyCredential;
  const response = credential.response as AuthenticatorAssertionResponse;
  if (!response.userHandle) throw new Error('This passkey did not identify an account.');
  return {
    credentialId: credential.id,
    clientDataJSON: toText(response.clientDataJSON),
    authenticatorData: toText(response.authenticatorData),
    signature: toText(response.signature),
    userHandle: toText(response.userHandle),
  };
}

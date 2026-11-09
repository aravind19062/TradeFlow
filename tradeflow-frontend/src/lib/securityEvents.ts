/** Plain-English names for account activity. */
export const EVENT_LABELS: Record<string, string> = {
  LOGIN_SUCCEEDED: 'Signed in', LOGIN_FAILED: 'Failed sign-in attempt', MFA_CHALLENGE_FAILED: 'Wrong verification code',
  RECOVERY_CODE_USED: 'Recovery code used', MFA_ENROLLMENT_STARTED: 'Two-factor setup started', MFA_ENABLED: 'Two-factor enabled',
  MFA_DISABLED: 'Two-factor disabled', RECOVERY_CODES_REGENERATED: 'Recovery codes replaced',
  MFA_RESET_BY_ADMIN: 'Two-factor reset by an administrator', STEP_UP_SUCCEEDED: 'Live-trading verification',
  STEP_UP_FAILED: 'Failed live-trading verification', REFRESH_REUSE_DETECTED: 'Session ended: possible token theft',
  SIGNED_OUT: 'Signed out', SIGNED_OUT_EVERYWHERE: 'Signed out of all devices',
  PROFILE_UPDATED: 'Profile updated', BANK_DETAILS_SAVED: 'Bank details saved', BANK_DETAILS_REMOVED: 'Bank details removed',
  BANK_DETAILS_CHANGE_FAILED: 'Failed attempt to change bank details', SESSION_REVOKED: 'A device was signed out',
  PASSKEY_ADDED: 'Passkey added', PASSKEY_REMOVED: 'Passkey removed', PASSKEY_CHANGE_FAILED: 'Failed attempt to change passkeys',
};

export function isWarningEvent(type: string): boolean {
  return type.includes('FAILED') || type.includes('REUSE');
}

/** A readable device name from a browser's User-Agent. */
export function deviceName(userAgent: string | null): string {
  if (!userAgent) return 'Unknown device';
  const browser = /Edg\//.test(userAgent) ? 'Edge' : /OPR\//.test(userAgent) ? 'Opera' : /Firefox\//.test(userAgent) ? 'Firefox'
    : /Chrome\//.test(userAgent) ? 'Chrome' : /Safari\//.test(userAgent) ? 'Safari' : null;
  const os = /iPhone|iPad/.test(userAgent) ? 'iOS' : /Android/.test(userAgent) ? 'Android' : /Mac OS X/.test(userAgent) ? 'macOS'
    : /Windows/.test(userAgent) ? 'Windows' : /Linux/.test(userAgent) ? 'Linux' : null;
  if (browser && os) return `${browser} on ${os}`;
  return browser ?? os ?? 'Unknown device';
}

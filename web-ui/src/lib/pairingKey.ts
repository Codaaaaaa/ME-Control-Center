/** Same alphabet as the server (SecretCodes): no I, L, O, 0, 1. */
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const PAIRING_KEY_LENGTH = 12;

/**
 * Formats free-form input as `XXXX-XXXX-XXXX` while typing or pasting: uppercases, drops separators
 * and characters that can never appear in a key, and caps the length.
 */
export function formatPairingKeyInput(input: string): string {
  const characters = [...input.toUpperCase()].filter((c) => ALPHABET.includes(c)).slice(0, PAIRING_KEY_LENGTH);
  const groups: string[] = [];
  for (let i = 0; i < characters.length; i += 4) {
    groups.push(characters.slice(i, i + 4).join(''));
  }
  return groups.join('-');
}

export function isCompletePairingKey(formatted: string): boolean {
  return formatted.replaceAll('-', '').length === PAIRING_KEY_LENGTH;
}

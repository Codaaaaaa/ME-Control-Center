/** Formats a duration as compact d/h/m/s parts, e.g. 93784 -> "1d 2h 3m". */
export function formatUptime(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

/** Minecraft's TPS is capped at 20; derive it from the average tick duration. */
export function ticksPerSecond(averageTickMillis: number): number {
  if (averageTickMillis <= 0) return 20;
  return Math.min(20, 1000 / averageTickMillis);
}

export function formatMillis(value: number): string {
  return value < 10 ? value.toFixed(2) : value.toFixed(1);
}

const SI_UNITS = ['', 'k', 'M', 'G', 'T', 'P'];

/** AE energy with SI prefixes, e.g. 12400 -> "12.4 kAE". */
export function formatEnergy(value: number): string {
  let scaled = Math.abs(value);
  let unit = 0;
  while (scaled >= 1000 && unit < SI_UNITS.length - 1) {
    scaled /= 1000;
    unit++;
  }
  const digits = scaled >= 100 || unit === 0 ? 0 : 1;
  const sign = value < 0 ? '-' : '';
  return `${sign}${scaled.toFixed(digits)} ${SI_UNITS[unit]}AE`;
}

/** Percentage 0-100 of used over capacity, or null when it cannot be computed honestly. */
export function percentOf(used: number | null, capacity: number | null): number | null {
  if (used === null || capacity === null || capacity <= 0) return null;
  return Math.max(0, Math.min(100, (used / capacity) * 100));
}

export function formatCount(value: number, locale: string): string {
  return new Intl.NumberFormat(toBcp47(locale)).format(value);
}

/** Relative time such as "3 minutes ago" in the UI locale. */
export function formatRelative(iso: string, locale: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const format = new Intl.RelativeTimeFormat(toBcp47(locale), { numeric: 'auto' });
  const abs = Math.abs(seconds);
  if (abs < 45) return format.format(0, 'second');
  if (abs < 3_600) return format.format(Math.round(seconds / 60), 'minute');
  if (abs < 86_400) return format.format(Math.round(seconds / 3_600), 'hour');
  return format.format(Math.round(seconds / 86_400), 'day');
}

export function toBcp47(locale: string): string {
  return locale === 'zh_cn' ? 'zh-CN' : 'en';
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** Crafting storage the way AE2 counts it (1 KB = 1024 bytes), e.g. 65536 -> "64 KB". */
export function formatBytes(bytes: number): string {
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit === 0 || value >= 100 || Number.isInteger(value) ? 0 : 1;
  return `${value.toFixed(digits)} ${BYTE_UNITS[unit]}`;
}

/** Elapsed time as a clock, e.g. 872000 -> "14:32", 3723000 -> "1:02:03". */
export function formatElapsed(millis: number): string {
  const total = Math.max(0, Math.floor(millis / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

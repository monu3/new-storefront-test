/**
 * Money, dates and small shared UI helpers for the console.
 *
 * <p><b>Amounts are paisa integers everywhere.</b> This is the one place in the frontend allowed to
 * divide by 100, and it does so only for display. Nothing here takes a rupee string and returns
 * paisa — that conversion belongs to the form that collects it, at the moment it collects it, so
 * there is exactly one of them.
 *
 * <p>Where an endpoint sends a pre-formatted display string alongside the integer, prefer it. The
 * server has one formatter and the browser has another; two formatters produce two renderings of
 * the same total, and the one that disagrees is always the invoice the customer filed.
 */

/** Minor units to a rupee string, e.g. 123456 to `1,234.56`. No currency symbol. */
export function paisaToRupees(minor: number | null | undefined): string {
  if (minor == null || !Number.isFinite(minor)) return '0.00';
  return (minor / 100).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

/** Minor units to a display amount, e.g. `NRP 1,234.56`. */
export function formatMinor(minor: number | null | undefined): string {
  return `NRP ${paisaToRupees(minor)}`;
}

/**
 * A rupee string from an input to minor units.
 *
 * <p>`Number()` rather than `parseFloat` on purpose: `parseFloat('12abc')` returns 12 and would let
 * a typo become a price. `Number` returns NaN, which this turns into 0, and the form's own
 * validation rejects a zero price before it is ever sent.
 */
export function rupeesToPaisa(rupees: string | number | null | undefined): number {
  if (rupees == null || rupees === '') return 0;
  const value = typeof rupees === 'number' ? rupees : Number(rupees);
  if (!Number.isFinite(value) || value < 0) return 0;
  return Math.round(value * 100);
}

/** The input value for a minor-unit amount, so an amount can be shown in an editable field. */
export function minorToInput(minor: number | null | undefined): string {
  if (minor == null) return '';
  return (minor / 100).toFixed(2);
}

/**
 * A date-time, in the shop's timezone rather than the reader's.
 *
 * <p>Deliberately not `toLocaleString()` with no options: an owner in Australia reading an order
 * placed in Kathmandu should see the shop's local time, because that is the time the shop's staff
 * were working.
 */
export function formatDateTime(iso: string | null | undefined, time = false): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const options: Intl.DateTimeFormatOptions = time
    ? { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }
    : { day: 'numeric', month: 'short', year: 'numeric' };
  return date.toLocaleString('en-GB', { ...options, timeZone: 'Asia/Kathmandu' });
}

/** "3 days ago", for a last-order column. Returns null rather than a placeholder. */
export function relativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;

  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) return 'just now';

  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['minute', 60],
    ['hour', 3600],
    ['day', 86_400],
    ['week', 604_800],
    ['month', 2_592_000],
    ['year', 31_536_000],
  ];

  let label: Intl.RelativeTimeFormatUnit = 'minute';
  let divisor = 60;
  for (const [unit, secondsInUnit] of units) {
    if (seconds >= secondsInUnit) {
      label = unit;
      divisor = secondsInUnit;
    }
  }
  const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });
  return rtf.format(-Math.round(seconds / divisor), label);
}

/** Converts an ISO instant to the `YYYY-MM-DDTHH:mm` a datetime-local input expects. */
export function toDateTimeInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** Converts a datetime-local input's value back to an ISO instant, or null when blank. */
export function fromDateTimeInput(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/**
 * Turns a 400 problem response into one readable line.
 *
 * <p>Prefers `detail`, then the `fieldErrors` map, then the status text. The console shows the
 * result under the form, so "Request failed: 400" is the one message that helps nobody — it does
 * not say which field, and the owner cannot act on it.
 */
export async function readProblem(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as {
      detail?: string;
      title?: string;
      fieldErrors?: Record<string, string>;
    };
    if (body.fieldErrors && Object.keys(body.fieldErrors).length > 0) {
      return Object.entries(body.fieldErrors)
        .map(([field, message]) => `${field}: ${message}`)
        .join('; ');
    }
    if (body.detail) return body.detail;
    if (body.title) return body.title;
  } catch {
    // Not a problem+ body. Fall through to the status text.
  }
  return `The server rejected this (HTTP ${response.status})`;
}

/** Field-level errors from a validation problem, for highlighting individual inputs. */
export async function readFieldErrors(
  response: Response,
): Promise<Record<string, string>> {
  try {
    const body = (await response.json()) as { fieldErrors?: Record<string, string> };
    return body.fieldErrors ?? {};
  } catch {
    return {};
  }
}
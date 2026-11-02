// Categorical slots live in styles.css as --cat-1..--cat-8 (light and dark values from the
// validated dataviz palette); --cat-other is the neutral used for the "Inne" group.
const SLOTS = 8;

export const OTHER_COLOR = "var(--cat-other)";

/** Keys of the given positions in colour order: first activity day, then key. */
export function colorKeys(positions: readonly { key: string; firstDay: string }[]): string[] {
  return [...positions]
    .sort((a, b) => a.firstDay.localeCompare(b.firstDay) || a.key.localeCompare(b.key))
    .map((p) => p.key);
}

/**
 * Colour for a position key: its slot is its index in `allKeys`, which callers build with
 * `colorKeys` from the unfiltered view. New positions start later, so they only append and
 * never repaint existing ones. The ninth and later keys get the neutral colour, not a ninth hue.
 */
export function colorFor(key: string, allKeys: readonly string[]): string {
  const index = allKeys.indexOf(key);
  if (index < 0 || index >= SLOTS) return OTHER_COLOR;
  return `var(--cat-${index + 1})`;
}

// Categorical slots live in styles.css as --cat-1..--cat-8 (light and dark values from the
// validated dataviz palette); --cat-other is the neutral used for the "Inne" group.
const SLOTS = 8;

export const OTHER_COLOR = "var(--cat-other)";

/**
 * Colour for a position key. It depends only on the key and the set of all known keys
 * (sorted alphabetically), never on the current list order, so filtering does not repaint.
 * Keys past the eighth slot get the neutral colour rather than a ninth hue.
 */
export function colorFor(key: string, allKeys: readonly string[]): string {
  const sorted = [...new Set(allKeys)].sort();
  const index = sorted.indexOf(key);
  if (index < 0 || index >= SLOTS) return OTHER_COLOR;
  return `var(--cat-${index + 1})`;
}

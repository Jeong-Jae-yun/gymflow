export type ClassValue = string | false | null | undefined

/** Minimal className joiner — avoids pulling in a dependency for something this small. */
export function cn(...classes: ClassValue[]): string {
  return classes.filter(Boolean).join(' ')
}

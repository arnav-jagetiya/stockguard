import { useState, useEffect } from 'react';

/**
 * Hook to debounce rapidly changing values (such as text search inputs)
 * to avoid firing API requests on every keystroke.
 *
 * @param value The raw input value
 * @param delayMs Debounce delay in milliseconds (defaults to 300ms)
 * @returns The debounced value
 */
export function useDebounce<T>(value: T, delayMs: number = 300): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedValue(value);
    }, delayMs);

    return () => {
      clearTimeout(timer);
    };
  }, [value, delayMs]);

  return debouncedValue;
}

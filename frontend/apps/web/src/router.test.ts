import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  navigate,
  parseHash,
  requiresSession,
  routeHref,
  routeTitle,
  subscribeToRoute,
} from './router';

afterEach(() => {
  globalThis.location.hash = '';
});

describe('parseHash', () => {
  it('maps known hashes to route names', () => {
    expect(parseHash('#/login')).toBe('login');
    expect(parseHash('#/home')).toBe('home');
    expect(parseHash('#/jobs')).toBe('jobs');
    expect(parseHash('#/files')).toBe('files');
  });

  it('ignores query strings and trailing slashes', () => {
    expect(parseHash('#/jobs?status=running')).toBe('jobs');
    expect(parseHash('#/files/')).toBe('files');
  });

  it('returns null for unknown routes', () => {
    expect(parseHash('')).toBeNull();
    expect(parseHash('#/unknown')).toBeNull();
    expect(parseHash('#/')).toBeNull();
  });
});

describe('route metadata', () => {
  it('exposes hrefs, titles and session requirements', () => {
    expect(routeHref('jobs')).toBe('#/jobs');
    expect(routeTitle('files')).toBe('文件');
    expect(requiresSession('login')).toBe(false);
    expect(requiresSession('home')).toBe(true);
  });
});

describe('navigate', () => {
  it('updates the hash and notifies subscribers', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeToRoute(listener);

    navigate('jobs');

    expect(globalThis.location.hash).toBe('#/jobs');
    expect(listener).toHaveBeenCalledWith('jobs');

    unsubscribe();
    navigate('files');
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

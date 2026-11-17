import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  matchRoute,
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

  it('extracts single-segment route parameters', () => {
    expect(matchRoute('#/departments/abc-123')).toEqual({
      name: 'department',
      params: { orgUnitId: 'abc-123' },
    });
    expect(matchRoute('#/work/p-1')?.params.personId).toBe('p-1');
    expect(parseHash('#/departments/abc-123')).toBe('department');
    expect(parseHash('#/collaboration')).toBe('collaboration');
    expect(matchRoute('#/collaboration/c-1')?.params.channelId).toBe('c-1');
  });
});

describe('route metadata', () => {
  it('exposes hrefs, titles and session requirements', () => {
    expect(routeHref('jobs')).toBe('#/jobs');
    expect(routeHref('department', { orgUnitId: 'abc' })).toBe('#/departments/abc');
    expect(routeTitle('files')).toBe('文件');
    expect(routeHref('collaborationChannel', { channelId: 'c-1' })).toBe('#/collaboration/c-1');
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

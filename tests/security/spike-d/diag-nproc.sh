#!/bin/sh
# Find the effective RLIMIT_NPROC headroom in this WSL instance.
set -u
pkill -9 -u plugin 2>/dev/null; sleep 1
echo "threads owned by uid 1000: $(ps -eLo uid | awk '$1==1000' | wc -l)"
ps -eLf -o uid,pid,lwp,comm | awk '$1==1000' | head -20
echo
for n in 64 128 256 512; do
  echo "--- nproc=$n : spawn 4 ---"
  unshare -n -- setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs \
    prlimit --nproc=$n -- sh -c 'i=0; while [ $i -lt 4 ]; do sleep 1 & i=$((i+1)); done; echo spawned $i' 2>&1
  echo "exit=$?"
  pkill -9 -u plugin 2>/dev/null; sleep 1
  echo "--- nproc=$n : spawn 64 ---"
  unshare -n -- setpriv --reuid=plugin --regid=plugin --clear-groups --no-new-privs \
    prlimit --nproc=$n -- sh -c 'i=0; while [ $i -lt 64 ]; do sleep 1 & i=$((i+1)); done; echo spawned $i' 2>&1
  echo "exit=$?"
  pkill -9 -u plugin 2>/dev/null; sleep 1
done

import { useCallback, useEffect, useRef, useState } from 'react';

export type VoiceRecorderPhase = 'idle' | 'recording' | 'ready' | 'unsupported';

export interface VoiceRecorder {
  phase: VoiceRecorderPhase;
  seconds: number;
  blob: Blob | null;
  error: string | null;
  start: () => Promise<void>;
  stop: () => void;
  reset: () => void;
}

function pickMimeType(): string {
  const candidates = [
    'audio/webm;codecs=opus',
    'audio/webm',
    'audio/ogg;codecs=opus',
    'audio/mp4',
  ];
  if (typeof MediaRecorder.isTypeSupported !== 'function') {
    return '';
  }
  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) ?? '';
}

/**
 * 只在 start() 被调用时申请麦克风权限，绝不在挂载或页面切换时自动录音。
 * 停止后立即释放音轨，录满 maxSeconds 自动停止。
 */
export function useVoiceRecorder(maxSeconds = 120): VoiceRecorder {
  const supported = typeof MediaRecorder !== 'undefined'
    && typeof navigator !== 'undefined'
    && typeof navigator.mediaDevices?.getUserMedia === 'function';
  const [phase, setPhase] = useState<VoiceRecorderPhase>(supported ? 'idle' : 'unsupported');
  const [seconds, setSeconds] = useState(0);
  const [blob, setBlob] = useState<Blob | null>(null);
  const [error, setError] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const timerRef = useRef<number | null>(null);
  const startedAtRef = useRef(0);

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const releaseStream = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
  }, []);

  const stop = useCallback(() => {
    clearTimer();
    const recorder = recorderRef.current;
    if (recorder && recorder.state !== 'inactive') {
      recorder.stop();
    }
  }, [clearTimer]);

  const start = useCallback(async () => {
    if (!supported) {
      setError('当前浏览器不支持录音，请改用文字录入');
      return;
    }
    setError(null);
    setBlob(null);
    setSeconds(0);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mimeType = pickMimeType();
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      chunksRef.current = [];
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunksRef.current.push(event.data);
        }
      };
      recorder.onstop = () => {
        const type = recorder.mimeType || mimeType || 'audio/webm';
        setBlob(new Blob(chunksRef.current, { type }));
        setPhase('ready');
        releaseStream();
      };
      recorderRef.current = recorder;
      recorder.start();
      setPhase('recording');
      startedAtRef.current = Date.now();
      timerRef.current = window.setInterval(() => {
        const elapsed = Math.floor((Date.now() - startedAtRef.current) / 1000);
        setSeconds(elapsed);
        if (elapsed >= maxSeconds) {
          clearTimer();
          if (recorder.state !== 'inactive') {
            recorder.stop();
          }
        }
      }, 500);
    } catch {
      setError('无法访问麦克风，请检查浏览器权限后重试');
      setPhase('idle');
      releaseStream();
    }
  }, [clearTimer, maxSeconds, releaseStream, supported]);

  const reset = useCallback(() => {
    clearTimer();
    if (recorderRef.current && recorderRef.current.state !== 'inactive') {
      recorderRef.current.stop();
    }
    recorderRef.current = null;
    chunksRef.current = [];
    releaseStream();
    setBlob(null);
    setSeconds(0);
    setError(null);
    setPhase(supported ? 'idle' : 'unsupported');
  }, [clearTimer, releaseStream, supported]);

  useEffect(() => () => {
    clearTimer();
    releaseStream();
  }, [clearTimer, releaseStream]);

  return { phase, seconds, blob, error, start, stop, reset };
}

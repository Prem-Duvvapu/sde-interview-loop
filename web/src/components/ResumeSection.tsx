import { useEffect, useRef, useState } from 'react';
import { ApiError, deleteResume, describeError, getResume, uploadResume } from '../api/client';
import type { ResumeInfo } from '../api/types';

/**
 * Upload, view, and clear the resume used by the resume-deep-dive module. Self-contained
 * so it can be dropped into Settings without threading resume state through the rest of
 * that component — a resume is a persistent, cross-session resource, not per-session UI
 * state, the same way an API key is.
 */
export function ResumeSection() {
  const [state, setState] = useState<
    | { phase: 'loading' }
    | { phase: 'empty' }
    | { phase: 'ready'; resume: ResumeInfo }
    | { phase: 'error'; message: string }
  >({ phase: 'loading' });
  const [busy, setBusy] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const reload = () => {
    setState({ phase: 'loading' });
    getResume()
      .then((resume) => setState({ phase: 'ready', resume }))
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.isUnavailable) {
          setState({ phase: 'empty' });
          return;
        }
        setState({ phase: 'error', message: describeError(err) });
      });
  };

  useEffect(reload, []);

  const handleFile = async (file: File) => {
    setBusy(true);
    try {
      const resume = await uploadResume(file);
      setState({ phase: 'ready', resume });
    } catch (err: unknown) {
      setState({ phase: 'error', message: describeError(err) });
    } finally {
      setBusy(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDelete = async () => {
    setBusy(true);
    try {
      await deleteResume();
      setState({ phase: 'empty' });
    } catch (err: unknown) {
      setState({ phase: 'error', message: describeError(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="settings-section">
      <h3>Resume</h3>
      <p className="overlay-sub">
        Used only by the resume-deep-dive round. Sent to whichever provider conducts that
        round — the same way any other round content is. Stored locally, never in this
        project's public repository.
      </p>

      {state.phase === 'loading' && <p className="muted">Loading…</p>}

      {state.phase === 'error' && (
        <div className="notice notice-error">
          <p className="notice-body">{state.message}</p>
        </div>
      )}

      {state.phase === 'empty' && (
        <p className="muted">No resume on file yet.</p>
      )}

      {state.phase === 'ready' && (
        <div className="provider-card">
          <div className="provider-head">
            <div className="provider-id">
              <span className="provider-name">{state.resume.originalFilename ?? 'Resume'}</span>
              <span className="state-pill state-ok">
                <span className="state-dot" />
                on file
              </span>
            </div>
            <button type="button" className="btn btn-danger btn-sm" onClick={handleDelete} disabled={busy}>
              Remove
            </button>
          </div>
          <p className="muted">
            {state.resume.contentLength.toLocaleString()} characters extracted · uploaded{' '}
            {new Date(state.resume.uploadedAt).toLocaleString()}
          </p>
        </div>
      )}

      <div style={{ marginTop: 10 }}>
        <input
          ref={fileInputRef}
          type="file"
          accept="application/pdf"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void handleFile(file);
          }}
        />
        <p className="muted" style={{ marginTop: 4 }}>
          PDF only, up to 10MB. A scanned/image-only PDF won't have extractable text.
          Uploading replaces the current resume.
        </p>
      </div>
    </section>
  );
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ApiError,
  deleteProviderKey,
  describeError,
  getSettings,
  listProviders,
  putEvaluatorBinding,
  putInterviewerBinding,
  putProviderKey,
  verifyProvider,
} from '../api/client';
import type { AppSettings, ProviderInfo, VerifyResult } from '../api/types';
import { titleCase } from '../lib/format';
import { ResumeSection } from './ResumeSection';

interface Props {
  open: boolean;
  onClose: () => void;
}

type LoadState =
  | { phase: 'loading' }
  | { phase: 'ready' }
  | { phase: 'error'; message: string; offline: boolean; unavailable: boolean };

/** Fills in the fields the current backend does not send yet, so the UI never reads `undefined`. */
function normalize(p: ProviderInfo): Required<Pick<ProviderInfo, 'id'>> & {
  displayName: string;
  enabled: boolean;
  configured: boolean;
  keySource: 'ui' | 'env' | 'none';
  maskedKey: string | null;
  models: NonNullable<ProviderInfo['models']>;
  streaming: boolean;
  toolUse: boolean;
  vision: boolean;
  caching: string;
  meetsFloor: boolean;
} {
  const caps = p.capabilities ?? {};
  const streaming = caps.streaming === true;
  const toolUse = caps.toolUse === true;
  const cachingRaw = caps.promptCaching;
  const caching =
    typeof cachingRaw === 'string' ? cachingRaw : cachingRaw === true ? 'SUPPORTED' : 'NONE';
  const keySource = p.keySource ?? (p.configured ? 'env' : 'none');
  return {
    id: p.id,
    displayName: p.displayName ?? titleCase(p.id),
    enabled: p.enabled ?? true,
    // The pre-settings backend has no `configured` field; an enabled provider there
    // implies a key was discovered from the environment.
    configured: p.configured ?? (p.enabled ?? false),
    keySource,
    maskedKey: p.maskedKey ?? null,
    models: p.models ?? [],
    streaming,
    toolUse,
    vision: caps.vision === true,
    caching,
    meetsFloor: p.meetsInterviewerFloor ?? (streaming && toolUse),
  };
}

type NormalProvider = ReturnType<typeof normalize>;

export function SettingsOverlay({ open, onClose }: Props) {
  const [load, setLoad] = useState<LoadState>({ phase: 'loading' });
  const [providers, setProviders] = useState<ProviderInfo[]>([]);
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [settingsUnavailable, setSettingsUnavailable] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const panelRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoad({ phase: 'loading' });

    (async () => {
      try {
        const list = await listProviders();
        if (cancelled) return;
        setProviders(Array.isArray(list) ? list : []);
        setLoad({ phase: 'ready' });
      } catch (err: unknown) {
        if (cancelled) return;
        setLoad({
          phase: 'error',
          message: describeError(err),
          offline: err instanceof ApiError && err.kind === 'offline',
          unavailable: err instanceof ApiError && err.isUnavailable,
        });
        return;
      }

      try {
        const s = await getSettings();
        if (cancelled) return;
        setSettings(s ?? null);
        setSettingsUnavailable(false);
      } catch (err: unknown) {
        if (cancelled) return;
        setSettings(null);
        setSettingsUnavailable(true);
        if (!(err instanceof ApiError && err.isUnavailable)) {
          console.warn('[settings] role bindings unavailable:', describeError(err));
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [open, reloadKey]);

  useEffect(() => {
    if (!open) return;
    panelRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const patchProvider = useCallback((id: string, patch: Partial<ProviderInfo>) => {
    setProviders((prev) => prev.map((p) => (p.id === id ? { ...p, ...patch } : p)));
  }, []);

  const normalized = useMemo(() => providers.map(normalize), [providers]);

  if (!open) return null;

  return (
    <div className="overlay" role="presentation" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        className="overlay-panel"
        role="dialog"
        aria-modal="true"
        aria-label="Settings"
        tabIndex={-1}
        ref={panelRef}
      >
        <header className="overlay-head">
          <div>
            <h2>Settings</h2>
            <p className="overlay-sub">Providers, API keys, and the interviewer / evaluator bindings.</p>
          </div>
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="overlay-body">
          {load.phase === 'loading' && (
            <div className="skeleton-list" aria-busy="true">
              {Array.from({ length: 3 }).map((_, i) => (
                <div className="skeleton-card" key={i} />
              ))}
            </div>
          )}

          {load.phase === 'error' && (
            <div className="notice notice-error">
              <p className="notice-title">
                {load.offline
                  ? 'Backend is not running.'
                  : load.unavailable
                    ? 'Provider settings are not implemented on the backend yet.'
                    : 'Could not load providers.'}
              </p>
              <p className="notice-body">
                {load.offline
                  ? 'Start the Spring Boot app on localhost:8123, then reopen settings.'
                  : load.unavailable
                    ? 'This screen will start working as soon as /api/providers ships. Nothing else is affected.'
                    : load.message}
              </p>
              <button type="button" className="btn btn-ghost" onClick={() => setReloadKey((k) => k + 1)}>
                Retry
              </button>
            </div>
          )}

          {load.phase === 'ready' && (
            <>
              <RoleBindings
                providers={normalized}
                settings={settings}
                unavailable={settingsUnavailable}
                onSettings={setSettings}
              />

              <ResumeSection />

              <section className="settings-section">
                <h3>Providers</h3>
                {normalized.length === 0 && (
                  <p className="muted">The backend reported no providers.</p>
                )}
                <div className="provider-grid">
                  {normalized.map((p) => (
                    <ProviderCard key={p.id} provider={p} onPatch={patchProvider} />
                  ))}
                </div>
              </section>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- provider card

function ProviderCard({
  provider,
  onPatch,
}: {
  provider: NormalProvider;
  onPatch: (id: string, patch: Partial<ProviderInfo>) => void;
}) {
  // The draft key is component-local and cleared the moment it is sent. It is never
  // written to localStorage and never logged — the server only ever hands back a mask.
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState<null | 'save' | 'clear' | 'verify'>(null);
  const [error, setError] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [verify, setVerify] = useState<VerifyResult | null>(null);

  const envManaged = provider.keySource === 'env';

  const run = async (kind: 'save' | 'clear' | 'verify') => {
    setBusy(kind);
    setError(null);
    setVerify(null);
    try {
      if (kind === 'save') {
        const res = await putProviderKey(provider.id, draft);
        setDraft('');
        onPatch(provider.id, {
          configured: res?.configured ?? true,
          keySource: res?.keySource ?? 'ui',
          maskedKey: res?.maskedKey ?? null,
        });
      } else if (kind === 'clear') {
        const res = await deleteProviderKey(provider.id);
        onPatch(provider.id, {
          configured: res?.configured ?? false,
          keySource: res?.keySource ?? 'none',
          maskedKey: null,
        });
      } else {
        const res = await verifyProvider(provider.id);
        setVerify(res ?? { ok: false, message: 'Empty response' });
      }
    } catch (err: unknown) {
      if (err instanceof ApiError && err.isUnavailable) {
        setUnavailable(true);
      } else {
        setError(describeError(err));
      }
    } finally {
      setBusy(null);
    }
  };

  const state = provider.configured
    ? verify?.ok
      ? { cls: 'ok', label: 'Verified' }
      : { cls: 'set', label: 'Key set' }
    : { cls: 'none', label: 'Not configured' };

  return (
    <article className={`provider-card${provider.configured ? '' : ' is-unconfigured'}`}>
      <header className="provider-head">
        <div className="provider-id">
          <span className="provider-name">{provider.displayName}</span>
          <code>{provider.id}</code>
        </div>
        <span className={`state-pill state-${state.cls}`}>
          <span className="state-dot" aria-hidden="true" />
          {state.label}
        </span>
      </header>

      <div className="cap-chips">
        <Cap on={provider.streaming} label="streaming" />
        <Cap on={provider.toolUse} label="tool use" />
        <Cap on={provider.vision} label="vision" />
        <span className="cap-chip cap-info" title="Prompt caching mechanism">
          cache: {provider.caching.toLowerCase()}
        </span>
      </div>

      {!provider.meetsFloor && (
        <p className="floor-warn">
          Below the interviewer floor — the phase machine needs streaming and tool use.
        </p>
      )}

      {unavailable ? (
        <p className="muted small">
          Key management for this provider is not implemented on the backend yet.
        </p>
      ) : envManaged ? (
        <div className="key-row">
          <span className="key-mask">{provider.maskedKey ?? '••••••••'}</span>
          <span className="chip chip-env">from environment</span>
        </div>
      ) : (
        <>
          {provider.configured && provider.maskedKey && (
            <div className="key-row">
              <span className="key-mask">{provider.maskedKey}</span>
              <button
                type="button"
                className="link-btn danger"
                onClick={() => run('clear')}
                disabled={busy !== null}
              >
                {busy === 'clear' ? 'Removing…' : 'Remove'}
              </button>
            </div>
          )}
          <form
            className="key-form"
            onSubmit={(e) => {
              e.preventDefault();
              if (draft.trim()) void run('save');
            }}
          >
            <label className="visually-hidden" htmlFor={`key-${provider.id}`}>
              {provider.displayName} API key
            </label>
            <input
              id={`key-${provider.id}`}
              className="input"
              type="password"
              autoComplete="off"
              spellCheck={false}
              placeholder={provider.configured ? 'Replace key…' : 'Paste API key'}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
            <button type="submit" className="btn btn-ghost" disabled={busy !== null || draft.trim() === ''}>
              {busy === 'save' ? 'Saving…' : 'Save'}
            </button>
          </form>
        </>
      )}

      <div className="provider-foot">
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={() => void run('verify')}
          disabled={busy !== null || !provider.configured || unavailable}
          title={provider.configured ? 'Make a real call to this provider' : 'Add a key first'}
        >
          {busy === 'verify' ? 'Verifying…' : 'Verify'}
        </button>
        {verify && (
          <span className={`verify-result ${verify.ok ? 'is-ok' : 'is-bad'}`}>
            {verify.ok ? '✓' : '✕'}{' '}
            {verify.ok
              ? `${verify.model ?? 'responded'}${verify.latencyMs != null ? ` · ${verify.latencyMs}ms` : ''}`
              : (verify.message ?? 'Verification failed')}
          </span>
        )}
        {error && <span className="verify-result is-bad">{error}</span>}
      </div>

      {provider.models.length > 0 && (
        <ul className="model-list">
          {provider.models.map((m) => (
            <li key={m.id}>
              <code>{m.id}</code>
              {m.roleHint && <span className="chip chip-tiny">{m.roleHint}</span>}
              {m.contextTokens != null && <span className="muted small">{Math.round(m.contextTokens / 1000)}k ctx</span>}
              {m.notes && <span className="muted small">{m.notes}</span>}
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

function Cap({ on, label }: { on: boolean; label: string }) {
  return <span className={`cap-chip${on ? ' cap-on' : ' cap-off'}`}>{label}</span>;
}

// ---------------------------------------------------------------- role bindings

function RoleBindings({
  providers,
  settings,
  unavailable,
  onSettings,
}: {
  providers: NormalProvider[];
  settings: AppSettings | null;
  unavailable: boolean;
  onSettings: (s: AppSettings) => void;
}) {
  const [pendingEvaluator, setPendingEvaluator] = useState<{ provider: string; model: string } | null>(null);
  const [busy, setBusy] = useState<'interviewer' | 'evaluator' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const epoch = settings?.evaluator?.comparabilityEpoch ?? null;

  if (unavailable) {
    return (
      <section className="settings-section">
        <h3>Roles</h3>
        <div className="notice notice-info compact">
          <p className="notice-body">
            Interviewer / evaluator bindings are not exposed by the backend yet
            (<code>/api/settings</code>). Until then they come from <code>config/providers.yaml</code>.
          </p>
        </div>
      </section>
    );
  }

  const commit = async (role: 'interviewer' | 'evaluator', provider: string, model: string) => {
    setBusy(role);
    setError(null);
    try {
      const next =
        role === 'interviewer'
          ? await putInterviewerBinding(provider, model)
          : await putEvaluatorBinding(provider, model);
      if (next) onSettings(next);
      else onSettings({ ...settings, [role]: { provider, model } } as AppSettings);
    } catch (err: unknown) {
      setError(describeError(err));
    } finally {
      setBusy(null);
      setPendingEvaluator(null);
    }
  };

  return (
    <section className="settings-section">
      <h3>Roles</h3>

      <div className="role-grid">
        <RolePicker
          role="interviewer"
          title="Interviewer"
          blurb="Any configured provider that meets the capability floor may conduct a round."
          providers={providers.filter((p) => p.configured && p.meetsFloor)}
          allProviders={providers}
          value={settings?.interviewer ?? null}
          busy={busy === 'interviewer'}
          onCommit={(provider, model) => void commit('interviewer', provider, model)}
        />

        <RolePicker
          role="evaluator"
          title="Evaluator"
          blurb="Rubric scoring is pinned to one provider and model so readiness trends measure your progress, not provider drift."
          providers={providers.filter((p) => p.configured && p.meetsFloor)}
          allProviders={providers}
          value={settings?.evaluator ?? null}
          busy={busy === 'evaluator'}
          epoch={epoch}
          onCommit={(provider, model) => setPendingEvaluator({ provider, model })}
        />
      </div>

      {error && (
        <div className="notice notice-error compact">
          <p className="notice-body">{error}</p>
        </div>
      )}

      {pendingEvaluator && (
        <ConfirmEpochDialog
          provider={pendingEvaluator.provider}
          model={pendingEvaluator.model}
          currentEpoch={epoch}
          onCancel={() => setPendingEvaluator(null)}
          onConfirm={() => void commit('evaluator', pendingEvaluator.provider, pendingEvaluator.model)}
        />
      )}
    </section>
  );
}

function RolePicker({
  role,
  title,
  blurb,
  providers,
  allProviders,
  value,
  busy,
  epoch,
  onCommit,
}: {
  role: 'interviewer' | 'evaluator';
  title: string;
  blurb: string;
  providers: NormalProvider[];
  allProviders: NormalProvider[];
  value: { provider?: string | null; model?: string | null } | null;
  busy: boolean;
  epoch?: number | null;
  onCommit: (provider: string, model: string) => void;
}) {
  const [providerId, setProviderId] = useState(value?.provider ?? providers[0]?.id ?? '');
  const [model, setModel] = useState(value?.model ?? '');

  useEffect(() => {
    setProviderId(value?.provider ?? providers[0]?.id ?? '');
    setModel(value?.model ?? '');
    // Re-sync only when the server-side binding changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value?.provider, value?.model]);

  const selected = allProviders.find((p) => p.id === providerId) ?? null;
  const models = selected?.models ?? [];
  const dirty = providerId !== (value?.provider ?? '') || model !== (value?.model ?? '');
  const blocked = allProviders.filter((p) => !p.meetsFloor || !p.configured);

  return (
    <div className="role-card">
      <div className="role-head">
        <h4>{title}</h4>
        {epoch != null && <span className="chip chip-epoch">epoch {epoch}</span>}
      </div>
      <p className="role-blurb">{blurb}</p>

      <div className="role-fields">
        <div className="field">
          <label className="field-label" htmlFor={`${role}-provider`}>
            Provider
          </label>
          <select
            id={`${role}-provider`}
            className="select"
            value={providerId}
            onChange={(e) => {
              setProviderId(e.target.value);
              setModel('');
            }}
          >
            <option value="">— none —</option>
            {providers.map((p) => (
              <option key={p.id} value={p.id}>
                {p.displayName}
              </option>
            ))}
            {blocked.map((p) => (
              <option key={p.id} value={p.id} disabled>
                {p.displayName} — {!p.configured ? 'no key' : 'below capability floor'}
              </option>
            ))}
          </select>
        </div>

        <div className="field">
          <label className="field-label" htmlFor={`${role}-model`}>
            Model
          </label>
          {models.length > 0 ? (
            <select
              id={`${role}-model`}
              className="select"
              value={model}
              onChange={(e) => setModel(e.target.value)}
            >
              <option value="">— pick a model —</option>
              {models.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.id}
                  {m.roleHint ? ` (${m.roleHint})` : ''}
                </option>
              ))}
            </select>
          ) : (
            <input
              id={`${role}-model`}
              className="input"
              placeholder="model id"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              spellCheck={false}
            />
          )}
        </div>
      </div>

      <button
        type="button"
        className="btn btn-ghost btn-sm"
        disabled={busy || !dirty || providerId === '' || model.trim() === ''}
        onClick={() => onCommit(providerId, model.trim())}
      >
        {busy ? 'Saving…' : role === 'evaluator' ? 'Change evaluator…' : 'Apply'}
      </button>
    </div>
  );
}

function ConfirmEpochDialog({
  provider,
  model,
  currentEpoch,
  onCancel,
  onConfirm,
}: {
  provider: string;
  model: string;
  currentEpoch: number | null;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    ref.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onCancel]);

  return (
    <div className="overlay overlay-nested" role="presentation">
      <div className="confirm-panel" role="alertdialog" aria-modal="true" aria-label="Confirm evaluator change" tabIndex={-1} ref={ref}>
        <h3>This starts a new comparability epoch</h3>
        <p>
          Rubric scoring is pinned to one provider and model on purpose. If you switch the evaluator to{' '}
          <code>{provider}</code> / <code>{model}</code>, every score recorded from now on belongs to a new
          epoch{currentEpoch != null ? ` (epoch ${currentEpoch} → ${currentEpoch + 1})` : ''}.
        </p>
        <p>
          <strong>Scores before and after the switch are not comparable.</strong> Your readiness trend
          restarts — it will be measuring the new evaluator, not your progress, until you have enough
          sessions on it.
        </p>
        <div className="confirm-actions">
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            Keep current evaluator
          </button>
          <button type="button" className="btn btn-danger" onClick={onConfirm}>
            Change evaluator and start a new epoch
          </button>
        </div>
      </div>
    </div>
  );
}

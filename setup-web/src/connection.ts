export const sourceIds = ['KONOMITV', 'EDCB', 'MIRAKURUN_ONLY'] as const

export type SourceId = (typeof sourceIds)[number]
export const connectionPlaceholder = 'https://192-168-xxx-xxx.local.konomi.tv'

export interface ConnectionSource {
  id: SourceId
  label: string
  host: string
  port: string
  httpPort?: string
}

export interface ConnectionSnapshot {
  sources: ConnectionSource[]
  mainSystem: SourceId | null
  scope: SourceId | null
}

type ConnectionResponse = {
  sources?: Array<Partial<ConnectionSource> & { port?: string | number | null; httpPort?: string | number | null }>
  mainSystem?: unknown
  scope?: unknown
}

export const defaults: Record<SourceId, Omit<ConnectionSource, 'label'>> = {
  KONOMITV: { id: 'KONOMITV', host: '', port: '7000' },
  EDCB: { id: 'EDCB', host: '', port: '4510', httpPort: '5510' },
  MIRAKURUN_ONLY: { id: 'MIRAKURUN_ONLY', host: '', port: '40772' },
}

export const labels: Record<SourceId, string> = {
  KONOMITV: 'KonomiTV',
  EDCB: 'EDCB',
  MIRAKURUN_ONLY: 'Mirakurun',
}

function portString(value: string | number | null | undefined, fallback: string): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : fallback
}

export function blankSnapshot(scope: SourceId | null = null): ConnectionSnapshot {
  return {
    sources: sourceIds.map((id) => ({ ...defaults[id], label: labels[id] })),
    mainSystem: null,
    scope,
  }
}

export function isPort(value: string): boolean {
  return /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 65535
}

export function isHttpPort(value: string): boolean {
  const match = /^(\d+)(s?)$/.exec(value)
  return match !== null && Number(match[1]) >= 1 && Number(match[1]) <= 65535
}

export function isConfigured(source: ConnectionSource): boolean {
  return source.host.trim() !== '' && source.host.trim() !== connectionPlaceholder && isPort(source.port)
}

export function validSource(source: ConnectionSource): boolean {
  if (!isPort(source.port)) return false
  if (source.id === 'EDCB' && !isHttpPort(source.httpPort ?? '')) return false
  return source.host.trim() === '' || isConfigured(source)
}

export function normalizeSnapshot(input: ConnectionResponse, scope: SourceId | null): ConnectionSnapshot {
  const sources = input.sources ?? []
  const byId = new Map(sources.filter((source) => sourceIds.includes(source.id as SourceId)).map((source) => [source.id as SourceId, source]))
  return {
    sources: sourceIds.map((id) => {
      const saved = byId.get(id)
      const base = defaults[id]
      return {
        ...base,
        id,
        label: typeof saved?.label === 'string' && saved.label !== '' ? saved.label : labels[id],
        host: typeof saved?.host === 'string' ? saved.host : base.host,
        port: portString(saved?.port, base.port),
        ...(id === 'EDCB' ? { httpPort: portString(saved?.httpPort, base.httpPort ?? '') } : {}),
      }
    }),
    mainSystem: sourceIds.includes(input.mainSystem as SourceId) ? input.mainSystem as SourceId : null,
    scope: sourceIds.includes(input.scope as SourceId) ? input.scope as SourceId : scope,
  }
}

export function resolveMainSystem(sources: ConnectionSource[], selected: SourceId | null): SourceId | null {
  const available = sources.filter(isConfigured).map((source) => source.id)
  if (available.length === 1) return available[0]
  if (selected === null) return null
  return available.includes(selected) ? selected : available[0] ?? null
}

export function canSave(sources: ConnectionSource[], selected: SourceId | null): boolean {
  return sources.some(isConfigured) && resolveMainSystem(sources, selected) !== null && sources.every(validSource)
}

export function saveBody(sources: ConnectionSource[], mainSystem: SourceId | null) {
  return {
    sources: sources.map(({ id, host, port, httpPort }) => ({
      id,
      host: host.trim(),
      port: String(port),
      ...(id === 'EDCB' && httpPort !== undefined ? { httpPort: String(httpPort) } : {}),
    })),
    mainSystem,
  }
}

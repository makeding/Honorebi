import { isConfigured, normalizeSnapshot, validAccess, validSource, type ConnectionSnapshot } from './connection'
import { applyDraft, createDraft } from './draft'

const konomi = (settings: ConnectionSnapshot) => settings.sources.find(source => source.id === 'KONOMITV')!
const accessId = (settings: ConnectionSnapshot) => settings.cloudflareAccess.clientId.replace(/\s/g, '')

export function pairingInputError(settings: ConnectionSnapshot): string {
  const source = konomi(settings)
  if (!isConfigured(source) || !validSource(source) || /\s/.test(source.host.trim()))
    return 'KonomiTV のアドレスとポート番号を入力してください。'
  if (!validAccess(settings.cloudflareAccess))
    return 'Client ID と Client Secret を両方入力するか、両方消去してください。'
  return ''
}

export function pairingConnectionChanged(current: ConnectionSnapshot, baseline: ConnectionSnapshot): boolean {
  const source = konomi(current), before = konomi(baseline)
  return source.host.trim() !== before.host.trim() || source.port.trim() !== before.port.trim() ||
    accessId(current) !== accessId(baseline) || current.cloudflareAccess.clientSecret !== null
}

/** Save only fields needed by the provider, then retain unrelated local edits as drafts. */
export async function preparePairingConnection(
  current: ConnectionSnapshot,
  baseline: ConnectionSnapshot,
  read: () => Promise<ConnectionSnapshot>,
  save: (settings: ConnectionSnapshot) => Promise<ConnectionSnapshot>,
): Promise<{ saved: ConnectionSnapshot; local: ConnectionSnapshot }> {
  const invalid = pairingInputError(current)
  if (invalid) throw new Error(invalid)
  if (!pairingConnectionChanged(current, baseline)) return { saved: baseline, local: current }
  const fresh = normalizeSnapshot(await read())
  const source = konomi(current), before = konomi(baseline)
  if (source.host.trim() !== before.host.trim() || source.port.trim() !== before.port.trim()) {
    konomi(fresh).host = source.host.trim()
    konomi(fresh).port = source.port.trim()
  }
  if (accessId(current) !== accessId(baseline) || current.cloudflareAccess.clientSecret !== null)
    fresh.cloudflareAccess = { ...current.cloudflareAccess }
  const saved = normalizeSnapshot(await save(fresh))
  const local = applyDraft(saved, createDraft(baseline, current))
  local.sources = local.sources.map(source => source.id === 'KONOMITV' ? { ...konomi(saved) } : source)
  local.cloudflareAccess = { ...saved.cloudflareAccess }
  return { saved, local }
}

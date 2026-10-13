import { describe, expect, it } from 'vitest'
import { blankSnapshot, canSave, connectionPlaceholder, isConfigured, isHttpPort, normalizeSnapshot, resolveMainSystem, saveBody, setupTokenFromHash, type ConnectionSource } from '../src/connection'

const source = (id: ConnectionSource['id'], host: string, port: string, httpPort?: string): ConnectionSource => ({ id, label: id, host, port, httpPort })

describe('connection form behavior', () => {
  it('always starts with all three source cards and expected ports', () => {
    expect(blankSnapshot().sources.map(({ id, port }) => [id, port])).toEqual([
      ['KONOMITV', '7000'], ['EDCB', '4510'], ['MIRAKURUN_ONLY', '40772'],
    ])
  })
  it('uses a reusable setup secret only from a URL fragment', () => {
    expect(setupTokenFromHash('#a-secret')).toBe('a-secret')
    expect(setupTokenFromHash('a-secret')).toBe('')
    expect(setupTokenFromHash('')).toBe('')
  })
  it('normalizes the full editable settings schema without returning existing secrets', () => {
    const snapshot = normalizeSnapshot({
      sources: [{ id: 'KONOMITV', host: '192.168.1.5', port: 7000 }], mainSystem: 'KONOMITV', preferredSource: 'EDCB', edcbRecordPlayMethod: 'API',
      cloudflareAccess: { clientId: 'id', secretConfigured: true }, smbServers: [{ id: 'nas', name: 'NAS', ip: '192.168.1.2', port: 445, user: 'u', passwordConfigured: true }], account: { userName: 'huggy' },
    })
    expect(snapshot.sources).toHaveLength(3)
    expect(snapshot.preferredSource).toBe('EDCB')
    expect(snapshot.cloudflareAccess.clientSecret).toBeNull()
    expect(snapshot.smbServers[0].password).toBeNull()
    expect(snapshot.account.userName).toBe('huggy')
  })
  it('rejects exact placeholder and preserves EDCB secure HTTP port validation', () => {
    expect(isConfigured(source('KONOMITV', connectionPlaceholder, '7000'))).toBe(false)
    expect(isConfigured(source('KONOMITV', 'https://192-168-1-20.local.konomi.tv', '7000'))).toBe(true)
    expect(isHttpPort('5511s')).toBe(true)
    expect(canSave([source('EDCB', 'edcb.local', '4510', '70000s')], 'EDCB')).toBe(false)
  })
  it('auto-selects the only configured source and preserves explicit secret clears', () => {
    const snapshot = blankSnapshot()
    snapshot.sources[0].host = 'tv.local'
    snapshot.cloudflareAccess.clientSecret = ''
    snapshot.smbServers.push({ id: 'nas', name: 'NAS', ip: 'nas.local', port: '445', user: 'u', password: '', passwordConfigured: true })
    expect(resolveMainSystem(snapshot.sources, 'EDCB')).toBe('KONOMITV')
    expect(saveBody(snapshot)).toMatchObject({
      mainSystem: 'KONOMITV', cloudflareAccess: { clientSecret: '' }, smbServers: [{ password: '' }],
    })
  })
  it('does not include account or setup credentials in the save body', () => {
    const body = saveBody(blankSnapshot())
    expect(JSON.stringify(body)).not.toContain('account')
    expect(JSON.stringify(body)).not.toContain('Setup-Token')
  })
})

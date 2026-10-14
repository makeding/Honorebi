import { describe, expect, it } from 'vitest'
import { blankSnapshot } from '../src/connection'
import { applyDraft, createDraft } from '../src/draft'

describe('connection drafts across QR sessions', () => {
  it('restores edits without overwriting fresh TV values or stored-password flags', () => {
    const base = blankSnapshot()
    base.sources[0].host = 'tv.local'
    base.smbServers = [{ id: 'nas', name: 'NAS', ip: 'nas.local', port: '445', user: 'user', password: null, passwordConfigured: true }]
    const current = structuredClone(base)
    current.sources[0].port = '443'
    current.smbServers[0].name = 'Edited NAS'
    const draft = createDraft(base, current)
    const fresh = structuredClone(base)
    fresh.sources[1].host = 'edcb.local'
    fresh.smbServers[0].port = '1445'
    fresh.cloudflareAccess.secretConfigured = true
    const restored = applyDraft(fresh, draft)
    expect(restored.sources[0].port).toBe('443')
    expect(restored.sources[1].host).toBe('edcb.local')
    expect(restored.smbServers[0]).toMatchObject({ name: 'Edited NAS', port: '1445', password: null, passwordConfigured: true })
    expect(restored.cloudflareAccess.secretConfigured).toBe(true)
  })
  it('preserves explicit credentials and SMB additions/removals without persisting auth or account state', () => {
    const base = blankSnapshot()
    base.smbServers = [{ id: 'old', name: 'Old', ip: 'old.local', port: '445', user: '', password: null, passwordConfigured: true }]
    base.account.userName = 'private-user'
    const current = structuredClone(base)
    current.smbServers = [{ id: 'new', name: 'New', ip: 'new.local', port: '445', user: '', password: '', passwordConfigured: false }]
    current.cloudflareAccess.clientSecret = 'typed-secret'
    const draft = createDraft(base, current)
    expect(JSON.stringify(draft)).not.toContain('private-user')
    expect(JSON.stringify(draft)).not.toContain('account')
    const restored = applyDraft(base, draft)
    expect(restored.smbServers.map((server) => server.id)).toEqual(['new'])
    expect(restored.smbServers[0].password).toBe('')
    expect(restored.cloudflareAccess.clientSecret).toBe('typed-secret')
  })
  it('retains a draft written by an already-open initial page, then upgrades on the next edit', () => {
    const fresh = blankSnapshot()
    fresh.smbServers = [{ id: 'nas', name: 'NAS', ip: 'nas.local', port: '445', user: '', password: null, passwordConfigured: true }]
    const flat = { ...createDraft(fresh, fresh).current, preferredSource: 'EDCB' }
    const restored = applyDraft(fresh, flat)
    expect(restored.preferredSource).toBe('EDCB')
    expect(restored.smbServers[0].passwordConfigured).toBe(true)
    expect(createDraft(fresh, restored).version).toBe(2)
  })
})

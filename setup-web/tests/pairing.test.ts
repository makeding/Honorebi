import { describe, expect, it } from 'vitest'
import { blankSnapshot, normalizeSnapshot, saveBody } from '../src/connection'
import { pairingConnectionChanged, pairingInputError, preparePairingConnection } from '../src/pairing'

function configured() {
  const settings = blankSnapshot()
  settings.sources[0].host = 'https://tv.example.org'
  settings.sources[0].port = '443'
  settings.mainSystem = 'KONOMITV'
  return settings
}

describe('phone account linking', () => {
  it('does not block a saved connection because of read-only labels', async () => {
    const baseline = configured(), current = structuredClone(baseline)
    current.sources[0].label = 'KonomiTV display label'
    // The former whole-source comparison also differed from the label-free draft baseline.
    expect(JSON.stringify(current.sources[0])).not.toBe(JSON.stringify(saveBody(baseline).sources[0]))
    expect(pairingConnectionChanged(current, baseline)).toBe(false)
    const result = await preparePairingConnection(current, baseline,
      async () => { throw new Error('Unchanged connection must not require another save') },
      async () => { throw new Error('Unchanged connection must not require another save') })
    expect(result.local).toBe(current)
  })

  it('saves the edited provider connection while retaining unrelated invalid drafts', async () => {
    const baseline = configured(), current = structuredClone(baseline), fresh = structuredClone(baseline)
    current.sources[0].host = 'https://new.example.org'
    current.sources[1].port = 'invalid local draft'
    current.preferredSource = 'EDCB'
    fresh.preferredSource = 'MIRAKURUN'
    fresh.sources[1].host = '192.168.1.40'
    const order: string[] = []
    const result = await preparePairingConnection(current, baseline,
      async () => { order.push('read'); return fresh },
      async settings => {
        order.push('save')
        expect(settings.sources[0].host).toBe('https://new.example.org')
        expect(settings.sources[1]).toEqual(fresh.sources[1])
        expect(settings.preferredSource).toBe('MIRAKURUN')
        return settings
      })
    expect(order).toEqual(['read', 'save'])
    expect(result.saved.sources[0].host).toBe('https://new.example.org')
    expect(result.local.sources[1].port).toBe('invalid local draft')
    expect(result.local.sources[1].host).toBe('192.168.1.40')
    expect(result.local.preferredSource).toBe('EDCB')
    expect(pairingConnectionChanged(result.local, result.saved)).toBe(false)
  })

  it('applies provider credentials without retaining a saved secret in the draft', async () => {
    const baseline = configured(), current = structuredClone(baseline)
    current.cloudflareAccess = { clientId: 'access-id', clientSecret: 'new-secret', secretConfigured: false }
    const result = await preparePairingConnection(current, baseline, async () => baseline, async settings => {
      expect(saveBody(settings).cloudflareAccess.clientSecret).toBe('new-secret')
      return normalizeSnapshot({ ...settings, cloudflareAccess: { clientId: 'access-id', secretConfigured: true } })
    })
    expect(result.local.cloudflareAccess.clientSecret).toBeNull()
    expect(result.local.cloudflareAccess.secretConfigured).toBe(true)
    expect(pairingConnectionChanged(result.local, result.saved)).toBe(false)
  })

  it('retains input after failed persistence and rejects invalid provider input before requests', async () => {
    const baseline = configured(), current = structuredClone(baseline)
    current.sources[0].host = 'https://new.example.org'
    await expect(preparePairingConnection(current, baseline, async () => baseline,
      async () => { throw new Error('SAVE_FAILED') })).rejects.toThrow('SAVE_FAILED')
    expect(current.sources[0].host).toBe('https://new.example.org')
    current.sources[0].port = '0'
    expect(pairingInputError(current)).toContain('ポート番号')
    await expect(preparePairingConnection(current, baseline,
      async () => { throw new Error('Must not request') }, async () => baseline)).rejects.toThrow('ポート番号')
  })
})

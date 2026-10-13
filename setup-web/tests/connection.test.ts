import { describe, expect, it } from 'vitest'
import { blankSnapshot, canSave, connectionPlaceholder, isConfigured, isHttpPort, normalizeSnapshot, resolveMainSystem, saveBody, type ConnectionSource } from '../src/connection'

const source = (id: ConnectionSource['id'], host: string, port: string, httpPort?: string): ConnectionSource => ({ id, label: id, host, port, httpPort })

describe('connection form behavior', () => {
  it('starts all three sources with the expected ports', () => {
    expect(blankSnapshot().sources.map(({ id, port }) => [id, port])).toEqual([
      ['KONOMITV', '7000'], ['EDCB', '4510'], ['MIRAKURUN_ONLY', '40772'],
    ])
  })

  it('accepts Kotlin string ports from GET and auto-selects the only configured source', () => {
    const sources = normalizeSnapshot({ sources: [{ id: 'KONOMITV', host: '192.168.1.5', port: '7000' }] }, null).sources
    expect(sources[0].port).toBe('7000')
    expect(resolveMainSystem(sources, 'EDCB')).toBe('KONOMITV')
  })

  it('rejects only the exact placeholder while retaining valid local KonomiTV hosts', () => {
    expect(isConfigured(source('KONOMITV', connectionPlaceholder, '7000'))).toBe(false)
    expect(isConfigured(source('KONOMITV', 'https://192-168-1-20.local.konomi.tv', '7000'))).toBe(true)
  })

  it('preserves the EDCB secure HTTP suffix and blocks invalid or empty configurations', () => {
    expect(isHttpPort('5511s')).toBe(true)
    expect(isHttpPort('70000s')).toBe(false)
    expect(canSave([], null)).toBe(false)
    expect(canSave([source('EDCB', 'edcb.local', '4510', '70000s')], 'EDCB')).toBe(false)
  })

  it('serializes ports as strings without labels or query scope', () => {
    expect(saveBody([source('KONOMITV', ' tv.local ', '7000')], 'KONOMITV')).toEqual({
      sources: [{ id: 'KONOMITV', host: 'tv.local', port: '7000' }], mainSystem: 'KONOMITV',
    })
  })
})

import { describe, expect, it } from 'vitest'
import { StreamingSha256 } from './sha256.js'

describe('StreamingSha256',()=>{
  it('matches SHA-256 vectors across chunk boundaries',()=>{
    const hash=new StreamingSha256().update(new TextEncoder().encode('a')).update(new TextEncoder().encode('bc')).hex()
    expect(hash).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
})

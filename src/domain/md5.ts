/** MD5 is used solely for Bilibili's public WBI URL signature, never for passwords. */
export function md5(text: string): string {
  const bytes = new TextEncoder().encode(text)
  const size = Math.ceil((bytes.length + 9) / 64) * 64
  const padded = new Uint8Array(size)
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  const bits = bytes.length * 8
  view.setUint32(size - 8, bits >>> 0, true)
  view.setUint32(size - 4, Math.floor(bits / 0x100000000), true)
  const shifts = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21]
  const constants = Array.from({ length: 64 }, (_, index) => Math.floor(Math.abs(Math.sin(index + 1)) * 0x100000000) >>> 0)
  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476
  for (let offset = 0; offset < size; offset += 64) {
    let a = a0, b = b0, c = c0, d = d0
    for (let index = 0; index < 64; index++) {
      let f: number, g: number
      if (index < 16) { f = b & c | ~b & d; g = index }
      else if (index < 32) { f = d & b | ~d & c; g = (5 * index + 1) % 16 }
      else if (index < 48) { f = b ^ c ^ d; g = (3 * index + 5) % 16 }
      else { f = c ^ (b | ~d); g = (7 * index) % 16 }
      const sum = (a + f + constants[index] + view.getUint32(offset + g * 4, true)) | 0
      const shift = shifts[Math.floor(index / 16) * 4 + index % 4]
      const previousD = d
      d = c; c = b; b = (b + (sum << shift | sum >>> (32 - shift))) | 0; a = previousD
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0
  }
  const result = new Uint8Array(16)
  const output = new DataView(result.buffer)
  ;[a0, b0, c0, d0].forEach((word, index) => output.setUint32(index * 4, word, true))
  return [...result].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

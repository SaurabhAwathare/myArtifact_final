/**
 * Counts the number of set bits (cardinality) in a Buffer.
 * Assumes Little-Endian bit ordering within bytes (matching java.util.BitSet).
 */
export function countSetBits(buffer: Buffer): number {
  let count = 0;
  for (let i = 0; i < buffer.length; i++) {
    let byte = buffer[i];
    // Brian Kernighan's Algorithm to count set bits
    while (byte > 0) {
      byte &= (byte - 1);
      count++;
    }
  }
  return count;
}

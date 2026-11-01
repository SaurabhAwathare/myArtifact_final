import { describe, it, expect } from "@jest/globals";
import { validateCoverage, coverageToBase64 } from "../util/validation/coverage";

describe("Coverage Validation", () => {
  const durationMs = 60000; // 60 seconds

  describe("Policy V1 (Legacy Bucketed)", () => {
    // V1 for 60s uses 5000ms segments (since it's < 10 mins and >= 1 min)
    // Wait, let's check policy.ts:
    // if (durationMs < 60000) return 500; // < 1 min
    // if (durationMs < 600000) return 5000; // < 10 mins
    // For 60000 exactly, it returns 5000. 60000 / 5000 = 12 segments.

    it("should calculate correct coverage for V1", () => {
      // 12 segments. 11 bits set = 11/12 = 91.6% (Fail)
      // 12 segments. 12 bits set = 12/12 = 100% (Pass)

      const buffer = Buffer.alloc(2); // 16 bits capacity
      buffer[0] = 0xFF; // 8 bits
      buffer[1] = 0x0F; // 4 bits. Total 12 bits.

      const result = validateCoverage(durationMs, buffer, true, 1);
      expect(result.totalSegments).toBe(12);
      expect(result.cardinality).toBe(12);
      expect(result.coveragePercent).toBe(1.0);
      expect(result.isValid).toBe(true);
    });

    it("should fail if coverage is below 95%", () => {
      const buffer = Buffer.alloc(2);
      buffer[0] = 0xFF; // 8 bits
      buffer[1] = 0x03; // 2 bits. Total 10 bits. 10/12 = 83.3%

      const result = validateCoverage(durationMs, buffer, true, 1);
      expect(result.isValid).toBe(false);
    });
  });

  describe("Policy V2 (Fixed 1s)", () => {
    // V2 uses 1000ms segments regardless of duration.
    // 60000 / 1000 = 60 segments.

    it("should calculate correct coverage for V2", () => {
      // 60 segments. 57 bits set = 57/60 = 95% (Pass)

      const buffer = Buffer.alloc(8); // 64 bits capacity
      for (let i = 0; i < 7; i++) buffer[i] = 0xFF; // 56 bits
      buffer[7] = 0x01; // 1 bit. Total 57 bits.

      const result = validateCoverage(durationMs, buffer, true, 2);
      expect(result.totalSegments).toBe(60);
      expect(result.cardinality).toBe(57);
      expect(result.coveragePercent).toBe(0.95);
      expect(result.isValid).toBe(true);
    });

    it("should fail if coverage is below 95% for V2", () => {
      const buffer = Buffer.alloc(8);
      for (let i = 0; i < 6; i++) buffer[i] = 0xFF; // 48 bits
      buffer[6] = 0x03; // 2 bits. Total 50 bits. 50/60 = 83.3%

      const result = validateCoverage(durationMs, buffer, true, 2);
      expect(result.isValid).toBe(false);
    });
  });

  describe("Backward Compatibility", () => {
    it("should default to V1 if version is missing", () => {
      const buffer = Buffer.alloc(2);
      buffer[0] = 0xFF;
      buffer[1] = 0x0F;

      const result = validateCoverage(durationMs, buffer, true);
      expect(result.totalSegments).toBe(12); // V1 behavior
    });
  });

  describe("coverageToBase64 Serialization Helper", () => {
    it("1. should convert a Node.js Buffer to the correct Base64 string", () => {
      const buffer = Buffer.from([0xDE, 0xAD, 0xBE, 0xEF]);
      const result = coverageToBase64(buffer);
      expect(result).toBe("3q2+7w==");
    });

    it("2. should ensure the Base64 result decodes to the exact original bytes", () => {
      const originalBytes = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
      const buffer = Buffer.from(originalBytes);
      const base64 = coverageToBase64(buffer);
      const decodedBuffer = Buffer.from(base64, "base64");
      expect(decodedBuffer.equals(buffer)).toBe(true);
      expect(Array.from(decodedBuffer)).toEqual(originalBytes);
    });

    it("3. should handle an empty Buffer correctly", () => {
      const emptyBuffer = Buffer.alloc(0);
      const result = coverageToBase64(emptyBuffer);
      expect(result).toBe("");
    });

    it("4. should handle Uint8Array correctly if supported", () => {
      const uint8Array = new Uint8Array([0xDE, 0xAD, 0xBE, 0xEF]);
      const result = coverageToBase64(uint8Array);
      expect(result).toBe("3q2+7w==");
    });

    it("5. should return empty string for null and undefined", () => {
      expect(coverageToBase64(null)).toBe("");
      expect(coverageToBase64(undefined)).toBe("");
    });

    it("6. should handle Firestore Blob/Bytes objects with verified toBase64 or toBuffer methods", () => {
      const fakeBytesWithToBase64 = {
        toBase64: () => "3q2+7w==",
      };
      expect(coverageToBase64(fakeBytesWithToBase64)).toBe("3q2+7w==");

      const fakeBytesWithToBuffer = {
        toBuffer: () => Buffer.from([0xDE, 0xAD, 0xBE, 0xEF]),
      };
      expect(coverageToBase64(fakeBytesWithToBuffer)).toBe("3q2+7w==");
    });

    it("7. should throw TypeError for unsupported values rather than returning empty string", () => {
      expect(() => coverageToBase64(12345)).toThrow(TypeError);
      expect(() => coverageToBase64("raw_string")).toThrow(TypeError);
      expect(() => coverageToBase64({ unknownProperty: "abc" })).toThrow(TypeError);
      expect(() => coverageToBase64(true)).toThrow(TypeError);
      expect(() => coverageToBase64([1, 2, 3])).toThrow(TypeError);
    });

    it("8. should detect genuinely different coverage bytes in before/after comparison", () => {
      const beforeBuffer = Buffer.from([0x01, 0x02]);
      const afterBuffer = Buffer.from([0x01, 0x03]);
      const beforeBase64 = coverageToBase64(beforeBuffer);
      const afterBase64 = coverageToBase64(afterBuffer);
      expect(beforeBase64 !== afterBase64).toBe(true);
    });

    it("9. should ensure identical coverage bytes remain identical after conversion", () => {
      const buf1 = Buffer.from([0xFF, 0x00, 0xAA, 0x55]);
      const buf2 = Buffer.from([0xFF, 0x00, 0xAA, 0x55]);
      const base64_1 = coverageToBase64(buf1);
      const base64_2 = coverageToBase64(buf2);
      expect(base64_1).toBe(base64_2);
      expect(base64_1 === base64_2).toBe(true);
    });

    it("10. Regression test: Node.js Firebase Admin SDK returns Firestore bytes as Buffer", () => {
      // Firebase Admin SDK returns Firestore bytes as Node Buffer instances.
      // Calling .toBase64() directly on a Node Buffer caused `TypeError: before.coverage.toBase64 is not a function`.
      const adminSdkFirestoreBytesBuffer = Buffer.from([0xFF, 0x0F]); // 12 bits set

      // Verify Buffer does NOT have toBase64 method
      expect((adminSdkFirestoreBytesBuffer as any).toBase64).toBeUndefined();

      // Verify coverageToBase64 converts it cleanly without throwing TypeError
      const serialized = coverageToBase64(adminSdkFirestoreBytesBuffer);
      expect(serialized).toBe("/w8=");

      // Verify idempotency evaluation when before and after documents have equal or different Admin SDK Buffers
      const beforeData = { coverage: adminSdkFirestoreBytesBuffer };
      const afterDataIdentical = { coverage: Buffer.from([0xFF, 0x0F]) };
      const afterDataModified = { coverage: Buffer.from([0xFF, 0x1F]) };

      const beforeBase64 = coverageToBase64(beforeData.coverage);
      const afterIdenticalBase64 = coverageToBase64(afterDataIdentical.coverage);
      const afterModifiedBase64 = coverageToBase64(afterDataModified.coverage);

      // Identical coverage -> no change detected
      expect(beforeBase64 !== afterIdenticalBase64).toBe(false);

      // Modified coverage -> change detected
      expect(beforeBase64 !== afterModifiedBase64).toBe(true);
    });
  });
});

import { describe, it, expect } from "@jest/globals";
import { validateCoverage } from "../util/validation/coverage";

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
});

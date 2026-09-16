# Offline Name Corpus Integration for IdentityScout — Revised Implementation Plan

## Revised Implementation Goal
Enhance `IdentityScout` with a lightweight, offline, pre-compiled given-name and surname dictionary (name corpus) to detect real-world personal name leaks in user handles and public display names, even when the user has not supplied a real name or profile email.

The system preserves strict user anonymity and privacy without introducing any cloud, network, or third-party runtime dependencies, while avoiding false positives on common English words and preserving existing UI and generator behaviors.

---

## 1. Concrete Corpus Source & Licensing Strategy

### Legal & Licensing Framework
- **Strict Anti-Violation Rule**: The project will **NOT** copy, dump, or bundle proprietary, copyrighted, or restricted third-party datasets into the application codebase or APK assets.
- **Dual-Source Strategy**:
  1. **Primary Project Seed List (`scripts/seed_names.txt`)**:
     A hand-curated list maintained directly in the source repository under the project's own repository license (MIT/Apache/CC0). This explicitly contains regional names, Indian given names/surnames (including "Saurabh", "Awathare"), and common global names.
  2. **Public Domain Open Government Data**:
     Offline compilation from US Social Security Administration (SSA) public domain baby name records (top 2,000 entries) and UK Office for National Statistics (ONS) public domain datasets (Crown Copyright / Open Government Licence v3.0 compliant) processed strictly offline during build time.

### Solo Developer Governance & Maintainability
- **Offline Build Pipeline**:
  An offline Python script (`scripts/build_name_corpus.py`) reads `seed_names.txt` and public domain sources, normalizes, deduplicates, and compiles the result into a clean asset file (`app/src/main/assets/identity/name_corpus.txt`).
- **No Cloud or Network Dependency**:
  100% offline. No Firebase calls, no Gemini API, no Remote Config, and zero runtime network reads. Embedded directly inside the APK assets.
- **Lightweight Footprint**:
  The compiled corpus is capped at ~5,000 to 8,000 high-frequency tokens (~40 KB - 70 KB uncompressed text asset, <20 KB compressed in APK).

---

## 2. Dataset Format and Generation Process

### Dataset Storage
- **File Location**: `app/src/main/assets/identity/name_corpus.txt`
- **Format**: Plain text, UTF-8 encoded, newline-delimited, ASCII-normalized lowercase words sorted alphabetically.
- **Example Asset Contents**:
  ```text
  awathare
  kumar
  patel
  saurabh
  sharma
  smith
  ```

### Generation Process (`scripts/build_name_corpus.py`)
1. Read input entries from `scripts/seed_names.txt` and open datasets.
2. Apply Unicode NFD decomposition to convert accented characters to base ASCII equivalents (e.g., "José" -> "jose", "Müller" -> "muller").
3. Trim surrounding whitespace and convert all characters to lowercase (`Locale.ROOT`).
4. Filter out short tokens (<3 characters).
5. Remove common English atmospheric or dictionary words (e.g., "rose", "amber", "dawn", "king", "may", "grace", "hope", "faith") from the *blocking* dictionary to prevent false positives.
6. Deduplicate and sort tokens alphabetically.
7. Output compiled list to `app/src/main/assets/identity/name_corpus.txt`.

---

## 3. Revised Architecture

### Architectural Principles
- **Primary Integration Point**: `IdentityScout.kt` (Singleton).
- **Corpus Repository / Asset Loader**: `NameCorpusRepository.kt` loads `assets/identity/name_corpus.txt` into an in-memory `Set<String>` (`HashSet`).
- **Lazy Initialization**: Corpus loading is executed lazily on a background thread (`Dispatchers.IO`) when `IdentityScout` is first accessed or pre-warmed during app startup.
- **Preserved UI & ViewModel Contracts**: `IdentityViewModel`, `UsernameInput.kt`, and `ModerationWarningCard.kt` continue consuming `UsernameValidationResult` and `ModerationWarning` without any structural or breaking changes.

### Architectural Diagram
```
+-------------------------------------------------------------------+
|                        UsernameInput UI                           |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                        UsernameValidator                          |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                           IdentityScout                           |
|  1. Check User-Provided Real Name / Email                         |
|  2. Check Token Match against NameCorpusRepository (Set<String>)  |
|  3. Check Dual-Meaning / Common Words Exclusions                  |
|  4. Check Email / Phone / Behavioral / Handle Patterns            |
+-------------------------------------------------------------------+
                                  |
                                  v
+-------------------------------------------------------------------+
|                       NameCorpusRepository                        |
|  Loads app/src/main/assets/identity/name_corpus.txt (Set<String>) |
+-------------------------------------------------------------------+
```

---

## 4. Detection Algorithm

### Input Normalization & Tokenization
1. Lowercase input string (`target`).
2. Remove diacritics/accents via ASCII folding.
3. Split input into discrete tokens using delimiters: whitespace, punctuation, hyphens, underscores, dots, and digits (`Regex("[\\s,._\\-\\d]+")`).
4. Split camelCase / PascalCase boundaries (e.g., `SaurabhAwathare` -> `saurabh`, `awathare`).
5. Retain tokens with length >= 3.

### Matching Rules
- **Exact-Token Matching ONLY**:
  Compare each candidate token directly against `nameCorpusSet.contains(token)`.
  **CRITICAL**: Substring matching (e.g., checking if `target.contains("art")` inside "artist") is **STRICTLY FORBIDDEN** to eliminate false positives.
- **Exclusion Check**:
  If token is present in `IGNORED_ROLE_TOKENS` ("artifact", "creator", "admin", "system", etc.) or `DUAL_MEANING_WORDS` ("rose", "amber", "king", "may", etc.), skip blocking flags.

---

## 5. Blocking vs Advisory Rules

### BLOCKING Warnings (`isBlocking = true`, `ValidationReason.REAL_NAME`)
Triggered when:
1. Target token explicitly matches the user's provided real name (`realName.toUnsecureString()`).
2. Target token explicitly matches an entry in `nameCorpusSet` AND is NOT in `IGNORED_ROLE_TOKENS` or `DUAL_MEANING_WORDS`.

*User Message*: `"This looks like a real name. For your privacy and safety, consider a pseudonymous choice."`

### ADVISORY Warnings (`isBlocking = false`, `ValidationReason.MOTIF_REUSE` / `POTENTIALLY_IDENTIFYING`)
Triggered when:
1. Phonetic similarity (Metaphone or Levenshtein distance <= 2) is detected between a target token and a corpus name or user's real name.
2. Exact match on a dual-meaning atmospheric word that doubles as a common name (e.g., "Amber", "Rose") when combined with other suspicious patterns.
3. Behavioral patterns ("i'm alex", "dm me on ig", handles starting with `real_` or `official_`).

*User Message*: `"This presence feels familiar to a real name. Consider something more distinct to stay anonymous."`

---

## 6. False-Positive Strategy

### Dual-Meaning / Common-Word Protection
A static set `DUAL_MEANING_WORDS` will be maintained in `IdentityScout`:
```kotlin
private val DUAL_MEANING_WORDS = setOf(
    "amber", "rose", "dawn", "king", "may", "grace", "hope",
    "faith", "summer", "autumn", "violet", "sky", "river",
    "stone", "star", "joy", "reed", "cliff", "glen", "dale"
)
```
- **Rule**: If a token matches a `DUAL_MEANING_WORDS` entry, it will **NOT** trigger a BLOCKING real-name leak error on its own.
- Standalone atmospheric names like `"Silent Amber"` or `"Ancient Rose"` remain valid pseudonyms and are allowed.

### Application Role Exclusions
Existing `IGNORED_ROLE_TOKENS` ("artifact", "creator", "user", "admin", "anonymous", "guest", "default", "test", "persona", "system") are preserved to avoid flagging system terms.

### Pseudonym Generator Protection
`UsernameGenerator.kt` generates pseudonyms using curated atmospheric adjectives and nouns. Its output tokens are filtered to ensure generated suggestions never trigger corpus matches or echo user input.

---

## 7. Performance Requirements

### Corrected Performance Claims
- **Removal of Unverified Claims**:
  Removed all claims of "<1 ms validation runtime" and "zero latency impact on first launch".
- **Acknowledged Realities**:
  Cold initialization requires an Android Asset Manager read and `HashSet` memory allocation.

### Practical & Measurable Performance Requirements
1. **Lazy Background Initialization**:
   `NameCorpusRepository` loads the text asset on `Dispatchers.IO` when initialized. Asset loading does not run on the Main UI thread and does not block app cold start.
2. **Validation Frame Budget**:
   Once loaded into memory, token set lookup operates in $O(1)$ time per token ($O(K)$ for $K$ tokens in a handle). Total validation pipeline execution time must remain under **16 ms** (1 frame budget at 60 FPS) for standard username inputs (3-30 characters).
3. **Memory Footprint**:
   In-memory `HashSet<String>` containing ~5,000-8,000 tokens consumes < 1 MB of Android JVM heap memory.
4. **Measurable Verification Target**:
   Validation runtime and initial corpus load performance will be explicitly measured and verified in a focused unit/benchmark test (`IdentityScoutPerformanceTest`) using `System.nanoTime()`.

---

## 8. Focused Test Plan

### Unit Test Suites (`IdentityScoutTest.kt` & `NameCorpusLoaderTest.kt`)

#### 1. Corpus Detection Tests ("Saurabh" & "Awathare")
- `detect corpus name saurabh in username is blocking` (e.g., `"saurabh_98"` -> `REAL_NAME`, `isBlocking = true`).
- `detect corpus surname awathare in username is blocking` (e.g., `"awathare_x"` -> `REAL_NAME`, `isBlocking = true`).
- `detect camelCase corpus name is blocking` (e.g., `"SaurabhAwathare"` -> `REAL_NAME`, `isBlocking = true`).
- `detect hyphenated or dotted corpus name is blocking` (e.g., `"saurabh.awathare"` -> `REAL_NAME`, `isBlocking = true`).

#### 2. Corpus Loader & Normalization Tests
- Test parsing of newline-delimited corpus asset file.
- Test ASCII folding (e.g., `"josé"` normalized to `"jose"`).
- Test duplicate line removal and empty line handling.

#### 3. Exact-Token Boundaries & Substring Protection
- Verify naive substrings do NOT trigger false positives (e.g., `"artist"` containing `"art"` is NOT flagged; `"keyboard"` containing `"key"` is NOT flagged).
- Test delimiter splitting (`_`, `.`, `-`, spaces, digits).

#### 4. False-Positive Protection
- Test dual-meaning words (`"Amber"`, `"Rose"`, `"King"`) do NOT trigger blocking real-name errors when used as anonymous handles (`"Amber Lantern"` -> valid, 0 blocking warnings).
- Test role words (`"Creator"`, `"Artifact"`) do NOT trigger real-name warnings.

#### 5. Preservation of Existing IdentityScout Tests
- Verify user-supplied `realName` and `email` leak detection remains 100% intact.
- Verify phone number pattern detection (7+ digits) remains intact.
- Verify behavioral patterns (`"i'm alex"`, `"dm me on ig"`, `real_`) remain advisory.

#### 6. Performance Benchmark Test
- `benchmark corpus load and token validation latency`:
  Measures time taken to load corpus asset and executes 100 consecutive `detectLeaks` calls, asserting timing targets using `System.nanoTime()`.

---

## 9. Risks and Unknowns

| Risk / Unknown | Impact | Mitigation Strategy |
| :--- | :--- | :--- |
| **Asset Size Inflation** | Large corpus increases APK size and RAM usage | Cap compiled corpus at 5,000-8,000 high-frequency tokens (~50 KB text file). |
| **Over-blocking Dictionary Words** | Valid pseudonyms blocked due to common given names | Maintain `DUAL_MEANING_WORDS` exclusion set and run build script dictionary filter. |
| **Cold Start Stutter on Asset Read** | Reading asset on Main thread causes dropped frames | Load corpus lazily via `Dispatchers.IO` in `NameCorpusRepository`. |

---

## 10. Exact Implementation Sequence

```
1. Create Build Pipeline & Seed Corpus
   ├── Create scripts/seed_names.txt (including "saurabh", "awathare", global names)
   └── Create scripts/build_name_corpus.py script to output app/src/main/assets/identity/name_corpus.txt

2. Build Corpus Asset
   └── Execute build_name_corpus.py -> Generates app/src/main/assets/identity/name_corpus.txt

3. Create NameCorpusRepository
   └── Implement NameCorpusRepository in com.saurabh.artifact.repository (or domain)
       Loads assets/identity/name_corpus.txt lazily on Dispatchers.IO into Set<String>

4. Integrate into IdentityScout
   ├── Inject NameCorpusRepository into IdentityScout
   ├── Add tokenization logic (delimiter splitting + camelCase splitting)
   ├── Add DUAL_MEANING_WORDS and exact token matching logic against nameCorpusSet
   └── Assign ValidationReason.REAL_NAME (isBlocking = true) for corpus matches

5. Comprehensive Unit Testing
   ├── Update IdentityScoutTest.kt with tests for "Saurabh", "Awathare", delimiters, camelCase
   ├── Add tests for dual-meaning false-positive protection ("Amber", "Rose")
   ├── Add NameCorpusLoaderTest.kt for asset loading & normalization
   └── Add IdentityScoutPerformanceTest.kt for nanoTime benchmark verification
```

---

## 11. Revised Definition of Done

- [ ] `scripts/seed_names.txt` and `scripts/build_name_corpus.py` created and documented.
- [ ] `app/src/main/assets/identity/name_corpus.txt` generated and bundled into app module assets.
- [ ] `NameCorpusRepository` implemented with thread-safe, lazy loading on `Dispatchers.IO`.
- [ ] `IdentityScout` updated with camelCase/delimiter tokenization, corpus set lookup, and dual-meaning word protections.
- [ ] Unit tests pass proving `"Saurabh"` and `"Awathare"` are blocked when present in corpus.
- [ ] Unit tests pass proving dual-meaning words (`"Amber"`, `"Rose"`) do not produce blocking errors.
- [ ] Existing `IdentityScout` tests remain 100% green.
- [ ] Benchmark test confirms loading occurs lazily off main thread and token lookup runs within frame budget (<16ms).
- [ ] 100% offline, privacy-preserving solution with zero cloud/network dependencies.

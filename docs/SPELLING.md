# Offline English spelling assistance

Status: implemented as a **suggestion-first, optional text-assistance layer**. It never changes the OCR engine, the OCR result or the raw transcript. Everything
marked **VERIFIED** was executed and inspected (commands and counts are in `HANDOFF.md`); **PENDING** was not. "Verified on Linux" means exactly that.

## 1. What the user gets

After OCR extraction (and whenever the transcript text changes), the editable transcript is checked in the background. Suspected misspellings are listed in a panel
under the transcript, each with its surrounding context. Choosing one selects that exact occurrence in the transcript and shows ranked suggestions. The user can:
**Replace** with a suggestion, **Replace with custom** text, **Ignore Once**, **Ignore All**, **Add to Dictionary**, **Undo/Redo** (the editor's own history), recheck,
switch the English variant (US, UK, Canada), switch the feature off, and open the personal dictionary list to remove words again.
Nothing is ever replaced without a click. **Automatic replacement is NOT implemented** (section 9).

Flow (unchanged outside the dotted box):

```
Image -> OCR engine -> immutable raw OCR text -> editable/reviewed transcript -> [ spelling assistance: check -> issues -> user review ] -> save / export
```

## 2. Existing implementation found, and where this attaches

No spell checker existed (the word "dictionary" in the code is the OCR recognizer's `character_dict`). The transcript editor is a JavaFX `TextArea`; its text listener writes
`PageData.editedText` and marks the project modified; raw OCR text is a separate immutable `PageData.rawText`; `TextArea` already provides undo/redo (verified empirically:
`replaceText` is undoable, each replacement individually, and `setText` on a page switch clears the history); `ProjectSession.epoch()` identifies the project; `DirtyTracker`
tracks unsaved changes; page isolation relies on the active page id. The panel was added next to the editor (the editor was **not** replaced). The project file format is unchanged.

## 3. Backend decision (and the Microsoft question)

| Candidate | Verdict |
|---|---|
| **Windows Spell Checking API** (`ISpellCheckerFactory` / `ISpellChecker`) | **Not adopted.** It is a COM interface for Windows 8+ desktop/UWP apps ([ISpellCheckerFactory](https://learn.microsoft.com/en-us/windows/win32/api/spellcheck/nn-spellcheck-ispellcheckerfactory)); which languages exist is whatever spell checkers are registered on that machine (`get_SupportedLanguages`, `IsSupported`; BCP 47 tags such as `en-US`), and `ISpellChecker2` needs Windows 10. A Java application would need *some* Windows-only way to call COM; the routes (JNI, a JNA-style library, a small native helper process, or the Foreign Function & Memory API, which is a preview feature in JDK 21 ([JEP 442](https://openjdk.org/jeps/442)) and final in JDK 22 ([JEP 454](https://openjdk.org/jeps/454))) were **not evaluated or built**, so no particular binding is claimed to be required or best. Any of them adds a Windows-only native component. It cannot run on Linux or Android, so it would give different results per platform and could not be tested here. No Microsoft/Office/Windows dictionary files are copied anywhere. |
| Hunspell (via a Java port or native bindings) | Not adopted: needs an affix engine or native libraries per platform; more machinery than this task justifies. |
| LanguageTool / grammar checkers | Not adopted: heavyweight, and grammar rewriting is explicitly out of scope. |
| SymSpell-style frequency dictionaries | Not adopted: needs a word-frequency corpus with its own provenance; the brute-force bounded edit-distance search is fast enough (section 8). |
| **Bundled plain SCOWL word lists + a small pure-Java checker** | **Chosen.** One consistent offline backend for Linux, Windows and (by design) Android: pure Java 17, no dependency, no native code, no network. |

The interface (`notebookai.spelling.SpellBackend`) is platform-neutral and returns **information only**, so a future Windows adapter (or an Android system spell checker) can sit
behind it unchanged. **Nothing in this build is Microsoft-backed or Microsoft-equivalent**, and nothing claims to be. If a Windows adapter is ever added it must be Windows-only
and isolated (native binding, language availability via `get_SupportedLanguages`, COM apartment/lifecycle, tests on a Windows runner); it is deliberately not part of this milestone.

## 4. Dictionaries, variants, provenance, licensing

* **Data**: SCOWL (Spell Checker Oriented Word Lists) **2020.12.07**, plain expanded lists taken **byte for byte** from the Debian packages `wamerican`, `wbritish`, `wcanadian`
  and their `-small` variants, version `2020.12.07-4` (about 104k entries per variant, UTF-8, NFC; includes proper nouns, possessives, contractions, accented words). The `-small` list
  (about 51k) is used only as a **commonness tier** when ranking suggestions. Exact files, package SHA-256 values and re-derivation steps: `spelling/src/main/resources/notebookai/spelling/dict/PROVENANCE.txt`;
  verify with `sh tools/verify-spelling-dictionaries.sh [DIR with the six .deb files]` (VERIFIED: all six lists and the notice are byte-identical to the packages).
* **Variants offered**: en-US, en-GB (as shipped by `wbritish`), en-CA. Only these, because only these have a verified bundled list. **en-AU is not offered** (no verified plain list was available).
* **Licence**: the SCOWL permission notice plus the notices of the sources it incorporates (public domain lists, WordNet 1.6 / Princeton University, Ispell / Geoff Kuenning, J Ross Beresford) is reproduced
  **verbatim** (the six packages' copyright files are byte-identical, SHA-256 `1d7c0128f6c72d22…`) in `SCOWL-COPYRIGHT.txt`, bundled next to the lists in the jar and copied to `licenses/scowl/` in every distribution and native package
  (required by the distribution validator and `verifyDeb`). The conditions require the notices to accompany copies; they do. **No legal review has been performed.** `packaging/licensing-status.json` has a new item
  `scowl-wordlists` (resolved, with that evidence). `verifyReleaseLicensing` is unchanged and **still fails** for the two existing items (xmpcore notice, model redistribution grant).
* **Transitive dependencies**: none (`./gradlew :spelling:verifySpellingDependencies`). No cloud service, no credentials, no network at runtime.

## 5. Modules and classes

| Where | What |
|---|---|
| `spelling/` (pure Java 17, platform-neutral, Android-bound by design) | `SpellBackend` (check/suggest, no mutation), `SpellLanguage`, `SpellIssue`, `SpellCheckResult`, `IssueKind`, `SpellException`, `WordFilter`, `PersonalWords` (in-memory model and rules), `WordListBackend` (bundled lists, checksum-verified lazy loading), `TextScanner`, `WordList` |
| `notebookai.assist` (desktop app) | `SpellingController` (state machine, UI-independent), `SpellingService` (bounded background worker), `SafeReplace`, `IgnoredOccurrences`, `PersonalDictionaryStore`, `SpellingPreferences`, `UserDataDir` |
| `notebookai.ui.SpellingPanel` (JavaFX) | the panel; adapts JavaFX to the controller |

The OCR engine does **not** depend on spelling (`EngineBoundaryTest`) and spelling does not depend on the engine, the desktop app, AWT, JavaFX, `java.nio.file` or `System.exit` (`SpellingBoundaryTest` scans compiled classes).

## 6. Contract

* **Text snapshot / ranges**: ranges are **UTF-16 code-unit offsets** `[start, end)` into the exact string that was checked, which is what `String` and JavaFX `TextArea` use. The text is never normalized or rewritten;
  lookup normalization (NFC, typographic apostrophes → `'`, case folding) is applied to a copy of the token only. Supplementary characters before or inside the text do not shift ranges (tested).
* **Result**: `SpellCheckResult(language, textLength, issues[], truncated, tokensChecked, tokensSkipped)`; `SpellIssue(start, end, token, kind)`. `IssueKind` is `UNKNOWN_WORD` or `UNKNOWN_CAPITALIZED` ("name?"): **unknown does not mean incorrect**.
* **Suggestions**: `suggest(token, language, max)`; ranked deterministically for a given dictionary: (edit distance ≤ 2, ≤ 1 for tokens up to 3 letters), then adjacent transposition first, first letter unchanged, lower-case candidates for lower-case tokens, common-tier words,
  then alphabetical. **It is a heuristic, not a probability, and not an OCR confidence**; edit distance, rank and word commonness are not calibrated against OCR errors.
* **Limits** (never silent): text longer than 300,000 characters is refused with a message (no partial result); at most 1,000 issues per result (flagged `truncated`).
* **Backend identity**: `identity(language)` e.g. `bundled word list, SCOWL 2020.12.07 en-US (104334 words, 51294 in the common tier)`; shown as a tooltip.

## 7. Tokens: what is checked, what is not

Checked: words made of letters and combining marks, with an apostrophe or hyphen between letters; lower-case, Title-case, UPPER-CASE (4+ letters). Case is handled Hunspell-style: a lower-case token needs the lower-case entry, a Title-case token matches the Title-case
or the lower-case entry, an UPPER-CASE token matches any case. `'s` possessives are accepted when the base word is known. Hyphenated words: the whole compound first, then each part; only the unknown **part** is reported.

Skipped on purpose (counted in `tokensSkipped`): URLs, e-mail addresses, file-system paths (absolute, `~/`, `./`, Windows drive/UNC, `a/b/c`), dotted file/domain/package names made of lower-case segments (`file.txt`, `example.com`; "end.Next" is **not** matched),
code-like text (`snake_case`, `::`, `->`, `()`, braces, `=`, `$var`, `#tag`, tags), equation-like operators between alphanumerics (`a+b`, `x^2`), identifiers containing digits (`mc2`, `3rd`, `abc123`), mixed-case words (`iPhone`, `McDonald`, `O'Brien`),
single letters, ALL-CAPS tokens of up to 3 letters (acronyms), tokens longer than 64 characters, and words in other scripts. These heuristics are conservative and cannot recognise every equation, name or technical term; unknown names are reported as unknown, not wrong.

**A dictionary cannot find valid-word OCR errors**: "form" for "from", "ban" for "bar" are real words and are never flagged (tested: `I come form London` yields no issue). Doubled words ("the the") are grammar, not spelling, and are not reported.

## 8. Concurrency, staleness, replacement

* Checking runs on **one background worker** after a 400 ms pause in typing; the UI thread never waits. At most one request is running and **one pending check and one pending suggestion request exist**; a newer request replaces a pending older one, so rapid edits cannot build a queue (measured: 1,000 rapid submissions → 41 actual checks, max pending 1).
* Every request carries a key: **project epoch, page id, text revision, language, word-list revision**. A result is applied only if the key still equals the current one; results are dropped after edits, page switches, new/open project, language or dictionary changes, disabling, and shutdown. After shutdown nothing is delivered and the backend is closed only after the worker stopped (or never, if it is still running after a 2 s wait).
* Saving and exporting never wait for spelling; OCR busy-state ownership does not depend on it.
* **Replacement** goes through `SafeReplace`: the project/page/revision must match, the range must be inside the text, and the characters in the range must still be exactly the flagged token. Otherwise **nothing is replaced**, the issue is discarded and a fresh check starts. A replacement replaces only that range (surrounding whitespace and punctuation untouched), is applied with `TextArea.replaceText` (so Ctrl+Z/Redo cover it and the app's existing listener marks the page modified), places the caret after the replacement and triggers a recheck.
* Checking and selecting an issue never mark the project modified (verified in the GUI acceptance).
* **Replace All is deferred** (its scope, token matching, offset updates and single-action undo would each need their own tests).

## 9. Automatic replacement: deferred

Suggestion-first is the delivered behavior; there is no setting and no code path that replaces text without a user action. A safe automatic policy cannot be justified from what is measurable here: edit distance, suggestion rank and
corpus commonness are not calibrated OCR confidences; the simulation in section 12 shows top-1 suggestions that are *wrong* ("Nove" → "Node", "leiy" → "levy") as well as right; names, technical words and valid-word OCR errors cannot be separated by absence from a word list.
A future implementation would need an explicit opt-in (default off), no retroactive application to existing reviewed text, no recheck loops, protected categories, a reviewable list of applied replacements that survives save/reopen, and per-replacement revert.

## 10. Personal dictionary, ignore scopes, preferences

* **Location (per user, never the install, bundled resources, source tree or system dictionaries)**: `NOTEBOOK_AI_CONFIG_DIR`, else `-Dnotebookai.config.dir`, else Linux `$XDG_CONFIG_HOME/notebook-ai` or `~/.config/notebook-ai`, Windows `%APPDATA%\notebook-ai`, macOS `~/Library/Application Support/notebook-ai`.
  Files: `personal-dictionary.txt`, `spelling.properties`. They are never part of a project, an export, a distribution or an installer (checked: none present in the ZIP, `.deb` or dist tree).
* **Personal dictionary format** (v1): UTF-8, first line `# notebook-ai personal dictionary v1`, then one word per line (`#` comments allowed). Words only, never transcripts or snippets. Entry rules: NFC, curly apostrophes stored as `'`, 2–64 characters, letters/combining marks/apostrophes/hyphens only, at least two letters,
  no leading/trailing apostrophe or hyphen. Matching: an all-lower-case entry matches any case; a Title-case entry matches itself and its UPPER-CASE form; a mixed-case entry matches exactly. Language scope: **all English variants share one list**. Duplicates are reported and change nothing.
* **Recovery**: invalid lines are skipped and counted (the original is copied to `personal-dictionary.txt.bak` before the next save); an undecodable file gives an empty list plus a notice; a file from a **newer format version** is loaded read-only and never overwritten; writes are atomic (temporary file + rename) and a failed write is reported but **never loses or alters transcript text** (the word still works for the session).
* **Ignore Once**: hides one occurrence (page id + token + ordinal among same-token issues) for this session; not saved; an edit that adds/removes an earlier identical flagged token can shift which later occurrence is hidden. **Ignore All**: ignores the word on every page and project until the application restarts; not saved; "Reset ignored words" clears both. Neither writes the personal dictionary.
* **Preferences**: `enabled` (default ON) and `language` (default `en-US`); damaged files fall back to defaults.

## 11. Privacy and offline behaviour

Everything runs locally. No network access, no cloud service, no credentials, no telemetry. The word lists are inside the application jar; the personal dictionary and preferences stay in the user's profile and are not uploaded or bundled. Transcript text is passed to the in-process checker only and is not retained after a check.

## 12. Measurements (desktop JVM 21 on this machine; **not mobile**)

From `./gradlew spellingPerf` (generated text, fixed seed): first check (loads and verifies en-US) 218 ms; all three dictionaries +51 MB heap (about 17 MB each; one language is loaded by default); short page (275 chars) 1.8 ms;
1,000-word transcript 2.3 ms; 10,000 words (54k chars) 4.7 ms; 50,000 words (269k chars) 11.0 ms; at the 300,000-character limit 11.7 ms (1,000 issues, `truncated`); suggestions mean 8.2 ms, worst 30.9 ms; 2,000 alternating page checks 0.16 ms each with heap 53 → 53 MB and 6 → 6 threads;
missing resources are reported in 0.1 ms.

From `./gradlew spellingAssistEval` (a **simulation** that always accepts the first suggestion; the product never does this; raw OCR numbers are separate and unchanged): committed handwriting sample (13 reference words, 67 characters) raw CER 0.179 / WER 0.385 → simulated 0.164 / 0.308, with 3 replacements: 1 helpful (`COMPLLCATED` → `COMPLICATED`), 0 harmful, 2 neutral and wrong (`Nove` → `Node`, `leiy` → `levy`);
rendered-print fixture 0 flags; private case (aggregate only, not in Git): raw CER 0.055 → simulated 0.022 with 3 replacements, 2 helpful. **One sample per fixture: this is an illustration, not evidence that spelling assistance improves OCR in general, and it says nothing about the OCR model.**

## 13. Platform status

* **Linux**: all automated tests, the GUI acceptance (Xvfb) and packaging checks were executed. **VERIFIED.**
* **Windows**: the spelling module is pure Java and ships in the Windows distribution/native inputs (static input check passes); **nothing was executed on Windows** in this milestone (the installer-smoke workflow was not run). **PENDING.**
* **Android**: the module compiles to Java 17 bytecode, `tools/check-android-api.py` finds no JDK API above Android API 24 across engine + spelling (428 references), D8 builds it into the proof APK. **Not executed on a device: PENDING.** It uses `java.text.Normalizer` and `Character.UnicodeScript` (both API ≤ 24, checked by the same tool). Android persistence of a personal dictionary and the UI are the Android app's job.
* **iOS**: a separate integration (reuse the rules/word lists, not the Java code).

## 14. Tests and commands

```sh
./gradlew test                                   # desktop + engine + spelling unit tests
./gradlew :spelling:build                        # spelling tests + dependency check + boundary scan
xvfb-run -a ./gradlew spellingGuiAcceptance      # real app, real OCR, real backend, isolated user directory (33 checks)
./gradlew spellingPerf                           # bounded timing/memory/coalescing measurements
./gradlew spellingAssistEval -PevalManifest=eval/cases.tsv   # simulated top-1 acceptance vs raw OCR (separate metrics)
sh tools/verify-spelling-dictionaries.sh [DIR]   # word lists vs SHA256SUMS (and vs the Debian packages)
ANDROID_HOME=$HOME/Android/Sdk ./gradlew -p android-proof :app:verifyEngineAndroidApi :app:assembleDebug   # Android API check + dex (build only)
```

## 15. Known limitations

* Dictionary lookup cannot detect real-word errors; names, technical terms and OCR garbage can be flagged or missed; suggestion ranking is weak (no frequency data, no phonetic or OCR-confusion model). Suggestions are computed on demand for the selected issue.
* Variants are limited to US/UK/Canada; British spelling follows the `wbritish` list as shipped (how it treats -ise/-ize alternatives was not examined).
* Heuristic exclusions (section 7) are not exhaustive; "end.Next"-style missing spaces are checked but all-lower-case dotted names are not.
* Ignore Once ordinals can shift when earlier identical flagged tokens are added or removed.
* Replace All and automatic replacement are not implemented.
* No inline underlines (an issue list is used so the editor stays unchanged).
* The personal dictionary is shared by all English variants and is not synchronised between machines.

# Known issues (Phase 0)

1. **No Android SDK in this environment.** `ANDROID_HOME` unset; only a
   Debian stub at `/usr/lib/android-sdk` (no platforms/platform-tools).
   `./gradlew assembleDebug` fails with "SDK location not found" after
   successfully resolving Gradle 9.4.1 + plugins. No APK has been built;
   no device testing performed.
2. **npm 12 `--allow-scripts` vs RN CLI init.** `rnc-cli init` runs a
   project-scoped `npm install --save <template>` which npm 12 rejects.
   Workaround used: `init --skip-install --skip-git-init`, then plain
   `npm install` inside the project (works; no pending script approvals).
   Future contributors on npm 12+ should use the same flags.
3. **JDK mismatch risk (unverified).** Environment Java is 25; React Native
   0.88/AGP expects JDK 17. Even with an SDK installed, the build may need
   `org.gradle.java.home` pointed at JDK 17. Not tested (blocked by #1).
4. **Test renderer counts.** The RN jest mock duplicates `testID` across
   composite + host nodes; UI tests query composite nodes via
   `src/testUtils/queries.ts`. If the preset changes its mocks, counts may
   need revisiting (behavior assertions are unaffected).
5. **Equation preview is a subset.** Only `\frac \sqrt \bar ^ _ \pm \times
   \cdot \leq \geq \neq` plus groups render; anything else shows the local
   error box. That is the specified behavior, listed here so nobody mistakes
   it for full LaTeX.
6. **No persistence.** Edits live for the app session only. Intended for
   Phase 0.

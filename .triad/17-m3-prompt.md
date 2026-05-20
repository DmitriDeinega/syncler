# M3 — Android app shell

You have completed M1 and M2 (server + crypto reference). Now build the Android host application.

Workspace-write granted to `d:\Projects\syncler\`. Touch only `android/`.

## Scope of M3

A new Android project at `android/` with: Compose UI, signup/login flows that talk to the server, secure local storage of the master key (derived locally from password), device enrollment.

This milestone does NOT include the plugin host (that's M4b), push (M5), or pairing UI (M6). It's the foundation: a user can install the app, sign up or log in, and the app holds the master key in memory + persisted-encrypted-at-rest, and registers the device with the server.

## Top-level structure (Gradle multi-module)

```
android/
  build.gradle.kts            # root build
  settings.gradle.kts         # module list
  gradle.properties           # versions
  gradle/
    libs.versions.toml        # version catalog
  app/                         # entry-point module
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/kotlin/...
  core/
    auth/                      # signup, login, JWT session
      build.gradle.kts
      src/main/kotlin/...
    crypto/                    # Argon2id, master key wrap/unwrap (matches docs/crypto-spec.md)
      build.gradle.kts
      src/main/kotlin/...
    network/                   # Retrofit/Ktor client for the FastAPI server
      build.gradle.kts
      src/main/kotlin/...
    storage/                   # EncryptedSharedPreferences + SQLCipher
      build.gradle.kts
      src/main/kotlin/...
  feature/
    inbox/                     # placeholder; full feed in M5+
      build.gradle.kts
    settings/                  # devices + plugins management; partial in M3
      build.gradle.kts
```

## Stack
- Kotlin 2.1.x (latest stable for May 2026)
- Jetpack Compose with the Material 3 design system
- Coroutines + Flow
- DataStore? No — use AndroidX EncryptedSharedPreferences for tokens, SQLCipher / EncryptedDB for structured data
- Network: Retrofit + Moshi (Kotlin codegen) — Ktor is also fine; pick Retrofit for V1
- DI: Hilt
- Crypto: 
  - Argon2id via `at.favre.lib.crypto.bcrypt:bcrypt`? NO. Use `de.svenkubiak:jBCrypt`? NO. Use **`com.lambdapioneer.argon2kt:argon2kt`** for Argon2id JNI bindings (correct lib for Android Argon2)
  - AES-GCM, HKDF-SHA256, Ed25519 verification: AndroidX Security + javax.crypto + BouncyCastle (`org.bouncycastle:bcprov-jdk18on`)
- Target SDK 35, Min SDK 26 (Android 8.0+; covers ~95% of devices)

## Files (high level — Codex fills in details)

### Root
- `android/build.gradle.kts` — plugins block declares Kotlin, Android, Hilt, KSP, etc.
- `android/settings.gradle.kts` — `include(":app", ":core:auth", ":core:crypto", ":core:network", ":core:storage", ":feature:inbox", ":feature:settings")`
- `android/gradle.properties` — `org.gradle.jvmargs=-Xmx4g -XX:+UseG1GC`, `android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`
- `android/gradle/libs.versions.toml` — version catalog with all lib versions pinned
- `android/.gitignore` — Android standard

### app module
- `android/app/build.gradle.kts` — applies android-application, kotlin, hilt, ksp plugins; depends on all other modules
- `android/app/src/main/AndroidManifest.xml` — package name `app.syncler.android`, application class `SynclerApplication`, internet permission, no plugin manifests yet
- `android/app/src/main/kotlin/app/syncler/android/SynclerApplication.kt` — `@HiltAndroidApp class SynclerApplication : Application()`
- `android/app/src/main/kotlin/app/syncler/android/MainActivity.kt` — `@AndroidEntryPoint`, sets Compose content; conditional navigation: if logged in → InboxScreen (stub); else → AuthScreen
- `android/app/src/main/kotlin/app/syncler/android/ui/AuthScreen.kt` — Compose UI with "Sign Up" / "Log In" tabs; email + password inputs; calls `AuthViewModel` which invokes `core:auth` use cases
- `android/app/src/main/kotlin/app/syncler/android/ui/InboxScreenStub.kt` — placeholder "You are logged in" + "Log out" button + "Manage devices" button
- `android/app/src/main/kotlin/app/syncler/android/ui/SettingsDevicesScreen.kt` — list devices via API, show "Revoke" button

### core:crypto
- `KeyDerivation.kt` — Argon2id wrapper matching the docs/crypto-spec.md exact params (m=19456, t=2, p=1). Returns the 64-byte hash split into `(authKey: ByteArray, masterKeyWrapKey: ByteArray)`.
- `MasterKey.kt` — generate random 32-byte master key; wrap/unwrap with master_key_wrap_key via AES-256-GCM; ByteArray utilities
- `Hkdf.kt` — HKDF-SHA256 returning pairing keys (matches the spec for future use)
- `Signing.kt` — Ed25519 sign/verify (Android Keystore for the device private key; ByteArray API for sender public keys)
- `Aead.kt` — AES-256-GCM encrypt/decrypt with the wire format (nonce ‖ ciphertext_with_tag)
- Unit tests in `core/crypto/src/test/kotlin/...` for each: derive_key, master_key roundtrip, hkdf vector, ed25519 sign+verify, aead roundtrip

### core:auth
- `AuthRepository.kt` — coordinates signup/login flows
  - `signup(email, password): Result<SignupResult>` — derive auth_key+wrap_key, generate master_key, wrap it, POST /v1/auth/signup
  - `login(email, password): Result<LoginResult>` — derive auth_key, POST /v1/auth/login, get encrypted_master_key blob, unwrap with password-derived key, store master_key in memory
  - `logout()` — clears master key + token
- `Session.kt` — holds JWT token + master key (in-memory only); exposes Flow for "is logged in" state
- `SessionStore.kt` — EncryptedSharedPreferences persistence for the JWT token (refresh on app restart); master key NOT persisted (re-derived from password on next login)

### core:network
- `SynclerApi.kt` — Retrofit interface
  - `POST /v1/auth/signup` `signup(body: SignupRequest): SignupResponse`
  - `POST /v1/auth/login` `login(body: LoginRequest): LoginResponse`
  - `POST /v1/auth/devices/enroll` `enrollDevice(body: DeviceEnrollRequest): DeviceEnrollResponse`
  - `GET /v1/auth/devices` `listDevices(): List<DeviceItem>`
  - `POST /v1/auth/devices/{id}/revoke` `revokeDevice(@Path("id") id: String)`
  - `DELETE /v1/account` `deleteAccount()`
- `NetworkModule.kt` — Hilt module providing the Retrofit instance with the base URL (from BuildConfig + injectable AuthInterceptor that adds `Authorization: Bearer <token>` when session has one)

### core:storage
- `SecurePrefs.kt` — wraps `androidx.security.crypto.EncryptedSharedPreferences` for storing the JWT token. Master key is NOT persisted; only password-derivable.
- `LocalDb.kt` — placeholder SQLCipher Room database; M3 has no tables yet but the wiring is in place

### feature:inbox / feature:settings
- Stub modules with a single `Screen.kt` each that the app navigates to. Full functionality in later milestones.

## Constraints
- Kotlin code is idiomatic: `val` over `var`, expressions over statements, sealed interfaces for results
- All async via coroutines + Flow; no LiveData
- Use the version catalog for all versions; no hardcoded version numbers in module build files
- BuildConfig field `SERVER_BASE_URL` (default to `http://10.0.2.2:8000/` for emulator → host server)
- No tests required for the app module yet (UI tests come in M11); unit tests required for core:crypto, core:auth, core:network
- Use Hilt for DI
- Targeting Android 8+ — no use of Android 11+ exclusive APIs unless gated by `Build.VERSION.SDK_INT`

## Print summary
- Top-level Gradle structure
- Argon2 lib chosen + version
- Any places where the spec was tightened or loosened with reason
- The exact `./gradlew` command to build + run unit tests

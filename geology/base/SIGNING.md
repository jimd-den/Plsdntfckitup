# Signing

Every release build is signed. An unsigned APK cannot be installed on any
device, so producing one is the same as producing nothing.

There are two keys, and the build picks whichever is available.

## 1. The CI test key — `ci-signing.jks`

Committed to this repository. Its password is published below and in
`app/build.gradle.kts`, deliberately: the key's only job is to make continuous
integration artifacts installable, and treating it as a secret would imply it
protects something.

| | |
| --- | --- |
| File | `ci-signing.jks` (PKCS12) |
| Alias | `stratum-ci` |
| Store and key password | `stratum-ci` |
| Certificate | `CN=Stratum CI Test Key, OU=Continuous Integration, O=Stratum, C=US` |
| SHA-256 | `d6:1a:01:44:01:7e:89:c9:c2:d2:31:67:52:2c:34:40:46:92:a4:8f:47:89:94:7a:41:d0:72:29:03:bd:0b:67` |
| Valid until | 2054-02-01 |

This is the same bargain as Android's shared `debug.keystore`, which also ships
with a known password. Because the key is stable, build N+1 upgrades build N in
place instead of forcing an uninstall first.

**Never publish with this key.** Anyone who clones the repository can sign an
APK with it, so it proves nothing about who built the artifact.

## 2. A real upload key

Used automatically whenever CI finds the secret. To set it up:

```sh
# Generate the key. Keep the file and the passwords somewhere you will not
# lose them: a Play upload key cannot be replaced without Google's help, and
# without Play App Signing it cannot be replaced at all.
keytool -genkeypair \
  -keystore upload.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, O=Your Org, C=US"

# Encode it for GitHub.
base64 -w0 upload.jks > upload.jks.base64
```

Then add four repository secrets under **Settings → Secrets and variables →
Actions**:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | contents of `upload.jks.base64` |
| `RELEASE_STORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | `upload` |
| `RELEASE_KEY_PASSWORD` | the key password |

Nothing else changes. The next build signs with the upload key, and the
artifact is named `…-uploadsigned` instead of `…-cisigned`.

Do not commit `upload.jks` or the base64 file — `.gitignore` already refuses
every `*.jks` except the CI key.

## How the build decides

`app/build.gradle.kts` prefers the `KEYSTORE_PATH` environment variable, which
CI sets only after decoding the secret, and falls back to `ci-signing.jks`. If
neither exists the build fails with a message saying so, rather than quietly
emitting an unsigned APK.

```sh
./gradlew :app:assembleRelease reportSigningKey
```

`reportSigningKey` prints which key was used. CI additionally runs `apksigner
verify` on the output and fails if the APK is unsigned or if the filename still
carries the `-unsigned` suffix — the artifact is proven installable rather than
assumed to be.

## Where to get a build

- **Any pull request** → the run's artifacts, `stratum-pr<N>-<sha>-cisigned`.
- **In the repository** → `apk/stratum-release.apk`, refreshed by
  `./gradlew :app:assembleRelease`.

## Switching to Play later

Turn on Play App Signing at first upload. Google then holds the app signing key
and your upload key becomes replaceable, which removes the one genuinely
unrecoverable failure in this setup. `applicationId` is currently
`com.aistudio.igboarpg.omagvd` and can never change after the first upload, so
settle it before then.

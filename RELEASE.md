# Releasing RP Launcher

Every push builds three things on GitHub Actions:

- **Latest test build** (`rp-launcher-test.apk`): the debug build for trying things out.
- **Release build**: shrunk, one APK per phone type plus a bundle for Google Play. Unsigned
  until the release key below is added; then it's published as **Latest release build**.
- **F-Droid check** (run by hand from the Actions tab): builds the app the way F-Droid will,
  with ONNX Runtime compiled from source, and publishes it as **F-Droid style test build**.

Before calling any version 1.0, the spec asks for testing with at least five people who
have RP, at different stages, in a dark room and in bright sun.

## 1. The release key (once)

The release key proves updates come from you. It never goes into the code; GitHub keeps it as
a secret. On a computer with Java installed:

```
keytool -genkeypair -v -keystore rp-launcher-release.jks -alias rp-launcher \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 rp-launcher-release.jks > key.txt      # on a Mac: base64 -i rp-launcher-release.jks -o key.txt
```

Then in GitHub: **Settings → Secrets and variables → Actions → New repository secret**, four times:

| Name | Value |
|---|---|
| `RP_KEYSTORE_BASE64` | the contents of key.txt |
| `RP_KEYSTORE_PASSWORD` | the keystore password you chose |
| `RP_KEY_ALIAS` | `rp-launcher` |
| `RP_KEY_PASSWORD` | the key password (often the same as the keystore password) |

**Back up `rp-launcher-release.jks` and the passwords somewhere safe**, such as a password
manager. On Google Play it's the "upload key", which Google can reset if it's lost. For
direct downloads it can't be replaced: a new key means people must uninstall and reinstall.

## 2. Google Play

1. Create the app in the Play Console (a one-time $25 developer account). Turn on **Play App
   Signing** (the default).
2. Upload the `.aab` from **Latest release build** to internal testing first.
3. **Store listing:** copy from `fastlane/metadata/android/en-US/` (and `es-ES/`). Add
   screenshots from the phone, and post `PRIVACY.md` on netgician.com for the privacy policy link.
4. **App content** answers:
   - *Ads:* no.
   - *Data safety:* no data shared. Data collected: **Location (precise)**, processed
     ephemerally, not shared, optional, for app functionality (the street address for Where
     am I and SOS uses the phone's address lookup). Everything else stays on the device.
     Texts, photos and voice messages are sent only when the person chooses to send them.
   - *SMS and Call Log permissions declaration:* core functionality **Default SMS handler**
     and **Default Phone handler**. The texting and phone features only work after the
     person makes RP Launcher their texting or phone app in Setup. Play usually asks for a
     short video showing that.
   - *Full-screen intent:* **calling app** (the incoming-call screen).
   - *Location:* foreground only, for SOS and Where am I.
   - *Accessibility:* RP Launcher doesn't use an accessibility service.
   - *Target audience:* adults; not designed for children.
5. Since 2026, Google also requires **developer verification** (identity check) for apps
   installed on certified Android phones, including outside Play. Verifying as Netgician LLC
   in the Play Console covers it.

## 3. F-Droid

1. The GitHub repository is public, which F-Droid needs. ✓
2. Tag the release: `git tag v0.5.0 && git push --tags`.
3. Run **F-Droid check** in the Actions tab and make sure it passes. Then install the
   **F-Droid style test build** and check that Read text in See still works.
4. With a free GitLab account: fork https://gitlab.com/fdroid/fdroiddata, add
   `fdroid/com.netgician.rplauncher.yml` as `metadata/com.netgician.rplauncher.yml`, and open
   a merge request titled "New app: RP Launcher". F-Droid reads the descriptions from
   `fastlane/metadata`. Their reviewers usually reply within a few weeks.

**About the test key:** `app/debug.keystore` is Android's standard test key, now public with
the repository. Anyone could sign an app with it that installs over a test build, so only
install test builds from this repository's own Releases page. Release builds use your own key.

F-Droid signs its own builds with its own key, so its version and the Play version can't
update each other. People should pick one source and stay with it.

## Version numbers

`app/build.gradle.kts` holds `versionCode` (goes up by one every release) and `versionName`.
Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for each release.

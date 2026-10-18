# Merge request text for fdroiddata

Title: **New app: RP Launcher**

Paste the text below into the merge request description (it replaces F-Droid's template, and
keeps its checklist).

---

RP Launcher is a free (GPL-3.0) Android home screen for people with retinitis pigmentosa
(tunnel vision). Every tile, label and alert fits inside the part of the screen the person can
see, on a dark, glare-free background, and everything can be read aloud. It can also be the
phone's texting and phone app, with big screens for each, and it reads printed text from the
camera on the phone itself.

* Source: https://github.com/netgician/rp-launcher
* License: GPL-3.0-only (bundled AOSP MMS code: Apache-2.0; PP-OCR models: Apache-2.0;
  Atkinson Hyperlegible font: OFL-1.1)
* No internet permission, no ads, no tracking, no proprietary libraries.
* Store text, screenshots and changelogs in English and Spanish, in the repo's `fastlane/metadata`.

Build notes:

* ONNX Runtime (used for reading text) is compiled from source with the `onnxruntime` and
  `eigen` srclibs, by `scripts/build_onnxruntime_android.sh`; the Gradle property
  `onnxFromSource=true` makes the app use it instead of the Maven AAR.
* The repository's `app/debug.keystore` (Android's public test key, for test builds) is removed
  by `rm`.
* Two new srclibs are included in this merge request: `srclibs/onnxruntime.yml` and
  `srclibs/eigen.yml`.

Required:

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (I am the author)
* [x] All related fdroiddata and RFP issues have been referenced in this merge request
* [x] Builds with `fdroid build` and all pipelines pass

Strongly recommended:

* [x] The upstream app source code repo contains the app metadata (summary, description,
  images, changelog, etc.) in a [Fastlane](https://gitlab.com/snippets/1895688) folder structure
* [x] Releases are tagged

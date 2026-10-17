# RP Launcher

An Android home screen built for people with **retinitis pigmentosa (RP)**, from early
tunnel vision through to speech-first use.

Most low-vision tools make things bigger. RP mainly narrows the field of view, so RP
Launcher makes things **fit**: every tile, label and alert sits inside the part of the
screen the person can actually see, on a dark, glare-free background.

## What's in version 0.1

- **Viewing window check.** At first start, a bright frame grows or shrinks until the
  person can just see all four corners. Everything afterwards fits inside it.
- **Big tiles** with icon and label centred together, thick outlines and Atkinson
  Hyperlegible type that shrinks instead of being cut off or breaking a word.
- **Paging with big Next and Back tiles**, never swiping or long scrolling lists.
- **Dark themes only:** white on black, yellow on black, night amber and night red.
  The night themes dim the screen. Nothing ever flashes white.
- **Time and battery** inside the window. Tap to hear them.
- **Torch tile**, phone, messages, contacts, all apps, any app, and people to call.
- **Calling a person** opens a full-window check that speaks the name first.
- **Long press speaks** any tile. When TalkBack is on, the launcher's own speech steps aside.
- **Helper setup** behind an optional PIN: colours, grid, text size, speech speed, tiles.
- **Save and load the setup** as a file, to move it to a new phone.
- **Safe screen:** if the launcher fails to start twice in a row, a plain screen with
  Phone, Contacts and settings appears instead of a black phone.

## What's new in version 0.2

- **See tile:** the camera as a magnifier, inside the viewing window. Closer and Wider
  zoom (below normal size on phones with a wide lens, to fit more into a narrow field),
  Freeze holds a still picture you can pinch to zoom, Light brightens dim scenes or turns
  on the torch, and Colours turns dark print on white paper into light print on black.
- **Automatic night colours,** by clock or by room light, in amber or red.
- **Launcher brightness limit,** and short fades between screens.
- **Touch landmarks:** a long press buzzes once per row; page turns buzz twice.
- **Forgot the helper PIN?** Reset it with the phone's own screen lock, or by holding a
  tile for three seconds on phones without one.

## What's new in version 0.3

- **Read text aloud.** In See, tap Read: the camera takes a sharp photo, reads the print
  on the phone itself (no internet), and shows it one line at a time in large text.
  English and Spanish, each read in its own voice. If text is cut off or missing, it
  says how to aim better.
- **Alerts tile:** new notifications shown large and read aloud, one at a time, with
  Open and Dismiss. Needs notification access; nothing is stored or sent anywhere.
- **Vision modes:** Sight first (tap opens), Sight and speech (tap to hear, double tap
  to open), and Speech first (one list, tap to hear, optional dark screen). When TalkBack
  is on, tiles work the TalkBack way.

No accounts, no ads, no tracking, and no internet permission.

## Testable rules

The spec's promises are checked by unit tests on every build:

- every text and outline colour reaches **7:1 contrast** on every surface, in every theme
- no pure white anywhere, and all surfaces stay near black
- every tile, header and footer lies **inside the viewing window**, at every window size
  the check can produce, on small and large screens, at every text size
- every tile is at least **48 dp**, Android's minimum touch target
- setup files round-trip exactly, and broken or foreign files are refused safely

## Build

GitHub Actions builds the app and runs the tests on every push. Each push to `main`
replaces the **Latest test build** release: open **Releases** on a phone, tap
`rp-launcher-test.apk`, and allow installing from your browser. Test builds share one
debug key, so a new one installs over the last.

To build locally you need JDK 17 and the Android SDK (compileSdk 35):

```
./gradlew testDebugUnitTest assembleDebug
```

Requires Android 10 or newer.

## Licence

RP Launcher is free software under the [GNU General Public License v3.0](LICENSE).

The typeface is **Atkinson Hyperlegible** by the Braille Institute of America, under the
SIL Open Font License 1.1 ([licence text](licenses/Atkinson-Hyperlegible-OFL.txt)).

Text reading uses the **PP-OCRv6 tiny** models from PaddleOCR, as packaged by OnnxOCR, both
under the Apache License 2.0 ([licence text](licenses/OnnxOCR-and-PaddleOCR-models-Apache-2.0.txt)),
running on **ONNX Runtime** (MIT licence). Before an F-Droid release, ONNX Runtime will be
built from source, as F-Droid requires.

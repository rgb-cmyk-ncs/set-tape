# SetTape

A one-button audio recorder for Android, made for recording a DJ mixer (or any other
source) over USB without clipping.

- **Big record button**, timer, file size and how much recording time the storage has left.
- **Peak meter in dBFS** with peak hold, the highest level reached, and a clip counter.
  0 dB on the meter is digital full scale; aim for peaks around -6 to -3.
- **Input level slider** under the meter, -24 to +24 dB in half-dB steps, usable during a
  take. The meter and the file both follow it. Tap the number to return to 0 dB.
- **Auto start/stop**: arm it, and recording starts when sound arrives and stops after a
  stretch of silence. Two seconds from before the trigger are kept, so the first beat isn't cut.
- **Keeps recording with the screen off** or with another app in front.
- **USB audio in**, with a channel picker for mixers that send several stereo pairs.
- Records **WAV, 24-bit or 16-bit**, into `Music/SetTape` on the device.

## Getting the app onto a phone

1. Create a new repository on GitHub and upload everything in this folder to it
   (keep the folder layout, including `.github` and `signing`).
2. Open the repository's **Actions** tab. The "Build APK" run starts by itself on every
   push to `main`; it takes a few minutes.
3. Download the APK, either from the run's **Artifacts**, or from
   `https://github.com/<you>/<repo>/releases/download/latest/SetTape.apk`
   (that link always points at the newest build, and is handy for installing on several devices).
4. Open the APK on the phone and allow installing from that source.

Every build is signed with the same key, so a new build installs over the old one and
keeps your settings.

## Recording a DJM mixer

1. Connect the mixer's **USB port for a computer** (the one you would plug a laptop
   into, not a USB-stick or LINK port) to the phone with a data-capable USB-C cable.
2. Open SetTape. The line under the timer should name the mixer and end in "(USB)".
   If it says "Phone microphone" in amber, Android has not picked the mixer up.
3. Mixers like the DJM-A9 send several stereo pairs over USB. Under **Input > Channels**
   the app shows the live level of every pair; pick the one carrying the mix.
   Which pair carries the master/REC OUT is set on the mixer side, with the
   manufacturer's Setting Utility on a computer.
4. Play the loudest part of a track and watch **PEAK** and the clip counter, and set the
   **input level** slider so the peaks land a few dB under 0. Tap the meter to reset the readout.

The slider works on the sound after it has reached the phone. It makes a quiet source
louder and a hot one quieter, but it cannot repair sound that was already clipped on the
way in. The app tells the two apart: **CLIPPED** means the slider is too high, and
**SOURCE CLIPPED** means it has to come down at the source.

Not every phone passes every channel of a multichannel USB device through to apps.
If Android only offers two channels, they are the device's first pair (USB 1/2), so
route the mix there.

## Things worth knowing

- A 24-bit stereo recording at 48 kHz is about 1 GB per hour.
- A WAV file tops out near 4 GB (about 3 h 50 min at 24-bit/48 kHz). Longer takes
  carry on seamlessly in a second file ending in `_part2`.
- The file's length information is refreshed every 5 seconds while recording. If the
  battery dies mid-set, the recording is still there, and the app repairs its length
  the next time it lists it.
- If the USB cable drops out during a take, the app stops and saves rather than
  carrying on through the phone's microphone.
- If a long recording stops by itself with the screen off, set the app's battery
  setting to **Unrestricted** in Android's app settings.

## The signing key

`signing/settape.jks` and its password are in the repository on purpose: it is what lets
GitHub build an APK that updates the one already installed. Anyone with that key could
build an "update" your phone would accept, so if this ever becomes more than a personal
app, generate a new key and keep it in GitHub Secrets instead.

## Layout of the code

| File | What it does |
| --- | --- |
| `audio/Pipeline.kt` | Channel picking, metering, clip detection, auto start/stop, splitting long takes |
| `audio/WavWriter.kt` | Writes and repairs WAV files |
| `audio/PreRoll.kt` | The two seconds kept from before an auto-start |
| `Engine.kt` | Opens the Android audio input and feeds the pipeline |
| `RecorderService.kt` | Keeps recording alive in the background |
| `MainActivity.kt`, `MeterView.kt`, `RecordButton.kt` | The screen |
| `app/src/test` | Tests for the audio code, run by the build |

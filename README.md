# Dart LED

Shows the scores from the **DartCounter** app on an **iPixel Color** LED display (Bluetooth), live during a match.

```
|DAVID      131 0      <- whose turn (bar), name, remaining score, legs won
 MATEJ      149 1
```

## How it works
- **Reading:** an Android accessibility service reads the names, remaining scores, legs and checkout hint that DartCounter shows on its match screen. It only reads DartCounter's windows; it presses DartCounter's buttons only to enter darts sent by the DartCam phone (can be switched off).
- **Display:** the app draws a small picture sized for the panel (auto-detected, e.g. 64×16) and sends it over Bluetooth using the iPixel protocol reverse-engineered by the open-source [pypixelcolor](https://github.com/lucagoc/pypixelcolor) project.

## Camera scoring with DartCam (phone at the board)
**DartCam** (`cam/`) is a second app for a phone placed near the board (e.g. Pixel 10 Pro on a small tripod
below or beside the board, 30–60 cm away). It recognises every dart with the camera and sends it over Wi-Fi to
Dart LED on the tablet, which enters it into DartCounter by pressing DartCounter's keypad buttons.

```
Pixel (DartCam: camera)  --Wi-Fi-->  tablet (Dart LED)  --taps-->  DartCounter  --reads-->  LED display
```

Setup:
1. Tablet: install the new Dart LED, then in Android's accessibility settings switch Dart LED **off and on
   again** (it now also needs permission to press buttons). In Dart LED, section 5 shows the tablet's address.
2. DartCounter: use **single-dart input** (SINGLE / DOUBLE / TRIPLE + numbers, then SUBMIT).
3. Phone: install `DartCam.apk`, allow the camera, put phone and tablet on the same Wi-Fi.
   DartCam finds the tablet by itself (the address can be typed in as a fallback: tap the status line).
4. Fix the phone so it can't move, pull the darts out of the board and tap **⚙** (calibration). Tap **Auto**: DartCam finds the board by itself (the first time it tries right away) and sets the zoom
   so the board fills the picture (zooming uses more of the camera sensor, so darts are seen sharper;
   if the zoom is too far in, it zooms out and looks again). The zoom is kept for the next start.
   Check that **20 is at the top** and the green wireframe sits on the wires, then tap **Done**.
   If Auto can't find the board (dark room, darts in the board, a board that isn't red/green), tap the
   **outer edge of the double ring** in the middle of 20 (top), 6 (right), 3 (bottom), 11 (left) by hand.
   Either way the yellow circles can be dragged until the wireframe fits.
   While playing, DartCam keeps the calibration up to date by itself: every few seconds, when nothing moves,
   it looks for the board again; if the phone was nudged it takes the new position (two looks must agree)
   and says "Calibration updated". Small drift is fixed silently, and only while the board is empty.
   Switch it off with **Keep calibrated automatically** on the calibration screen (e.g. for a board Auto
   can't read).
5. Play. Each dart is shown on the phone and entered in DartCounter; pulling the darts out submits the visit.
   After each dart a **close-up** of the dart appears (full camera resolution, with the wires drawn in) and
   the number is **re-checked in that zoom**; the hint says when the zoom corrected the number, and the
   close-up gets a yellow frame with "close – check" when the tip sits within ~1 mm of a wire. Tap the
   close-up to hide it.
   Screen: status chips (tablet link, calibration; **⚙** opens calibration), player and remaining score, the visit's 3 darts in big tiles with the total (`*` = corrected by the zoom, yellow frame = close to a wire), camera picture, then big buttons.
   Buttons: **UNDO** (last dart), **MISS** (dart missed the board or bounced out – changes outside the scoring area, e.g. light flicker on the number ring, are ignored, so misses are entered with this button), **NEXT ▶** (submit
   when the takeout was not noticed). A wrong dart can also be fixed directly in DartCounter.

Moving the phone or changing the zoom needs a new calibration.

How the detection works (`cam/.../core/DartDetector.java`, plain Java): it waits until the picture is still,
compares it with the previous still picture, and takes the changed area as the new dart. The 4 calibration
points give a homography to board millimetres and also the camera's position; since darts stick out towards
the camera, the tip is the part of the dart nearest to the camera's foot point on the board. A takeout is
noticed when the board looks empty again. **Zoom re-check** (`cam/.../core/TipRefiner.java`): the detector works on a 640 px copy of the camera picture;
after each dart the window of ±40 mm around the tip is cut from the full-resolution pictures before and
after the dart and the tip is measured again there (`tools/dartcam-sim/RefineTest.java`: 92% → 97% exact,
tip error median 1.4 → 0.7 mm in the simulation).

**Auto** (`cam/.../core/AutoCalibrator.java`) looks for the red/green double and treble rings, fits an ellipse
to the double ring, reads the 20 segments around it, takes the 20 from the phone's gravity sensor (the board
hangs with 20 at the top) and fits the homography to ~270 measured ring edges and wires.
`tools/dartcam-sim/AutoCalTest.java` checks it on simulated colour pictures; `AutoCalPhoto.java` runs it on a
real photo and draws the result. `tools/dartcam-sim/SimTest.java` tests the dart detection on simulated camera
views (5 camera positions, noise, takeouts, an exposure jump): about 90% exact, tip error ~2 mm, and the
misses are darts placed on purpose within ~2 mm of a wire.

If darts are entered wrongly, use **Copy DartCounter buttons** in Dart LED during a match and adjust the
button names in `DartTyper.java`.

## Install
Every push to `main` builds the app on GitHub and publishes it at
**Releases › latest** (`DartLED.apk` for the tablet, `DartCam.apk` for the phone at the board). Open that page on the device, download, and install (allow installs from your browser when Android asks).

## Setup on the tablet
1. Open **Dart LED** › *Open accessibility settings* › switch on **Dart LED**.
   If the switch is greyed out: Settings › Apps › Dart LED › ⋮ › *Allow restricted settings*, then try again.
2. Close the iPixel Color app (it holds the Bluetooth connection), switch the display on, tap **Find display** and pick it.
3. Tap **Send test score**: the display should show DAVID 131 / MATEJ 149.
4. Start a match in DartCounter: the display follows the score.

## If scores don't show
Open Dart LED during a match and use **Copy what Dart LED sees**; that text shows exactly what DartCounter exposes, which is what the parser (`ScreenParser.java`) needs to be adjusted against.

## Code
- `core/` – plain Java, no Android: screen parser, pixel fonts, renderer, PNG encoder, iPixel commands.
- `DartReaderService` – reads DartCounter's screen.
- `LedLink` – Bluetooth connection and sending.
- `MainActivity` – the setup screen.
- `CamServer` / `DartTyper` – receive darts from DartCam over Wi-Fi and enter them into DartCounter.
- `cam/` – the DartCam phone app (camera, calibration, dart detection).

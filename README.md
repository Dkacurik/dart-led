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
2. DartCounter: use **single-dart input** (the keypad with the tabs **Single / Double / Treble / Bull 50 /
   Outer 25**, numbers 1–20, undo arrow and **MISS**). Dart LED finds that keypad on the screen
   (`app/.../core/Keypad.java`): T20 = Treble, 20; D16 = Double, 16; S5 = Single, 5; 25 = Outer 25;
   bull = Bull 50; the visit is submitted with the green arrow in the input bar, UNDO presses the undo
   arrow left of MISS (`tools/typer-sim/KeypadTest.java` checks this on that screen). DartCounter is a web
   app, so Dart LED taps like a finger; numbers are found by the keypad grid (in Double/Treble mode the
   buttons read "20 40" / "20 60").
   **Bust / end of leg:** on the next submit (takeout or NEXT ▶) Dart LED presses the bust banner's
   **Confirm**, answers **DARTS USED ON A DOUBLE** (darts thrown while the score left was a double –
   2–40 even or 50) and confirms the dialog (`core/Prompts.java`, `PromptsTest`). Darts sent while such
   a banner/dialog is open are not entered.
3. Phone: install `DartCam.apk`, allow the camera, put phone and tablet on the same Wi-Fi.
   DartCam finds the tablet by itself (the address can be typed in as a fallback: tap the status line).
4. Fix the phone so it can't move, pull the darts out of the board and tap **⚙** (calibration). Tap **Auto**: DartCam finds the board by itself (the first time it tries right away) and sets the zoom
   so the board fills the picture (zooming uses more of the camera sensor, so darts are seen sharper;
   if the zoom is too far in, it zooms out and looks again). The zoom is kept for the next start.
   Check that **20 is at the top** and the green wireframe sits on the wires, then tap **Done**.
   If Auto can't find the board (dark room, darts in the board, a board that isn't red/green), tap the
   **outer edge of the double ring** in the middle of 20 (top), 6 (right), 3 (bottom), 11 (left) by hand.
   Either way the yellow circles can be dragged until the wireframe fits.
   While playing, DartCam **tracks the board all the time** (like a QR scanner): about twice a second, when
   nothing moves, it looks for the board again. Small differences are smoothed in without disturbing the
   detection; if the phone was moved, the new position is taken after two matching looks ("Board moved –
   following"). The chip at the top shows **Board locked** / **Following…** / **Searching board: <reason>**.
   **Tap the camera picture** to focus there and to tell the tracker "the board is here" (handy if something
   else round or red/green is in view); once the board is locked, focus and exposure stay on the board.
   While the board is locked and empty, DartCam also **zooms by itself** so the board with its number
   ring fills the picture (up to 3×; more camera pixels per millimetre for the detection and the
   close-up). Moving the zoom slider by hand switches this off until the next **Auto**.
   Switch tracking off with **Track the board all the time** on the calibration screen (e.g. for a board Auto
   can't read).
5. Play. Each dart is shown on the phone and entered in DartCounter; pulling the darts out submits the visit.
   After each dart a **close-up** of the dart appears (full camera resolution, with the wires drawn in) and
   the number is **re-checked in that zoom**; the hint says when the zoom corrected the number, and the
   close-up gets a yellow frame with "close – check" when the tip sits within ~1 mm of a wire. Tap the
   close-up to hide it.
   Screen: status chips (tablet link, calibration; **⚙** opens calibration), player and remaining score, the visit's 3 darts in big tiles with the total (`*` = corrected by the zoom, yellow frame = close to a wire), camera picture, then big buttons.
   A dart outside the double ring – number ring, board edge, surround (up to 25 cm from the centre) – is
   scored as **MISS** when it is dart-shaped (a small object, or a streak pointing at the camera) and the
   full-resolution zoom confirms it; flicker patches on the number ring and changes the old picture
   explains (flicker, exposure, moiré when testing on a screen, a tiny camera shake) are ignored as
   "not a dart". Pulling darts out is never scored, also when the light changes while someone stands
   at the board: where the picture changed it looks like the empty board again.
   Buttons: **UNDO** (last dart), **MISS** (a bounce-out or a dart the camera didn't see), **NEXT ▶** (submit
   when the takeout was not noticed). A wrong dart can also be fixed directly in DartCounter.
   **Camera resolution:** DartCam starts with the highest 4:3 resolution the phone offers (up to
   4000×3000) and steps down (3264×2448 → 2560×1920 → 1920×1440 → 1280×960) when a frame takes more than
   ~80 ms to process; the step is remembered (**Max resolution** on the calibration screen tries the top
   again). The calibration screen shows the resolution, fps and time per frame. Detection runs on a
   640 px copy; the close-up re-check after each dart uses the full resolution.
   **Send debug** (calibration screen) zips the last detections: pictures before/after, the zoom window,
   the calibration and phone tilt in force.

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

If darts are entered wrongly, use **Copy DartCounter buttons** in Dart LED during a match: it lists every
element (icon buttons too) and, at the end, which keypad buttons Dart LED picked. Adjust `Keypad.java`.

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

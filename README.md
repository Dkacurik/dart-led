# Dart LED

Shows the scores from the **DartCounter** app on an **iPixel Color** LED display (Bluetooth), live during a match.

```
|DAVID      131 0      <- whose turn (bar), name, remaining score, legs won
 MATEJ      149 1
```

## How it works
- **Reading:** an Android accessibility service reads the names, remaining scores, legs and checkout hint that DartCounter shows on its match screen. It only reads DartCounter's windows and never taps or types anything.
- **Display:** the app draws a small picture sized for the panel (auto-detected, e.g. 64×16) and sends it over Bluetooth using the iPixel protocol reverse-engineered by the open-source [pypixelcolor](https://github.com/lucagoc/pypixelcolor) project.

## Install
Every push to `main` builds the app on GitHub and publishes it at
**Releases › latest › DartLED.apk**. Open that page on the tablet, download, and install (allow installs from your browser when Android asks).

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

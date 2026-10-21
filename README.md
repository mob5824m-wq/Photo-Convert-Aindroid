# Pixel Photo NAS (Google Pixel 1–5 Unlimited Google Photos Relay)

Turn any **Google Pixel 1 through Pixel 5** into a self-hosted 24/7 home Wi-Fi **Photo & Video NAS** that:
1. Accepts high-resolution camera uploads into **Folder 1 (`Incoming`)** via **Browser Drag-and-Drop Web Portal**, **WebDAV Network Drive**, **FTP Server (`:2121`)**, or **Local Sync (Syncthing / Resilio / FolderSync)**,
2. Optionally converts media to **Google Photos Storage Saver quality** (**≤ 16.0 MP JPEG** at 85% quality with **100% EXIF Date, Time & GPS location preserved**, plus **1080p H.264/AAC MP4** video compression),
3. Moves verified files into **Folder 2 (`DCIM/PixelNAS`)** and immediately notifies Android's `MediaStore` so **Google Photos** backs them up automatically, and
4. **Automatically deletes the higher-resolution originals** from Folder 1 once the output file is verified non-empty and intact.

---

## Supported Pixel Devices & Google Photos Unlimited Tiers

| Device Family | Codenames | Android API | Google Photos Entitlement | Recommended Mode |
|---|---|---|---|---|
| **Pixel / Pixel XL (2016)** | `sailfish`, `marlin` | Android 8.0–10 (API 26–29) | **Unlimited Original Quality** | **Pass-Through (`Saver OFF`)** *(or toggle `Saver ON` to keep 32GB internal flash from filling during big bursts)* |
| **Pixel 2 / 2 XL** | `walleye`, `taimen` | Android 8.0–11 (API 26–30) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 3 / 3 XL / 3a / 3a XL** | `blueline`, `crosshatch`, `sargo`, `bonito` | Android 9–12 (API 28–31) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 4 / 4 XL / 4a** | `flame`, `coral`, `sunfish` | Android 10–13 (API 29–33) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 4a (5G) / Pixel 5** | `bramble`, `redfin` | Android 11–14 (API 30–34) | **Unlimited Storage Saver** | **Storage Saver ON** *(Best 24/7 hardware: 6–8GB RAM, 128GB UFS, cool 765G)* |

---

## Why This Avoids the Classic Pixel NAS Pitfalls

- **`.nomedia` Shield on Folder 1 (`Incoming`)**: Prevents Google Photos from accidentally backing up raw 50 MB / 200 MB un-converted uploads while files are transferring over Wi-Fi.
- **Zero-Data-Loss Atomic Cleanup**: Waits until incoming uploads stabilize, writes converted output to a temporary `.converting_*` file, verifies file header & byte integrity, atomically renames into `DCIM/PixelNAS`, scans into `MediaStore`, and **only then** deletes the higher-resolution source file.
- **4 GB RAM OOM Protection (Pixel 1–3)**: Two-pass `BitmapFactory` bounds + power-of-two `inSampleSize` decoding lets a 4 GB RAM Pixel 1/2/3 convert **50 MP / 108 MP / 200 MP** camera photos without `OutOfMemoryError`.
- **Full EXIF Timeline & Map Location Preservation**: Preserves `DateTimeOriginal`, sub-second & timezone tags, camera `Make`/`Model`/`Lens`, and `GPSLatitude`/`GPSLongitude` while normalizing portrait EXIF orientation cleanly upright.
- **Hardware `MediaCodec` 1080p Video & 41 °C Thermal Guard**: Uses `androidx.media3:media3-transformer` hardware encoding and automatically pauses heavy encoding if the Pixel's battery temperature exceeds `41 °C`.
- **Google Takeout `.json` Sidecar Merger**: Migrating an old Google Photos library via Google Takeout? Drop `.jpg`/`.mp4` files alongside their `.json` sidecars into `Incoming/` — timestamps (`photoTakenTime`) and map GPS coordinates (`geoData`) are automatically baked back into real EXIF tags, and both the original and `.json` file are cleaned up.
- **WhatsApp / Screenshot EXIF Date Rebuilder**: Automatically parses stripped-EXIF filenames (`WhatsApp Image 2023-07-19 at 18.45.12.jpeg`, `PXL_...`, `IMG_...`, `Screenshot_...`) and writes the real historical `DateTimeOriginal` EXIF tag so chat photos never pile up on "Today" in Google Photos.
- **SHA-256 Duplicate Shield & Recursive Subfolder Ingestion**: Skips re-converting identical re-uploaded files via persistent SHA-256 hashing and recursively processes nested album subfolders (`Vacation2026/IMG_001.jpg` → `Vacation2026_IMG_001.jpg`).
- **Auto-Clean Synced Output Retention (`7d / 14d / 30d` + Low-Space FIFO Watermark)**: Uses NAS ingestion time (never historical photo capture date!) to automatically free local storage on 32 GB / 64 GB / 128 GB Pixels after Google Photos backs files up to the cloud.
- **OLED Burn-In Shield & mDNS Discovery (`PixelPhotoNAS`)**: One-tap `#000000` OLED blackout screen mode with pixel-shift telemetry for OLED Pixels (Pixel 2 XL – Pixel 5) plus ZeroConf `_http._tcp` LAN discovery.

---

## Option 1 — Install Native Android App (`android-app/`)

1. Download **`PixelPhotoNAS-v1.0.0-Pixel1to5-Signed.apk`** from the **GitHub Actions → Build Pixel Photo NAS APK** artifact (or open `android-app/` in Android Studio Ladybug+ and run `./gradlew assembleRelease`).
2. Install on your **Pixel 1–5** and grant **All Files Access** (`MANAGE_EXTERNAL_STORAGE` on Android 11–14, storage permission on Android 8–10).
3. Tap the switch to keep **NAS Daemon Online**:
   - **Web UI**: `http://<pixel-ip>:8080`
   - **WebDAV Share**: `http://<pixel-ip>:8080/webdav/Incoming/` *(macOS Finder `⌘K`, Windows Explorer Map Network Drive, PhotoSync, FolderSync)*
   - **FTP Server**: `ftp://<pixel-ip>:2121/` *(unprivileged port — no root needed)*
   - **Local Folder Sync**: Drop files via Syncthing (*Send Only*) into `/storage/emulated/0/PixelNAS/Incoming`.
4. Open **Google Photos → Collections / Library → Photos on device → PixelNAS** and enable **Backup**.

---

## Option 2 — Run Standalone Server (Termux on Android / Linux / macOS / Windows)

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 -m server.main
```

Open `http://localhost:8080` in your browser, test with **Drop Photos + 4K Video**, or run the full automated test suite:

```bash
pytest -v
```

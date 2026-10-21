# Pixel Photo NAS (Android Unlimited Google Photos Relay + Home Screen Upload Widget)

Turn any **Google Pixel 1 through Pixel 5** into a self-hosted 24/7 home Wi-Fi **Photo & Video NAS** — paired with a **Native Android Home Screen Upload Widget & Share Sheet** for your daily Android phones — that:
1. Accepts high-resolution Android camera uploads into **Folder 1 (`Incoming`)** via **Native Android Home Screen Upload Widget**, **Android System Share Sheet**, **Browser Drag-and-Drop Web Portal**, **WebDAV Network Drive**, **FTP Server (`:2121`)**, or **Local Sync (FolderSync / Round Sync / Syncthing-Fork)**,
2. Routes files automatically using **Per-Folder Profiles** (`/Incoming/StorageSaver/` → `≤ 16.0 MP` Storage Saver, `/Incoming/Original/` → `100%` untouched Original pass-through, `/Incoming/Family/` + custom family/album rules → dedicated `DCIM/PixelNAS/<Album>` output folder),
3. Isolates half-finished or unreadable transfers into a `.nomedia`-shielded **Quarantine Folder (`PixelNAS/Quarantine/`)** after 2 failed retries so corrupted files never block your watcher queue,
4. Moves verified outputs into **Folder 2 (`DCIM/PixelNAS`)**, immediately notifies Android's `MediaStore` so **Google Photos** backs them up automatically, and **deletes high-resolution originals** once verified.

---

## Automated APK Compilation & Rollover Versioning on Every Merge

GitHub Actions (`.github/workflows/build-apk.yml` + `server/versioning.py`) automatically runs unit tests, compiles V1+V2 signed APKs with a persistent release certificate (`android-app/keystore/pixelnas-release.p12` so updates install seamlessly over prior builds), and publishes a tagged **GitHub Release** on every merge into `main`:

- **First merge to `main`**: **`v1.0.0`** (`versionCode = 1`)
- **Patch increments (`+0.0.1`)**: `v1.0.0` → `v1.0.1` → … → `v1.0.9` → **`v1.1.0`**
- **Minor column rollover**: `v1.1.0` → … → `v1.8.9` → `v1.9.0` → … → `v1.9.9` → **`v2.0.0`**

---

## Supported Pixel Devices & Google Photos Unlimited Tiers

| Device Family | Codenames | Android API | Google Photos Entitlement | Recommended Default Mode |
|---|---|---|---|---|
| **Pixel / Pixel XL (2016)** | `sailfish`, `marlin` | Android 8.0–10 (API 26–29) | **Unlimited Original Quality** | **Pass-Through (`Saver OFF`)** *(or use Per-Folder `StorageSaver/` to save local flash space)* |
| **Pixel 2 / 2 XL** | `walleye`, `taimen` | Android 8.0–11 (API 26–30) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 3 / 3 XL / 3a / 3a XL** | `blueline`, `crosshatch`, `sargo`, `bonito` | Android 9–12 (API 28–31) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 4 / 4 XL / 4a** | `flame`, `coral`, `sunfish` | Android 10–13 (API 29–33) | **Unlimited Storage Saver** | **Storage Saver ON** |
| **Pixel 4a (5G) / Pixel 5** | `bramble`, `redfin` | Android 11–14 (API 30–34) | **Unlimited Storage Saver** | **Storage Saver ON** *(Best 24/7 closet NAS hardware: 6–8 GB RAM, 128 GB UFS, cool 765G)* |

---

## Key Capabilities

- **Native Android Home Screen Upload Widget & Share Sheet (`PixelNasUploadWidgetProvider`)**:
  - Install the same APK on both your closet **Pixel 1–5 NAS** and your **daily Android phone** (Pixel 8/9, Samsung Galaxy, OnePlus, Motorola, Nothing, etc.).
  - Tap **Auto-Find NAS** (mDNS `PixelPhotoNAS`) and **Pin Widget to Home Screen** — right from your Android Home Screen, tap **`Saver ≤16MP`**, **`Original`**, or **`Family`** to pick up to 50 photos/videos in Android's native Photo Picker and stream them over Wi-Fi straight to your Pixel NAS!
  - Also adds **Upload to Pixel Photo NAS** directly into Android's **Share Sheet** (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`).
- **Per-Folder Upload Profiles (`/Incoming/<Folder>/`)**:
  - `/Incoming/StorageSaver/` → Downscales photos `> 16.0 MP` to `≤ 16.0 MP` JPEG (85%) + `4K → 1080p` MP4 video.
  - `/Incoming/Original/` → Keeps `100%` untouched full-resolution originals even when global default is Storage Saver.
  - `/Incoming/Family/` & custom family/album folders → Configurable Storage Saver or Original mode routed into `DCIM/PixelNAS/<Album>` so family uploads stay neatly organized.
- **Quarantine Folder for Corrupted Transfers (`PixelNAS/Quarantine/`)**:
  - Half-transferred or corrupted media streams are retried twice and then isolated into `.nomedia`-shielded `Quarantine/` so Google Photos never indexes broken bytes and healthy uploads never stall. Includes 1-tap **Retry**, **Inspect Bytes**, and **Clear** controls.
- **Smart Metadata & 24/7 Closet Reliability**:
  - **Google Takeout `.json` Sidecar Merger** (bakes `photoTakenTime` + `geoData` GPS back into real EXIF tags).
  - **WhatsApp / Pixel / Samsung EXIF Date Rebuilder** (`WhatsApp Image 2023-07-19...`, `PXL_...`, `SAM_...`, `IMG_...`).
  - **SHA-256 Duplicate Shield**, **4 GB RAM OOM-Safe 200 MP Decoder**, **41 °C Battery Thermal Guard**, **Auto-Clean Synced Output Retention (`7d / 14d / 30d`)**, and **OLED `#000000` Burn-In Shield**.

---

## Option 1 — Install Native Android App (`android-app/`)

1. Download **`PixelPhotoNAS-v1.0.0-Pixel1to5-Signed.apk`** (or the latest merged version) from **GitHub Releases** or the **GitHub Actions** artifact.
2. **On your Closet Pixel 1–5**: Open **Pixel Photo NAS**, grant **All Files Access**, enable **NAS Daemon Online**, and turn **Backup ON** for `PixelNAS` inside **Google Photos → Collections → On this device**.
3. **On your Daily Android Phone**: Install the APK, tap **Auto-Find NAS** to pair over Wi-Fi, and tap **Pin Widget to Home Screen** (or configure **FolderSync / Round Sync** against `http://<pixel-ip>:8080/webdav/Incoming/StorageSaver/`).

---

## Option 2 — Run Standalone Server (Termux on Android / Linux)

```bash
pip install -r requirements.txt
python3 -m server.main
```

Open `http://localhost:8080` in your browser or run the full automated test suite:

```bash
pytest -v
```

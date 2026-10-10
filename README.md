# PDF Studio (mobile)

Native PDF reader/editor for **Android** (Kotlin + Jetpack Compose) and **iOS** (Swift + SwiftUI + PDFKit).
This repository is the MVP implementation of the design in
[`delivery-ops-platform/docs/pdf-app/system-design.md`](https://github.com/hadhvikcc-ux/delivery-ops-platform/blob/main/docs/pdf-app/system-design.md).

```
android/   Gradle project (Compose, PdfRenderer + PDFBox-Android)
ios/       XcodeGen spec + SwiftUI sources (PDFKit)
.github/   CI: builds + tests both apps; Android job uploads an installable APK
```

## Install the Android app

1. Open **Actions → Android → latest successful run** and download the **pdf-studio-debug-apk** artifact
   (or, for a tagged release, the `pdf-studio-debug.apk` release asset).
2. Unzip, copy `app-debug.apk` to the phone, open it and allow "install from this source".
3. Or with a cable: `adb install app-debug.apk`.

Local build: `cd android && ./gradlew assembleDebug` (JDK 17 + Android SDK 34).

## Run the iOS app

iOS apps cannot be installed straight from GitHub; they need Xcode and an Apple ID (free for your own device).

```
brew install xcodegen
cd ios && xcodegen generate && open PDFStudio.xcodeproj
```
Select your device/team under *Signing & Capabilities*, then Run. TestFlight/App Store distribution needs a paid
Apple Developer account and signing secrets added to CI (not configured here).

## What works in this MVP

| Requirement | Android | iOS |
|---|---|---|
| Open from storage / email / cloud ("Open with", share sheet, file picker) | ✅ | ✅ |
| Zoom (pinch, double-tap), swipe/scroll pages | ✅ | ✅ |
| Search text | ✅ (PDFBox) | ✅ (PDFKit) |
| Thumbnails, bookmarks | ✅ | ✅ |
| Highlight, freehand ink | ✅ (burned in as vector) | ✅ (real PDF annotations) |
| Comments | ❌ | ✅ (sticky note) |
| Merge, split, reorder/delete pages | ✅ | ✅ |
| Watermark | ✅ | ❌ |
| Password protect / open protected PDFs | ✅ AES-256 | ✅ |
| Version history + restore (local, immutable versions) | ✅ | ✅ |
| Share PDF / page as PNG | ✅ | ✅ |
| Offline | ✅ everything is local | ✅ |
| Cloud sync (Drive / OneDrive / Dropbox) | ✅ via a folder you pick (Storage Access Framework) | ✅ via Files folder |
| Dark mode, night reading, TalkBack/VoiceOver labels, EN + ES | ✅ | ✅ |

## Not built yet (needs backend or paid SDKs)

Editing existing text/images, headers/footers, export to PDF/A / Word / Excel, OCR, certificate-based
digital signatures, multi-user collaboration, server-backed sync, crash reporting and analytics, and the
Node/Java microservices. See the design document for how each is planned; the on-device parts here are
structured so a server implementation can replace them.

## Known limitations

* Android annotations ignore pages with a `/Rotate` entry (placement may be off).
* Android watermark uses a built-in Latin font only.
* Android zoom re-renders at coarse steps; very large pages are capped at 3200 px wide.
* Folder sync is last-writer-wins on push; on pull, a clashing name is imported as "<name> (remote)".
* Neither app has been run on a device by the author of this commit; CI is the first real build.

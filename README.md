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
| **Edit existing text** | ✅ in place when safe, otherwise cover + overwrite | ⚠️ cover + overwrite only |
| **Add text** | ✅ | ✅ (FreeText annotation) |
| **Add / replace / delete images** | ✅ | ➕ add only (flattens annotations) |
| **Edit document properties** (title, author, subject, keywords) | ✅ | ✅ |
| Watermark | ✅ | ❌ |
| **OCR** (make scans searchable) | ✅ ML Kit, offline | ✅ Vision, offline |
| **PDF/A-2b export** | ✅ (rasterised, validated with veraPDF in CI) | ❌ not possible on-device, needs a server |
| Password protect / open protected PDFs | ✅ AES-256 | ✅ |
| Version history + restore (local, immutable versions) | ✅ | ✅ |
| Share PDF / page as PNG | ✅ | ✅ |
| Offline | ✅ everything is local | ✅ |
| Cloud sync (Drive / OneDrive / Dropbox) | ✅ via a folder you pick (Storage Access Framework) | ✅ via Files folder |
| Dark mode, night reading, TalkBack/VoiceOver labels, EN + ES | ✅ | ✅ |

## Not built yet (needs backend or paid SDKs)

Headers/footers, export to Word / Excel, certificate-based
digital signatures, multi-user collaboration, server-backed sync, crash reporting and analytics, and the
Node/Java microservices. See the design document for how each is planned; the on-device parts here are
structured so a server implementation can replace them.

## Editing notes

Tap **Edit** in the bottom bar, then tap a line of text, an image, or empty space. Changes are queued (Android
shows them as overlays) and saved together as one new version, so every edit can be undone from version history.

* **Fonts:** the dialog starts from the font family, weight, slant, size and colour detected in the document
  (Sans / Serif / Mono, bold, italic) and lets you change them. New text is set in the matching standard PDF font
  (Helvetica, Times or Courier family), or in an Android system font when it needs characters outside Latin-1.
  The document's own font is only reused when it is a simple font that still contains every glyph you typed;
  subset or embedded composite fonts (typical for Word / Chrome / LibreOffice PDFs) cannot be reused, so the
  closest standard font is used instead, which is similar but not identical.
* **Android text replacement** has three tiers, best first: (1) swap the string inside the original font;
  (2) remove the original text operators from the page, keeping their width so nothing moves, and draw the new text in
  the matched font - the old text is really gone from the file; (3) only if the text shares operators with other
  text, or uses unusual operators or Type 3 fonts, paint over it with a box in the sampled background colour and draw
  the new text on top, in which case **the old text stays in the file** (the save message says so).
* **iOS** can only do the cover-and-overwrite variant, because PDFKit does not expose page content, though the new
  text uses the font detected from the selection. Added text is a standard FreeText annotation. Inserting an image
  rebuilds the file and flattens existing annotations.
* Edits are applied and saved as a new version immediately; undo them from version history.
* Scanned pages have no text to edit; run OCR first (OCR text is invisible and sits over the scan, so editing it
  does not change what you see).
* Neither platform is a redaction tool; do not use text edits to remove sensitive information.
* Edits assume unrotated pages; text in rotated pages or rotated images may be positioned wrongly.
* Fonts: new text uses the device's system font (Android) or the system font (iOS); the original font is not matched.

## OCR and PDF/A notes

* **OCR** recognises Latin-script text only (accented letters included). It adds an invisible text layer to pages
  that have no text, so search, copy and the PDF/A export keep working. Accuracy depends on scan quality.
* **PDF/A-2b (Android only)**: the exporter rasterises each page (about 200 dpi JPEG), attaches an sRGB output intent and
  XMP conformance metadata, and, when "searchable" is on, an OCR text layer in an embedded system font. The result is a
  new library document; files are larger than the source and source links/vector text are not preserved. The
  `PDF/A conformance` workflow generates a file on an emulator and checks it with veraPDF.
* iOS has no PDF/A export: Core Graphics cannot write the required structures. Doing it properly needs a server
  (iText/PDFBox) or a commercial SDK.
* iOS OCR rebuilds the PDF through Core Graphics, which drops annotations and page rotation; save annotations first.

## Known limitations

* Android annotations ignore pages with a `/Rotate` entry (placement may be off).
* Android watermark uses a built-in Latin font only.
* Android zoom re-renders at coarse steps; very large pages are capped at 3200 px wide.
* Folder sync is last-writer-wins on push; on pull, a clashing name is imported as "<name> (remote)".
* Neither app has been run on a device by the author of this commit; CI is the first real build.

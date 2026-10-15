# Fusion Pixel Font

- Upstream: https://github.com/TakWolf/fusion-pixel-font
- Release: `2026.09.25`
- Archive: `fusion-pixel-font-12px-proportional-otf.woff2-v2026.09.25.zip`
- Original file: `fusion-pixel-12px-proportional-zh_hans.otf.woff2`
- Bundled file: `src/assets/fonts/fusion-pixel-12px-proportional-zh_hans.woff2` (unchanged font bytes)
- SHA-256: `0ebd99e6f1e9b650a793a3f759c1c3dfa99f0c5b6502e78569b48ddb43767e40`
- License: SIL Open Font License 1.1; see `OFL.txt` and the upstream component notices in `LICENSES/`.

The simplified Chinese variant also contains Latin and other fallback glyphs. It is loaded locally for the Minecraft-inspired mobile theme and lyric preset. The font and these notices ship together in the renderer build. No game textures or game font files are included.

## Android native format

`android/app/src/main/assets/fonts/fusion-pixel.otf` is the same font decompressed from WOFF2 to OpenType for Android Typeface (no glyph or design edits). Converted with Python fontTools 4.67.0 / Brotli: load the WOFF2 with `TTFont`, set `font.flavor = None`, then save the OTF. Size: 4,955,388 bytes. SHA-256: `1c060bb3a428a514016473cff393c701eb40aa0d1fad442dc74381f08367fb14`. The web WOFF2 and native OTF retain the same SIL OFL and upstream component notices; these notices are bundled through the web assets.

# Pixels user guide

Pixels makes photos look their best while keeping them true to the scene. Everything happens on
your phone: Pixels has no internet access, and your original photo is never changed.

**Work from big to small:** crop and level first, then light, then colour, then local fixes.

## Getting a photo in

- **Choose photo** opens the system photo picker.
- **Take a photo** opens your camera app; the picture goes straight into Pixels.
- **Share to Pixels** or **Edit with Pixels** from a gallery or files app. Pixels keeps its own
  copy so you can come back to the edit later.
- **Recent edits** on Home reopen any edit exactly where you left it, with its history. Use the ⋮
  menu on a row to rename, duplicate or remove an edit (removing never deletes your photo).

## The editor

The photo is at the top (on the left when the phone is sideways), the open tool's panel below it,
and the tool strip at the bottom. A dot under a tool means you changed something there.

- **Press and hold the photo** to see the original. **Compare** (top bar) shows a before/after
  divider you can drag; pinch or double-tap to zoom.
- **Sliders:** drag anywhere along the row to change the value a little at a time; double-tap to
  reset. The value snaps to zero (with a light tick) as you pass it.
- **Undo / Redo** in the top bar; every finished change is one step.
- **Reset** at the bottom of a panel resets just that panel.
- On a narrow screen or with large text, **Share**, **Compare** and **Redo** move into the **More**
  menu so Save always fits.
- Selected chips are filled and show a tick; a dot after a name (for example "Red •" in the curve)
  means that part has changes.

## Accessibility

Pixels works with TalkBack: every button, slider and chip has a name, sliders read their value
and can be changed with TalkBack's adjust gestures, and tools with changes are read as "edited".
Text follows your phone's font size up to 200 per cent; layouts wrap and scroll instead of cutting
text off. Every control is at least 48 dp, and the before/after divider can be moved with TalkBack
as well as by dragging. Selection never relies on colour alone.

## Tools

| Tool | What it does |
|---|---|
| **Presets** | 29 looks in 8 groups (Portrait, Landscape, Cinematic, Vintage, Black & White, Food, Urban, Seasons). Tap one, then set **Amount** from 0 to 200 %. Save your own with ⋮ > Save as preset; long-press one of yours to delete it. |
| **Auto** | Fixes exposure, colour casts and noise for this photo. Lower **Auto strength** for a lighter touch; pick a scene if Pixels guessed wrong. **Original (no edits)** returns to the untouched photo. |
| **Crop** | Rotate, flip, straighten (Auto levels the horizon) and aspect ratios: Free, Original, 1:1, 5:4, 4:3, 3:2, 16:9, 21:9, 4:5, 3:4, 2:3, 9:16. |
| **Light** | Exposure, Contrast, Highlights, Shadows, Whites, Blacks, plus fine-tune and Face exposure. **Show clipping** marks pure white in red and pure black in blue. **Curve:** tap to add a point, drag it, double-tap to remove; or start from a curve preset. |
| **Colour** | White balance **Auto**, **As shot** (keeps the camera's colours) or **Pick** (tap something that should be grey or white), Temperature, Tint, Vibrance, Saturation, Hue. **Mixer** changes one colour at a time; **Grading** tints shadows, midtones and highlights; **Calibration** re-tunes all reds, greens and blues at once; **B&W** converts to black and white. |
| **Effects** | Texture, Clarity, Dehaze; Vignette and Grain with shape controls. |
| **Detail** | Sharpening (amount, radius, detail, masking) and noise reduction (luminance and colour). |
| **Optics** | Lens distortion, colour fringing, lens vignetting, and **Defringe** for purple or green edges. |
| **Geometry** | **Auto upright** straightens leaning buildings; fine-tune Vertical, Horizontal, Rotate, Aspect, Scale and Offset. The photo zooms just enough to hide empty edges. |
| **Masking** | Up to 12 masks: Brush, Linear, Radial, Luminance range, Colour range. **Show mask** shows it in red; paint, erase, invert, duplicate, rename. Each mask has its own light and colour sliders. |
| **Healing** | Tap a blemish to remove it; drag the dashed circle to choose another source. **Clone** copies exactly; **Red eye** fixes red pupils. |
| **History** | Every change by name; tap a step to go back to it. **Versions** saves named snapshots of the edit. |

## Saving and sharing

- **Save** writes a **new file** to `Pictures/Pixels`. Choose:
  - format: **JPEG**, **PNG** or **WebP**, and quality for JPEG and WebP;
  - size: Full, Large (2560 px), Medium (1600 px) or Small (1080 px) on the long edge;
  - metadata: keep all, **remove location** (default) or remove all;
  - an optional **border** (thin, medium or thick; white or black) and **watermark** text with
    position, size and opacity.
  The dialog shows the exact pixel size before you save. Saving can be cancelled.
- **Share** sends a copy to another app without saving it to your gallery.
- **Copy / Paste settings** (⋮ menu) copies chosen groups of settings to another photo.
- **Apply to other photos** (⋮ menu): choose which settings to copy, pick up to 50 photos, and
  each is saved as a new file with your export settings. Crop, masks and healing stay with the
  original photo.

## Settings (Home > Settings)

Auto strength for new photos · haptic feedback · ask before leaving an edit that was never saved ·
export defaults · storage used by Pixels · **Clear temporary files** · **Delete my presets** ·
**Delete all edits** (removes Recent edits, previews and photos imported from other apps or the
camera; photos in your gallery and files you saved are not touched).

## Privacy

No account, no internet, no analytics. Edits, previews and imported photos stay in Pixels' private
storage and are excluded from backups. Location is removed from saved photos unless you choose to
keep it. Full text: Home > About, privacy and terms.

## Troubleshooting

| Message or problem | What to do |
|---|---|
| "This file type is not supported" | Use a JPEG, PNG, WebP or HEIC/HEIF photo. |
| "The photo could not be opened" when reopening a recent edit | The photo was moved or deleted, or the app it came from only lent it until Pixels closed. Pick it again, or share it to Pixels so Pixels keeps its own copy. |
| "Not enough storage space to save" | Free some space or save a smaller size; your edits are kept. |
| "Not enough memory for this size" | Save at Large or Medium size; full-size exports are limited to about 24 megapixels. |
| "No camera app is available on this device" | Install or enable a camera app, or use Choose photo. |

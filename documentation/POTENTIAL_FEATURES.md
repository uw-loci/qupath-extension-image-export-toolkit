# Potential Features: Lessons from QuickFigures & BioVoxxel Figure Tools

## Context

This document catalogs features found in two Fiji figure-creation plugins --
[QuickFigures](https://github.com/grishkam/QuickFigures) and
[BioVoxxel Figure Tools](https://github.com/biovoxxel/BioVoxxel-Figure-Tools) --
that QuIET does not currently offer. Each entry includes a description, an
implementation sketch against the current QuIET codebase, and known challenges.

**Current QuIET capabilities** (v0.7.3): Rendered exports (classifier overlay,
object overlay, density map overlay), mask exports (binary, grayscale, colored,
instance, multi-channel -- JPEG blocked for label integrity), raw pixel exports,
tiled ML training exports, per-object classification crop exports, SVG vector export,
per-annotation region export, panel labels (A, B, C), info labels (per-image metadata
templates with live preview), visual LUT selector, scale bars (smart color defaults
from project images, background box option), color scale bars, display settings control
(per-image, current viewer, saved preset, global matched, raw), split-channel export,
DPI/resolution control, script generation, batch processing, metadata sidecars,
GeoJSON export, QUAREP-LiMi context-sensitive guidelines panel (Step 2),
floating Publication Advice dialog with config section highlighting (Step 3),
and a three-step wizard UI with collapsible section-based config panes.

**Codebase metrics** (v0.7.3):
- ~45 Java source files across `export/` (25 files), `ui/` (11 files), `advice/` (4 files), `scripting/` (6 files), `preferences/` (1 file), root (1 file)
- `RenderedExportConfig` has sub-config records (ScaleBarConfig, TextLabelConfig, etc.) with ~35 total fields
- `RenderedConfigPane` uses collapsible TitledPane sections (6 sections: Image Settings, Overlay Source, Object Overlays, Scale Bar, Color Scale Bar, Panel Label)
- `RenderedScriptGenerator` is ~1050 lines generating 3 script variants with panel label support
- One external dependency beyond QuPath: JFreeSVG 5.0.6 for SVG export (Gson comes from QuPath)
- 14+ test classes in the test suite
- Shared `TextRenderUtils` eliminates text rendering duplication across renderers
- `SectionBuilder` utility provides consistent collapsible section creation across all 5 config panes

---

## Priority Tiers

| Tier | Criteria |
|------|----------|
| **P1 - High** | High user value, directly requested, fits QuIET's export focus |
| **P2 - Medium** | Valuable but more complex or niche |
| **P3 - Low** | Nice-to-have, large scope, or edge-case |

---

## Feature List

### F1. Single-Annotation Figure Panel Export -- COMPLETED (v0.5.0)

**Priority:** P1 -- **IMPLEMENTED**
**Source:** QuickFigures (crop + overlay + scale bar in one shot), user request

**Description:**
Select a single annotation in QuPath and export a publication-ready cropped panel
showing just that region with overlays, scale bar, color scale bar, and optional
panel label. Currently QuIET exports whole images; there is no way to export a
cropped view centered on one annotation.

**Implementation sketch:**
- Add a `RegionScope` enum to `RenderedExportConfig`: `WHOLE_IMAGE`,
  `SELECTED_ANNOTATION`, `ALL_ANNOTATIONS_INDIVIDUAL`.
- Add `regionPaddingPixels` (int, default 0) to `RenderedExportConfig`.
- In `RenderedImageExporter`, compute `RegionRequest` from the annotation's
  bounding box (with optional padding) instead of the full image bounds.
  The three render methods (`renderClassifierComposite`,
  `renderObjectComposite`, `renderDensityMapComposite`) already use
  `RegionRequest` internally -- currently hardcoded to `(0, 0, width, height)`.
  Change to use the annotation ROI's bounding box when scope is not WHOLE_IMAGE.
- Scale bar calculation already works from pixel calibration + region size --
  `maybeDrawScaleBar()` (line 438) receives image dimensions and pixel size,
  both of which adapt automatically to the cropped region.
- The `paintObjects()` method (line 638) uses `ImageRegion.createInstance(0, 0, ...)` --
  would need to pass the crop region instead so overlays are clipped correctly.
  The `gCopy.scale(1.0 / downsample, ...)` transform would need an additional
  translate to match the crop origin.
- Script generator: emit per-annotation loop with `getSelectedObject()` or
  `getAnnotationObjects()`.
- UI: add a "Region" dropdown to RenderedConfigPane with the three options,
  plus a padding spinner. Insert at approximately row 8 (after format combo).

**Challenges:**
- Annotation bounding boxes can be irregularly shaped (polygons, ellipses). Need
  to decide: export the rectangular bounding box, or mask outside the annotation
  boundary? Rectangular is simpler and matches QuickFigures behavior.
- Very small annotations at high resolution could produce enormous images. Need
  a max-dimension safety cap or auto-downsample.
- `RawExportConfig` already has `RegionType` (WHOLE_IMAGE, SELECTED_ANNOTATIONS,
  ALL_ANNOTATIONS) with `paddingPixels` -- the same concept. Could unify the
  enum across both config types into a shared type, or keep them separate
  since rendered adds the SELECTED_ANNOTATION (singular) option for
  "current selection" semantics.
- `RawImageExporter.exportAnnotations()` (line 116) already implements the
  padding + bounding box + clamping logic. The annotation iteration pattern
  can be reused directly.

**Effort estimate:**
- New fields in `RenderedExportConfig`: 2 (enum + int) -> +2 builder methods, +2 getters
- Modified methods in `RenderedImageExporter`: 5 (3 render composites + paintObjects + each export method)
- New UI controls in `RenderedConfigPane`: 2 (ComboBox + Spinner)
- Script generator changes: moderate (add annotation loop to 3 script variants)
- Files touched: 4 (`RenderedExportConfig`, `RenderedImageExporter`, `RenderedConfigPane`, `RenderedScriptGenerator`)
- Estimated LOC: ~200 new, ~50 modified
- Test files: 1 new (annotation region rendering test)

**Cross-references:** F2 (inset uses same region concept), F12 (crop is a subset of this)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedExportConfig.java` | 20-27 (RenderMode enum) | Add RegionScope enum nearby |
| `RenderedExportConfig.java` | 244-430 (Builder) | Add regionScope + regionPaddingPixels fields |
| `RenderedImageExporter.java` | 282-338 (renderClassifierComposite) | Change RegionRequest from full image to annotation bounds |
| `RenderedImageExporter.java` | 343-386 (renderObjectComposite) | Same change |
| `RenderedImageExporter.java` | 460-533 (renderDensityMapComposite) | Same change |
| `RenderedImageExporter.java` | 638-668 (paintObjects) | Adjust ImageRegion and gCopy.translate for crop offset |
| `RawImageExporter.java` | 116-195 (exportAnnotations) | Reuse padding/bounding-box/clamp pattern |
| `RawExportConfig.java` | 16-23 (RegionType enum) | Reference design for region type enum |
| `RenderedConfigPane.java` | 118-461 (buildUI) | Insert Region controls around row 8 |
| `RenderedScriptGenerator.java` | 558-743 (generateClassifierScript) | Add annotation loop variant |

---

### F2. Inset / Zoom Panels

**Priority:** P1
**Source:** QuickFigures (Inset Tool), BioVoxxel (Create Framed Inset Zoom)

**Description:**
Draw a small ROI on the exported image to generate a magnified inset panel
embedded in a corner (or beside) the main image. The source region is marked
with a colored frame on the main image, and the zoomed inset shows the
magnified detail. BioVoxxel uses strictly integer magnification to avoid
interpolation artifacts.

**Implementation sketch:**
- New class `InsetRenderer` in the `export` package, following the
  `ScaleBarRenderer` / `ColorScaleBarRenderer` stateless utility pattern.
- Public API: `drawInset(Graphics2D g2d, BufferedImage fullImage,
  Rectangle2D sourceRegion, int magnification,
  ScaleBarRenderer.Position position, Color frameColor, int frameWidth)`.
- Pipeline: (1) crop source region from full image, (2) scale up by integer
  factor using nearest-neighbor (no interpolation), (3) draw frame rectangle
  on main image at source location, (4) draw inset image at chosen corner with
  matching frame, (5) optionally draw connecting lines from source frame corners
  to inset corners.
- Follow the same graphics state save/restore pattern used in `ScaleBarRenderer`
  (lines 69-156) and `ColorScaleBarRenderer` (lines 58-177): save composite,
  AA hint, font, color at entry, restore in finally block.
- Configuration: add `showInset`, `insetRegion` (x, y, w, h as fractions of
  image), `insetMagnification`, `insetPosition`, `insetFrameColor`,
  `insetFrameWidth` to `RenderedExportConfig` -- 6 new fields.
- Integration point: call after `maybeDrawScaleBar()` and before `g2d.dispose()`
  in all three render composite methods.
- UI: "Inset" section in RenderedConfigPane. The inset region could be defined
  by the user selecting a small rectangle annotation named "inset" in QuPath,
  or by numeric x/y/w/h fields.
- Script generation: inline `drawInset()` function in Groovy, following the
  same pattern as `emitScaleBarFunction()` (RenderedScriptGenerator lines 123-167).

**Challenges:**
- Defining the inset region per-image is the hardest UX problem. QuickFigures
  uses an interactive drag tool; BioVoxxel uses the current ROI selection.
  In a batch export context, per-image inset regions are impractical. Options:
  (a) fixed fractional region applied to all images, (b) use a specially-named
  annotation ("inset") per image, (c) interactive preview-only.
- Connecting lines between source frame and inset look professional but require
  careful geometry to avoid overlapping scale bars.
- Memory: large images at high magnification could produce very large insets.
  Cap inset dimensions.

**Effort estimate:**
- New classes: 1 (`InsetRenderer`, ~200 LOC)
- New fields in `RenderedExportConfig`: 6 -> +6 builder methods, +6 getters (~80 LOC)
- New UI controls in `RenderedConfigPane`: ~6 (checkbox, 4 spinners, color picker)
- Script generator: 1 new function (~60 LOC)
- Files touched: 5 (`InsetRenderer` new, `RenderedExportConfig`, `RenderedImageExporter`, `RenderedConfigPane`, `RenderedScriptGenerator`)
- Estimated total LOC: ~450
- Test files: 1 new (`InsetRendererTest`)

**Cross-references:** F1 (done -- annotation region concept feeds inset source), F5 (done -- panel labels could overlap inset position)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `ScaleBarRenderer.java` | (entire class) | Pattern to follow: stateless utility, delegates text to TextRenderUtils |
| `ColorScaleBarRenderer.java` | (entire class) | Same pattern with vertical gradient |
| `PanelLabelRenderer.java` | (entire class) | Newest renderer example, uses TextRenderUtils |
| `TextRenderUtils.java` | (entire class) | Shared text rendering -- use for inset labels |
| `RenderedImageExporter.java` | (maybeDrawScaleBar call) | Insert `maybeDrawInset()` call after this in each composite method |
| `RenderedImageExporter.java` | (maybeDrawScaleBar) | Pattern for conditional overlay drawing |
| `RenderedScriptGenerator.java` | (emitScaleBarFunction) | Pattern for inlining a renderer as Groovy function |
| `RenderedExportConfig.java` | (Builder) | Add 6 inset-related fields |

---

### F3. Multi-Panel Grid / Figure Layout Composition

**Priority:** P1
**Source:** QuickFigures (one-click split-channel figure, flexible grid layout)

**Description:**
Compose multiple exported images into a single figure with a grid layout.
For example, 2x3 grid showing 6 conditions, or a row of split channels plus
merge. Panels share consistent sizing, spacing, and alignment. Output is a
single image file.

**Implementation sketch:**
- New class `FigureComposer` in the `export` package.
- Public API: `composeFigure(List<BufferedImage> panels, int columns,
  int gapPixels, Color backgroundColor)` returns a single `BufferedImage`.
- Each panel is scaled to match the largest panel's dimensions (or user-specified
  uniform size). Panels arranged left-to-right, top-to-bottom.
- Optional row/column labels drawn in the gap regions.
- Optional panel letters (A, B, C...) drawn at upper-left of each panel
  (depends on F5 `PanelLabelRenderer`).
- Integration into `BatchExportTask`: add a post-processing step after the
  per-image loop (after line 195). Collect all rendered `BufferedImage` objects
  instead of writing them individually, then compose and write once.
  This requires changing `exportRendered()` to optionally return images
  rather than writing to disk.
- Alternatively, implement as a second pass: read back the exported PNGs,
  compose, write the composed figure. Simpler but slower.
- UI: new wizard step or checkbox "Compose into figure" with column count,
  gap size, background color. Could be a section in the ImageSelectionPane
  (Step 3) since it operates on the full batch.

**Challenges:**
- Panel ordering: user needs to control which image goes where. The project
  entry list order may not match desired figure layout. Need drag-and-drop
  reordering or manual index assignment.
- Mixed aspect ratios: if images have different dimensions, uniform scaling
  produces different effective magnifications. Need to handle this (crop to
  uniform aspect ratio? letterbox with background color?).
- Memory: composing many high-resolution panels into one figure could exceed
  available memory. Need dimension limits or auto-downsampling.
- `BatchExportTask` currently writes each image immediately and does not hold
  references to rendered images. Would need either (a) a two-pass approach
  (render to temp files, then compose), or (b) hold all `BufferedImage` objects
  in memory simultaneously.
- Script generation for figure composition is complex -- the Groovy script
  would need to hold all panels in memory simultaneously.

**Effort estimate:**
- New classes: 1 (`FigureComposer`, ~300 LOC)
- Modified classes: 2 (`BatchExportTask`, `ImageSelectionPane`)
- New UI controls: ~4 (checkbox, spinner, gap spinner, color picker)
- Estimated total LOC: ~500
- Test files: 1 new (`FigureComposerTest`)

**Cross-references:** F4 (split channels produce the panels to compose), F5 (done -- panel labels placed on composed figure), F20 (uniform dimensions are a prerequisite)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `BatchExportTask.java` | 119-206 (call method) | Main loop where per-image export happens; add post-compose step |
| `BatchExportTask.java` | 208-225 (exportRendered) | Would need variant that returns BufferedImage instead of writing |
| `RenderedImageExporter.java` | 282-338 (renderClassifierComposite) | Returns BufferedImage already -- could expose this directly |
| `ImageSelectionPane.java` | (Step 3 UI) | Add "Compose" section for grid layout controls |
| `ExportWizard.java` | 282-335 (startRenderedExport) | Wire up compose option into the batch task |

---

### F4. Split-Channel Export -- COMPLETED (v0.7.0)

**Priority:** P1 -- **IMPLEMENTED**
**Source:** QuickFigures (one-click split channels + merge figure)

**Description:**
For fluorescence images, automatically export individual channel panels
(each showing one channel in its pseudocolor or grayscale) plus a merged
composite panel. This is the most common figure format in fluorescence
microscopy publications.

**Implementation sketch:**
- New render mode `SPLIT_CHANNELS` in `RenderedExportConfig.RenderMode` or
  a separate boolean `splitChannels` flag.
- In `RenderedImageExporter`, for each image:
  (1) Read the full composite image using the existing `resolveDisplayServer()`.
  (2) For each visible channel, create a single-channel image rendered with
      its LUT (or grayscale). QuPath's `ChannelDisplayTransformServer` is
      already used in `resolveDisplayServer()` (line 426) -- it accepts a
      `List<ChannelDisplayInfo>` for selected channels. Create one server
      per channel by passing `List.of(singleChannel)`.
  (3) Create a merged composite with all visible channels.
  (4) Either export as separate files (channel1.png, channel2.png, merge.png)
      or compose into a single-row figure using `FigureComposer` (see F3).
- Channel names auto-derived from `ImageDisplay.selectedChannels()` which
  returns `ChannelDisplayInfo` objects with `getName()`.
- Scale bar on merge panel only (or on each -- user choice).
- The `resolveDisplayServer()` method (line 394-433) already creates an
  `ImageDisplay` and gets `display.selectedChannels()`. This is the exact
  entry point for split-channel iteration.

**Challenges:**
- QuPath's `ChannelDisplayTransformServer.createColorTransformServer()` accepts
  a list of `ChannelDisplayInfo` objects. For split channels, call it once per
  channel with a single-element list. This is the cleanest approach and reuses
  the existing display pipeline.
- Grayscale option: some journals prefer grayscale individual channels with
  colored merge. Need a toggle. For grayscale, could use a custom
  `SingleChannelDisplayInfo` that maps to grayscale LUT, or render in color
  and convert to grayscale via `BufferedImage` type conversion.
- Brightfield images don't have meaningful "channels" to split (they use
  stain vectors via color deconvolution). This mode should be fluorescence-only.
  Can detect via `imageData.getImageType()` -- if `BRIGHTFIELD_*`, skip or warn.
- Output naming: need convention like `{imageName}_Ch1_DAPI.png`, etc.
  The `buildOutputFilename()` method in `RenderedExportConfig` only handles
  single output names. Would need an overload or a channel-aware naming method.

**Effort estimate:**
- New fields in `RenderedExportConfig`: 2 (boolean splitChannels, boolean splitGrayscale)
- New methods in `RenderedImageExporter`: 1 major (~100 LOC `renderSplitChannels`)
- Modified methods: 1 (`resolveDisplayServer` refactored to expose per-channel wrapping)
- New UI controls: 2 (checkbox, grayscale toggle)
- Files touched: 4 (`RenderedExportConfig`, `RenderedImageExporter`, `RenderedConfigPane`, `RenderedScriptGenerator`)
- Estimated total LOC: ~250
- Test files: 1 new or extend existing

**Cross-references:** F3 (compose split channels into grid), F7 (display range matching critical for split-channel comparisons)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedImageExporter.java` | 394-433 (resolveDisplayServer) | Creates ImageDisplay + ChannelDisplayTransformServer -- split channels iterate here |
| `RenderedImageExporter.java` | 419-427 (display.selectedChannels) | Returns List<ChannelDisplayInfo> -- iterate to split |
| `RenderedExportConfig.java` | 20-27 (RenderMode enum) | Add SPLIT_CHANNELS or boolean flag |
| `RenderedExportConfig.java` | 233-239 (buildOutputFilename) | Needs channel-aware overload |
| `BatchExportTask.java` | 208-225 (exportRendered) | Add SPLIT_CHANNELS dispatch |
| `RenderedScriptGenerator.java` | 21-27 (generate dispatch) | Add split-channel script variant |

---

### F5. Panel Labels (A, B, C Lettering) -- COMPLETED (v0.5.0)

**Priority:** P1 -- **IMPLEMENTED**
**Source:** QuickFigures (Smart Label Sequence), BioVoxxel (Dimension Labeler
with ABC mode)

**Description:**
Add automatic panel letter labels (A, B, C, ...) to exported images. Essential
for multi-panel figures referenced in manuscript text as "Figure 1A", etc.
Configurable position, font size, color, and style.

**Implementation sketch:**
- New class `PanelLabelRenderer` following the `ScaleBarRenderer` pattern.
- Public API: `drawPanelLabel(Graphics2D g2d, int imageWidth, int imageHeight,
  String label, ScaleBarRenderer.Position position, int fontSize,
  boolean bold, Color color)`.
- Uses the same 8-direction outlined text technique from `ScaleBarRenderer.drawOutlinedText()`
  (line 223-236) and `ColorScaleBarRenderer.drawOutlinedText()` (line 224-237).
  **Note:** These two classes have duplicated `drawOutlinedText()` and
  `computeOutlineColor()` methods. Consider extracting to a shared
  `TextRenderUtils` class when adding `PanelLabelRenderer`.
- Configuration: add `showPanelLabel`, `panelLabelText` (or auto-increment),
  `panelLabelPosition`, `panelLabelFontSize`, `panelLabelBold`,
  `panelLabelColor` to `RenderedExportConfig` -- 6 new fields.
- For batch export, auto-increment: first image = "A", second = "B", etc.
  Or allow a user-specified starting letter.
  The `BatchExportTask.call()` method (line 131) iterates with index `i` --
  pass `i` to the renderer to compute the letter.
- Integration: call after scale bar drawing in all three composite methods
  and after `maybeDrawColorScaleBar()` in density map (topmost layer).
- Script generation: simple text drawing in Groovy, following the
  `emitScaleBarFunction()` pattern.

**Challenges:**
- When used with figure composition (F3), labels should be on the composed
  figure, not individual panels. Need to coordinate with FigureComposer.
- Position conflicts: panel label, scale bar, and color scale bar could all
  want the same corner. Need smart offset or user guidance. Current position
  enum has only 4 corners -- could add CENTER_TOP, CENTER_BOTTOM for labels.
- Auto-increment in batch requires knowing the export order, which is
  determined by the image selection list order in `BatchExportTask.entries`.

**Implementation (completed v0.5.0):**
- `TextRenderUtils.java` -- extracted shared text rendering from ScaleBarRenderer + ColorScaleBarRenderer
- `PanelLabelRenderer.java` -- stateless renderer with `drawPanelLabel()` + `labelForIndex()` helper
- `RenderedExportConfig.java` -- 5 new fields (showPanelLabel, panelLabelText, panelLabelPosition, panelLabelFontSize, panelLabelBold)
- `RenderedImageExporter.java` -- `maybeDrawPanelLabel()` + `resolvePanelLabel()` + panelIndex parameter
- `BatchExportTask.java` -- passes loop index as panelIndex
- `RenderedScriptGenerator.java` -- emitPanelLabelConfig/Function/Drawing in all 3 script variants
- `RenderedConfigPane.java` -- 5 UI controls with visibility toggling
- `QuietPreferences.java` -- 5 new preferences
- Tests: `PanelLabelRendererTest`, `TextRenderUtilsTest`, updated config + script tests

**Cross-references:** F3 (labels on composed figure), F11 (dimension labels similar technique), F13 (custom text similar technique)

---

### F6. SVG / Vector Export -- COMPLETED (v0.6.0)

**Priority:** P2 -- **IMPLEMENTED**
**Source:** BioVoxxel (SVG with editable overlays), QuickFigures (SVG, PDF, EPS)

**Description:**
Export figures as SVG vector graphics where overlays (annotations, scale bars,
labels, color scale bars) are editable vector elements rather than rasterized
pixels. The base image is embedded as a raster element. This allows
post-processing in Inkscape or Illustrator without quality loss on overlays.

**Implementation sketch:**
- New `SvgExporter` class using Apache Batik library (already proven in
  BioVoxxel's implementation).
- Image raster data embedded as base64 PNG within the SVG.
- Annotations converted to SVG `<path>` elements with fill/stroke colors.
  QuPath's `PathObject.getROI()` returns `ROI` objects with `getGeometry()`
  providing JTS `Geometry` -- can convert to SVG path data.
- Scale bar as SVG `<rect>` + `<text>` elements.
- Color scale bar as SVG `<rect>` gradient + `<text>` tick labels.
- Panel labels as SVG `<text>` elements.
- All elements in named SVG groups/layers for Inkscape organization.
- Add SVG to the `OutputFormat` enum or as a separate export action.
- Alternative to Batik: use Java's built-in XML APIs (DOM) to construct SVG
  directly. This avoids the Batik dependency entirely but requires manual
  SVG element construction. For the limited set of elements needed (rect,
  path, text, image), this is feasible and keeps the zero-dependency stance.

**Challenges:**
- **Dependency**: Apache Batik is a large library (~5MB). `build.gradle.kts`
  (line 19-29) currently has zero external dependencies beyond QuPath/logging.
  Adding Batik would be the first external dependency. The no-Batik approach
  (manual SVG DOM) is preferred to maintain the zero-dependency policy.
- **Complexity**: SVG DOM construction is verbose. Each overlay type (polygon,
  ellipse, line, text) needs a separate SVG element mapper.
- **Classifier overlays**: Pixel classification results are raster data, not
  vector shapes. They would need to be embedded as a second raster layer with
  opacity, losing the vector advantage.
- **Density map overlays**: Same issue -- colorized density is raster.
- **Best fit**: SVG export makes most sense for object overlay mode where
  annotations/detections are already vector geometries. Classifier and density
  map modes would still be mostly raster.
- **QuPath API note**: `HierarchyOverlay.paintOverlay()` (used in `paintObjects()`
  at line 663) renders directly to `Graphics2D`. For SVG, we would instead
  iterate `imageData.getHierarchy().getAnnotationObjects()` and convert
  each `PathObject.getROI().getGeometry()` to SVG path data.

**Implementation (completed v0.6.0):**
- Used JFreeSVG library (org.jfree.svg 5.0.6) instead of Apache Batik or manual SVG DOM -- lightweight, clean API via `SVGGraphics2D` that accepts standard `Graphics2D` drawing calls
- Added `SVG` to `OutputFormat` enum
- SVG available for rendered exports (classifier overlay, object overlay, density map overlay); base image embedded as raster, overlays as vector paths
- Scale bars, panel labels, and color scale bars render as SVG vector text/shapes
- JFreeSVG is the first (and only) external dependency beyond QuPath

**Cross-references:** F14 (metadata embedding in SVG), F9 (arrow overlays as SVG elements)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `OutputFormat.java` | 6-28 (enum) | Add SVG format entry |
| `RenderedImageExporter.java` | 638-668 (paintObjects) | Uses HierarchyOverlay -- SVG needs direct ROI iteration instead |
| `build.gradle.kts` | 19-29 (dependencies) | JFreeSVG 5.0.6 added as first external dependency (v0.6.0) |
| `BatchExportTask.java` | 147-152 (switch on category) | Add SVG dispatch path |

---

### F7. Display Range Matching Across Images -- COMPLETED (v0.7.0)

**Priority:** P2 -- **IMPLEMENTED**
**Source:** QuickFigures (Match channels), BioVoxxel (5D Contrast Optimizer)

**Description:**
Ensure all images in a batch export use identical brightness/contrast display
ranges per channel. Critical for valid visual comparison of fluorescence
intensities across conditions. Currently QuIET supports "Current Viewer
Settings" (uniform but requires manual setup) or "Per-Image Saved" (each
image has its own settings). Neither automatically computes a global optimal
range across all selected images.

**Implementation sketch:**
- Add a new display settings mode: `GLOBAL_MATCHED` to
  `RenderedExportConfig.DisplaySettingsMode` (line 33-42).
- Add `matchedDisplayPercentile` field (double, default 0.1) to
  `RenderedExportConfig`.
- Before export, scan all selected images in `BatchExportTask`:
  (1) For each channel, read a low-resolution version (high downsample, e.g., 32x).
  (2) Compute global min/max (or percentile-based) across all images.
  (3) Build an `ImageDisplaySettings` from the computed ranges.
  (4) Apply as `capturedDisplaySettings` to the config.
- The scanning pass happens before the main export loop in `BatchExportTask.call()`
  (before line 131). Could be a new private method `computeGlobalDisplayRange()`.
- The existing `resolveDisplayServer()` mechanism (line 394-433) already handles
  applying `capturedDisplaySettings` -- the GLOBAL_MATCHED mode would work
  identically to CURRENT_VIEWER mode once the settings are computed.
- Configuration: add `matchedDisplayPercentile` (e.g., 0.1% saturation,
  like BioVoxxel's default of 0.05%).

**Challenges:**
- **Performance**: scanning all images before export adds a pre-processing
  pass. For large WSI projects this could be slow. Mitigate by using high
  downsample (e.g., 32x or higher) for the scan pass. Each image only needs
  a single `readRegion()` at low resolution.
- **Memory**: need to hold per-channel min/max statistics, not full images.
  A running min/max accumulator per channel is tiny.
- **Channel mismatch**: images in the same project might have different
  channel configurations. `BatchExportTask` already computes
  `channelSignature()` (line 230-238) per image. Use this to group images
  and match within groups.
- **Script generation**: the generated Groovy script would need a two-pass
  approach (scan then export), making it significantly more complex.
- **QuPath API**: `ImageServer.readRegion()` returns pixel data; computing
  percentiles requires iterating all pixels in the low-res version. For
  float/16-bit data, need to use raster's `getSampleDouble()`.

**Effort estimate:**
- New enum value in `DisplaySettingsMode`: 1
- New field in `RenderedExportConfig`: 1 (matchedDisplayPercentile)
- New method in `BatchExportTask`: 1 (`computeGlobalDisplayRange`, ~80 LOC)
- New UI control: 1 (percentile spinner, visible when GLOBAL_MATCHED selected)
- Script generator: significant (~100 LOC for two-pass script)
- Files touched: 5 (`RenderedExportConfig`, `BatchExportTask`, `RenderedConfigPane`, `RenderedScriptGenerator`, `strings.properties`)
- Estimated total LOC: ~250
- Test files: extend existing

**Cross-references:** F18 (batch contrast preview uses same scan logic), F4 (split channels need matched ranges)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedExportConfig.java` | 33-42 (DisplaySettingsMode) | Add GLOBAL_MATCHED value |
| `RenderedImageExporter.java` | 394-433 (resolveDisplayServer) | GLOBAL_MATCHED uses same path as CURRENT_VIEWER once settings computed |
| `BatchExportTask.java` | 119-131 (before main loop) | Insert scanning pass here |
| `BatchExportTask.java` | 230-238 (channelSignature) | Reuse for grouping channels |
| `RenderedConfigPane.java` | 150-197 (displaySettingsCombo) | Add GLOBAL_MATCHED to dropdown |
| `strings.properties` | 48-54 (display settings labels) | Add label for new mode |

---

### F8. DPI / Resolution Control -- COMPLETED (v0.7.0)

**Priority:** P2
**Source:** QuickFigures (Re-Set Pixel Density), journal requirements

**Description:**
Specify target DPI (dots per inch) for exported images. Many journals require
300 DPI minimum for figures. Currently QuIET exports at the native resolution
determined by the downsample factor; users must mentally calculate what
downsample produces the desired DPI for their image.

**Implementation sketch:**
- Add a `targetDpi` field to `RenderedExportConfig` (int, 0 = disabled/auto).
- Compute the required downsample from: `downsample = imagePixelSize /
  (25400.0 / targetDpi)` where imagePixelSize is in microns and 25400 is
  microns per inch.
- Two integration options:
  (a) Compute downsample before export in `BatchExportTask` and override the
      config's downsample. Requires per-image calculation since pixel sizes vary.
  (b) Compute in `RenderedImageExporter` at render time using
      `imageData.getServer().getPixelCalibration().getAveragedPixelSizeMicrons()`.
      Option (b) is simpler and handles per-image pixel size differences.
- UI: radio button group "Resolution control": "By downsample" (existing) vs
  "By target DPI" (new). When DPI is selected, show a spinner (72, 150, 300,
  600) and auto-compute the downsample. Insert near the existing downsample
  combo (RenderedConfigPane line 243-263).
- Display the resulting pixel dimensions and file size estimate using the
  current image's dimensions and pixel calibration.
- For TIFF/PNG output, set the DPI metadata in the image header. Java's
  `ImageIO` can write DPI via `IIOMetadata` for both PNG (pHYs chunk)
  and TIFF (ResolutionUnit + XResolution/YResolution tags).

**Challenges:**
- **Pixel size dependency**: DPI calculation requires calibrated images. If
  pixel size is unknown, fall back to downsample-only mode with warning.
  `RenderedImageExporter.maybeDrawScaleBar()` already checks
  `cal.hasPixelSizeMicrons()` -- same guard needed here.
- **Image-specific**: different images in a project may have different pixel
  sizes (e.g., 20x vs 40x scans). The same DPI target produces different
  downsamples per image. The batch loop needs per-image downsample calculation.
  This means the config's `downsample` field becomes a computed value rather
  than a fixed input.
- **DPI metadata writing**: `ImageWriterTools.writeImage()` (used in all
  exporters) may not expose metadata writing. Would need to use Java's
  `ImageIO` directly with custom `IIOMetadata`. This is a moderate effort
  for TIFF (straightforward via `TIFFField`) and PNG (via `pHYs` chunk).
- **Script generation**: the script needs the DPI-to-downsample calculation
  inline, plus pixel calibration access per image.

**Effort estimate:**
- New fields in `RenderedExportConfig`: 1 (targetDpi)
- New helper method in `RenderedImageExporter`: 1 (computeDownsampleForDpi, ~15 LOC)
- New UI controls: 2-3 (radio group, DPI spinner, dimension display label)
- Optional: DPI metadata writer helper (~60 LOC for PNG + TIFF)
- Files touched: 4-5
- Estimated total LOC: ~150-250
- Test files: extend existing

**Cross-references:** F20 (matched dimensions interact with DPI-based sizing)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedExportConfig.java` | 50 (downsample field) | targetDpi field nearby; downsample computed from DPI at render time |
| `RenderedImageExporter.java` | 288-291 (downsample usage) | Override downsample with DPI-computed value per image |
| `RenderedImageExporter.java` | 443-448 (pixel calibration access) | Same calibration access needed for DPI calculation |
| `RenderedConfigPane.java` | 243-263 (downsample combo) | Replace/augment with DPI mode toggle |
| `RenderedImageExporter.java` | 85 (ImageWriterTools.writeImage) | May need direct ImageIO for DPI metadata |

---

### F9. Arrow / Shape Overlays on Export

**Priority:** P2
**Source:** QuickFigures (arrows, shapes, ROI display), BioVoxxel (overlay export)

**Description:**
Draw arrows, rectangles, circles, or other shape annotations on the exported
image to highlight specific features. Distinct from QuPath's detection/annotation
objects -- these are purely visual indicators added during export.

**Implementation sketch:**
- Leverage QuPath's existing annotation system: users create Point, Line, or
  Arrow annotations in QuPath. During export, render these as visual overlays.
- QuPath already has `PathAnnotationObject` with various ROI types including
  `LineROI`, `PointsROI`, `RectangleROI`, `EllipseROI` via `qupath.lib.roi.*`.
- The existing `paintObjects()` method in `RenderedImageExporter` (line 638-668)
  already renders all objects via `HierarchyOverlay.paintOverlay()`. The challenge
  is not rendering shapes (QuPath handles that) but adding arrowheads and
  custom styling specifically for "indicator" objects.
- For arrows specifically: detect annotations classified as "Arrow" or with
  a specific name prefix, and draw arrowhead geometry. After the standard
  `paintObjects()` call, iterate `imageData.getHierarchy().getAnnotationObjects()`,
  find those with "Arrow" class, get their `LineROI`, and draw arrowhead
  triangles at the endpoint.
- New class `ArrowRenderer` with method `drawArrowhead(Graphics2D g2d,
  double x1, double y1, double x2, double y2, Color color, int headSize)`.
- Alternative: add a dedicated "Indicators" layer in the export config that
  draws user-specified shapes from a simple definition (JSON or config).

**Challenges:**
- QuPath's `HierarchyOverlay` already renders all objects. The challenge is
  styling control -- making arrows look like arrows (with arrowheads) rather
  than line annotations. QuPath's line ROIs render as plain lines.
- QuPath's line ROIs (`qupath.lib.roi.LineROI`) have `getX1()`, `getY1()`,
  `getX2()`, `getY2()` -- perfect for arrowhead calculation.
- This overlaps with QuPath's existing annotation rendering. Need to clearly
  distinguish "export indicators" from "analysis annotations" in the UI.
  Could use a naming convention (e.g., "arrow:" prefix) or a dedicated
  classification.

**Effort estimate:**
- New classes: 1 (`ArrowRenderer`, ~80 LOC)
- Modified methods: 1 (add arrow post-processing after `paintObjects` calls)
- New UI controls: 0-2 (could be zero if using classification-based detection)
- Files touched: 2-3
- Estimated total LOC: ~120
- Test files: 1 new

**Cross-references:** F6 (SVG export makes arrows truly vector)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedImageExporter.java` | 638-668 (paintObjects) | Insert arrow post-processing after this method |
| `RenderedImageExporter.java` | 325-331 (annotation overlay in classifier composite) | Where annotations are drawn in classifier mode |
| `RenderedImageExporter.java` | 372-379 (annotation overlay in object composite) | Where annotations are drawn in object mode |
| `RenderedImageExporter.java` | 517-523 (annotation overlay in density composite) | Where annotations are drawn in density mode |

---

### F10. Color Deficient Vision (CDV) Simulation

**Priority:** P2
**Source:** BioVoxxel (CDV Test)

**Description:**
Simulate how the exported image appears to viewers with protanopia,
deuteranopia, or tritanopia (the three main forms of color blindness).
Helps researchers verify their color scheme is accessible before publication.

**Implementation sketch:**
- New class `ColorBlindSimulator` in the `export` package.
- Apply color transformation matrices to the rendered BufferedImage:
  - Protanopia: standard Brettel/Vienot/Machado matrix
  - Deuteranopia: standard matrix
  - Tritanopia: standard matrix
- The transformation is a simple per-pixel RGB matrix multiplication on
  `BufferedImage.TYPE_INT_RGB` data. Access pixels via `getRGB()`/`setRGB()`
  or faster via `Raster.getDataElements()`.
- Integration options:
  (a) Preview-only: button in the preview panel that toggles CDV simulation.
      The preview is already rendered via `RenderedImageExporter.renderPreview()`
      (line 185-267). Add a post-processing step that applies the CDV matrix
      to the returned `BufferedImage` before displaying.
  (b) Export option: export additional CDV-simulated versions alongside the
      normal export for review.
- The preview window is shown in `RenderedConfigPane.showPreviewWindow()`
  (line 905-920). Add a toggle button or dropdown to the preview stage.

**Challenges:**
- **Multiple simulation algorithms**: Brettel et al. (1997), Vienot et al.
  (1999), and Machado et al. (2009) produce slightly different results. Need
  to pick one and document it. Machado is most modern and parameterizable.
- **Preview integration**: the preview panel currently shows one image in a
  simple `StackPane`. CDV simulation could show a side-by-side or tabbed view.
- **Value judgment**: this is a QA tool, not an export feature. Might belong
  in a separate "Verify" step of the wizard rather than in the export config.
- **LUT choice guidance**: ideally, combine with colormap selection (F17) to
  recommend colorblind-safe colormaps (viridis, cividis). Could add a
  "colorblind safe" badge to LUT options.

**Effort estimate:**
- New classes: 1 (`ColorBlindSimulator`, ~120 LOC with 3 matrix constants)
- Modified classes: 1 (`RenderedConfigPane` preview window)
- New UI controls: 1-2 (toggle button in preview window)
- Files touched: 2-3
- Estimated total LOC: ~180
- Test files: 1 new

**Cross-references:** F17 (LUT preview with CDV badges)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedImageExporter.java` | 185-267 (renderPreview) | Apply CDV matrix to returned BufferedImage |
| `RenderedConfigPane.java` | 905-920 (showPreviewWindow) | Add CDV toggle to preview stage |
| `RenderedConfigPane.java` | 886-903 (preview thread) | Apply CDV after `renderPreview()` returns |

---

### F11. Dimension / Timestamp Labels -- COMPLETED (v0.7.0, as Info Label)

**Priority:** P2
**Source:** BioVoxxel (Dimension Labeler)

**Description:**
Add per-image text stamps showing metadata: timestamps, Z-slice position,
channel name, sample ID, or classification label. Useful for time-lapse
exports, Z-stack montages, or batch exports where each image needs
identifying text.

**Implementation sketch:**
- New class `DimensionLabelRenderer` following the ScaleBarRenderer pattern.
- Configurable label template with placeholders: `{imageName}`, `{sampleId}`,
  `{pixelSize}`, `{classifier}`, `{date}`, or arbitrary fixed text.
- Position in any of the 4 corners (reuse `ScaleBarRenderer.Position` enum).
- 8-direction outlined text for visibility (use `TextRenderUtils.drawOutlinedText()`).
- Configuration: add `showDimensionLabel`, `dimensionLabelTemplate`,
  `dimensionLabelPosition`, `dimensionLabelFontSize`, `dimensionLabelBold`
  to `RenderedExportConfig` -- 5 new fields.
- Integration in `RenderedImageExporter`: new `maybeDrawDimensionLabel()`
  method called after scale bar drawing. The method receives `imageData`
  (for metadata access), the entry name, and the config.
- Template resolution: use simple `String.replace()` for each placeholder.
  Access image metadata via `imageData.getServer().getMetadata()` and
  `imageData.getServer().getMetadata().getName()`.

**Challenges:**
- **Template parsing**: need a simple placeholder replacement engine. Avoid
  over-engineering -- a few fixed placeholders with `String.replace()` is
  sufficient.
- **Position conflicts**: with scale bar, color scale bar, panel label, and
  now dimension label, users could place too many overlays in the same corner.
  Consider a conflict-detection system or auto-stacking. Alternatively,
  document that users should use different corners for each overlay.
- **Metadata availability**: not all images have all metadata fields. Need
  graceful fallback (empty string or "N/A") for missing fields.
- **Per-image variability**: unlike other renderers, the dimension label
  content changes per image (e.g., image name). The renderer needs the
  `entryName` or `ImageData` at draw time, not just config.

**Effort estimate:**
- New classes: 1 (`DimensionLabelRenderer`, ~100 LOC)
- New fields in `RenderedExportConfig`: 5
- New UI controls: ~4 (checkbox, template text field, position combo, font size)
- Script generator: 1 new function (~30 LOC)
- Files touched: 5
- Estimated total LOC: ~250
- Test files: 1 new

**Cross-references:** F5 (panel labels similar technique), F13 (custom text overlaps)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `ScaleBarRenderer.java` | (drawScaleBar) | Pattern for position-aware overlay drawing |
| `TextRenderUtils.java` | (drawOutlinedText) | Shared text rendering for label text |
| `PanelLabelRenderer.java` | (entire class) | Similar renderer to follow as template |
| `RenderedImageExporter.java` | (maybeDrawScaleBar) | Pattern for new maybeDrawDimensionLabel |
| `RenderedImageExporter.java` | (exportWithClassifier) | entryName parameter available for label text |

---

### F12. Crop and Rotation Before Export

**Priority:** P2
**Source:** QuickFigures (Crop Dialog with rotation), BioVoxxel (inset rotation)

**Description:**
Allow users to define a crop region and rotation angle for the exported image,
independent of QuPath annotations. Useful for straightening slightly tilted
tissue sections or focusing on a specific tissue region.

**Implementation sketch:**
- Add `cropRegion` (x, y, w, h in full-resolution pixels) and `rotationAngle`
  (degrees) to `RenderedExportConfig`.
- In `RenderedImageExporter`, apply crop by adjusting the `RegionRequest`
  parameters (currently hardcoded to `0, 0, width, height` in all three
  composite methods), then apply rotation via `AffineTransform` on the
  resulting BufferedImage.
- Use bicubic interpolation for rotation (`RenderingHints.VALUE_INTERPOLATION_BICUBIC`).
  The existing rendering already uses bilinear (`VALUE_INTERPOLATION_BILINEAR`
  at lines 311-313, 366-368, 503-505).
- UI: could use QuPath's viewer ROI tool to define the crop region, or
  numeric fields in the config pane.

**Challenges:**
- **Per-image vs. global crop**: in batch export, different images likely need
  different crop regions. Per-image crop definitions add significant UI
  complexity.
- **Rotation and overlays**: rotating the base image means all overlay
  coordinates (annotations, scale bar position) must also be transformed.
  Scale bar physical length is unaffected but position calculation changes.
  The `paintObjects()` method uses `gCopy.scale()` -- would also need
  `gCopy.rotate()` to match.
- **Rotation + classification overlays**: pixel classifier output would need
  to be rotated identically to the base image. The classifier server's
  `readRegion()` returns unrotated data.
- **Simpler alternative**: F1 (annotation-based region export) handles
  cropping via annotation bounding boxes. Rotation is rarely needed and adds
  significant complexity. Consider implementing F1 first and deferring rotation.

**Effort estimate:**
- New fields in `RenderedExportConfig`: 5 (cropX, cropY, cropW, cropH, rotationAngle)
- Modified methods: 3 (all composite render methods)
- New UI controls: ~5
- Estimated total LOC: ~200
- Test files: extend existing

**Cross-references:** F1 (annotation-based cropping is simpler alternative)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedImageExporter.java` | 295-297 (RegionRequest in classifier) | Change to use crop region |
| `RenderedImageExporter.java` | 355-357 (RegionRequest in object) | Same |
| `RenderedImageExporter.java` | 474-477 (RegionRequest in density) | Same |
| `RenderedImageExporter.java` | 311-313 (bilinear interpolation) | Use bicubic for rotation |
| `RenderedImageExporter.java` | 654-655 (gCopy.scale in paintObjects) | Add gCopy.rotate() |

---

### F13. Custom Text / Label Overlays -- PARTIALLY COMPLETED (v0.7.0, as Info Label)

**Priority:** P2
**Source:** QuickFigures (complex text items, rich formatting)

**Description:**
Add arbitrary text labels to exported images beyond the auto-generated
scale bar and panel labels. For example, adding "Control" / "Treatment"
labels, pointing out specific structures, or adding figure captions
directly on the image.

**Implementation sketch:**
- Extend the "Dimension Label" feature (F11) to support multiple text items.
- Each text item: content, position (x, y as fractions or absolute), font
  size, bold, color.
- Stored as a list in `RenderedExportConfig`. This is the first list-typed
  config field -- the Builder would need a `addTextLabel(TextLabel label)`
  method. Alternatively, use a serialized string format like
  `"Control|UPPER_LEFT|16|true|#FFFFFF"`.
- UI: simple text entry field with position dropdown, or an "add label"
  button that appends to a list.
- Use the same outlined text rendering as ScaleBarRenderer.

**Challenges:**
- **Per-image text**: in batch export, the same text applies to all images.
  Per-image custom text requires either annotation-based text (reading QuPath
  annotation names) or a mapping table.
- **UI complexity**: a full text editor with positioning is a significant UI
  investment. Keep it simple: one or two fixed text fields with corner
  positions, not a freeform canvas.
- **Overlap with F5 and F11**: panel labels, dimension labels, and custom
  text are all "text on the image." Consider a unified "Labels" section in
  the config rather than three separate features. A single `TextOverlayConfig`
  class with a list of label definitions could serve all three use cases.
- **Config serialization**: the current config classes use only primitive types
  and simple strings. A list of text labels adds complexity to JSON
  serialization for script generation (`RenderedScriptGenerator` uses Gson
  for display settings at line 15).

**Effort estimate:**
- If built on F11: ~100 additional LOC (extend DimensionLabelRenderer to list)
- If standalone: ~200 LOC
- New UI controls: ~6 (text field, position, font size, bold, color, add/remove buttons)
- Files touched: 3-4
- Estimated total LOC: ~200-300

**Cross-references:** F5 (panel labels), F11 (dimension labels), F6 (SVG makes text editable post-export)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `TextRenderUtils.java` | (drawOutlinedText) | Shared text rendering for custom text |
| `PanelLabelRenderer.java` | (entire class) | Similar per-position renderer pattern |
| `RenderedExportConfig.java` | (constructor field copying) | First list-type field in config |
| `RenderedScriptGenerator.java` | (Gson) | Could use for serializing text label list |

---

### F14. Metadata Embedding in Export

**Priority:** P3
**Source:** BioVoxxel (Meta-D-Rex, SVG metadata embedding)

**Description:**
Embed processing metadata directly into exported image files (TIFF metadata
fields, PNG text chunks, or SVG description elements). Records what settings
were used to create the export for reproducibility.

**Implementation sketch:**
- For TIFF exports: use `IIOMetadata` to write custom TIFF tags or
  ImageDescription fields containing export configuration.
- For PNG exports: use PNG text chunks (tEXt or iTXt) to embed JSON
  configuration.
- QuIET already writes `export_info.txt` sidecar files via
  `ExportMetadataWriter` (line 114-158). This feature would put the same
  information inside the image file itself.
- Could also embed the generated Groovy script as metadata.
- Implementation: create an `ImageMetadataEmbedder` utility class that
  wraps `ImageIO.write()` with custom `IIOMetadata`. Replace the
  `ImageWriterTools.writeImage()` calls in `RenderedImageExporter`
  (lines 85, 123, 161) with the metadata-aware writer when embedding
  is enabled.

**Challenges:**
- **QuPath's `ImageWriterTools.writeImage()`** is a convenience wrapper that
  does not expose metadata writing. Would need to use Java's `ImageIO` directly
  with `ImageWriter` + `IIOImage` + `IIOMetadata`. This is well-documented
  Java API but verbose.
- **Value**: the sidecar `.txt` files already provide this information.
  Embedded metadata is more robust (can't be separated from the image) but
  less visible to users.
- **Format-specific**: TIFF metadata uses IFD tags; PNG uses text chunks;
  JPEG uses EXIF/XMP. Each format needs a different approach.
- **Complexity vs. benefit**: moderate implementation effort for marginal
  benefit over existing sidecar files.

**Effort estimate:**
- New classes: 1 (`ImageMetadataEmbedder`, ~200 LOC for PNG + TIFF)
- Modified files: 1 (`RenderedImageExporter` -- replace write calls)
- New UI controls: 1 (checkbox "Embed metadata")
- Estimated total LOC: ~250
- Test files: 1 new

**Cross-references:** F6 (SVG metadata embedding is trivial -- just add XML elements)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `ExportMetadataWriter.java` | 114-158 (writeExportInfo) | Existing metadata content to embed |
| `RenderedImageExporter.java` | 85 (ImageWriterTools.writeImage) | Replace with metadata-aware writer |
| `RenderedImageExporter.java` | 123 (second writeImage call) | Same |
| `RenderedImageExporter.java` | 161 (third writeImage call) | Same |

---

### F15. Interactive Channel Cycling (for HTML/SVG output)

**Priority:** P3
**Source:** BioVoxxel (Interactive SVG with JavaScript channel cycling)

**Description:**
Export an interactive HTML or SVG file where clicking cycles through
individual channels and channel combinations. Useful for supplementary
materials or online figure viewers.

**Implementation sketch:**
- Export each channel as a separate PNG (or embedded in SVG).
- Generate HTML/SVG with embedded JavaScript that toggles CSS
  `display: none/block` on channel layers.
- Use CSS `mix-blend-mode: screen` for fluorescence compositing.
- Could be a separate export format alongside PNG/TIFF/JPEG.
- The channel iteration logic from F4 (split-channel) provides the
  per-channel images. This feature adds the HTML/JS wrapper.

**Challenges:**
- **Scope creep**: this is fundamentally different from static image export.
  It's an interactive web artifact, not an image file.
- **Browser compatibility**: CSS blend modes work in modern browsers but
  may not render identically to Java2D compositing.
- **File size**: embedding multiple full-resolution channel images in one
  HTML/SVG produces very large files.
- **Limited audience**: most journal submissions require static figures.
  Interactive figures are mainly for online supplementary materials.

**Effort estimate:**
- New classes: 1 (`InteractiveHtmlExporter`, ~300 LOC)
- Depends on: F4 (split-channel for per-channel images)
- New UI controls: 1 (format option)
- Estimated total LOC: ~350
- Test files: 1 new

**Cross-references:** F4 (depends on split-channel rendering), F6 (SVG as base format)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `OutputFormat.java` | 6-28 | Add HTML or INTERACTIVE_SVG format |
| `RenderedImageExporter.java` | 394-433 (resolveDisplayServer) | Per-channel iteration for channel images |

---

### F16. Figure Templates / Style Presets

**Priority:** P3
**Source:** QuickFigures (Figure Format templates)

**Description:**
Save and load complete export configuration presets (font sizes, scale bar
style, label positions, colors, layout settings) as named templates.
Ensures consistent figure style across a manuscript or lab.

**Implementation sketch:**
- Serialize `RenderedExportConfig` (and future figure composition settings)
  to JSON files stored in the QuPath project or user directory.
- UI: "Save as Template" / "Load Template" buttons in the wizard.
- Template files are human-readable and shareable.
- Gson is already available (from QuPath, used in `RenderedScriptGenerator`
  line 15). Use it to serialize/deserialize configs.
- Store templates in `<project>/exports/templates/` or
  `~/.qupath/quiet/templates/`.

**Challenges:**
- **Already partially implemented**: `QuietPreferences` already persists all
  settings across sessions via Java Preferences API. Templates add the ability
  to have *multiple* named presets and share them.
- **Scope**: a template system is useful but not urgent. The existing
  preferences system covers the single-user case well.
- **Sharing**: templates would need to handle path-dependent settings
  (output directories) gracefully -- exclude them or make them relative.
- **Config immutability**: `RenderedExportConfig` is immutable with a private
  constructor. Deserialization would need to go through the Builder, which
  validates fields. Need a `fromJson()` factory method.

**Effort estimate:**
- New classes: 1 (`TemplateManager`, ~150 LOC)
- Modified classes: 1 (`ExportWizard` -- add template buttons)
- New UI controls: 2 (Save/Load buttons)
- Estimated total LOC: ~200
- Test files: 1 new

**Cross-references:** F3 (compose settings are part of the template)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `RenderedScriptGenerator.java` | 15 (Gson) | Reuse for JSON serialization |
| `RenderedExportConfig.java` | 244-430 (Builder) | Deserialization target |
| `QuietPreferences.java` | (entire file) | Existing persistence mechanism |
| `ExportWizard.java` | 118-152 (buildNavigation) | Add template buttons to nav bar |

---

### F17. LUT Preview / Visual Colormap Selector -- COMPLETED (v0.5.0)

**Priority:** P2 -- **IMPLEMENTED**
**Source:** BioVoxxel (LUT Channels Tool with visual buttons),
QuickFigures (channel color visualization)

**Description:**
Show colormap/LUT options as visual gradient swatches rather than text names
in the dropdown. Users can see what "Viridis" vs "Magma" looks like before
selecting. Critical for density map overlay mode where LUT choice directly
affects the figure.

**Implementation sketch:**
- Custom `ListCell` renderer for the `colormapCombo` ComboBox in
  RenderedConfigPane (currently a plain text ComboBox at line 371-374).
- For each colormap name, render a small horizontal gradient (e.g., 100x16px)
  by calling `colorMap.getColor(value, 0.0, 1.0)` across the range and
  drawing into a `WritableImage`.
- Display the gradient swatch beside the text name in the dropdown.
- Cache the swatches -- there are ~10-20 colormaps from
  `ColorMaps.getColorMaps()`, each swatch is ~6.4 KB (100x16x4 bytes).
- The colormap lookup already exists in `RenderedImageExporter.resolveColorMap()`
  (line 564-581). Use the same `ColorMaps.getColorMaps()` API.
- For the cell factory: `colormapCombo.setCellFactory(...)` with a custom
  `ListCell<String>` that creates an `HBox(imageView, label)`.
- The packed RGB int from `colorMap.getColor()` can be converted to JavaFX
  `Color` via `Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)`.

**Challenges:**
- **Dropdown width**: gradient swatches widen the dropdown. Need to balance
  swatch size with UI compactness. A 60x12px swatch is sufficient.
- **Low risk**: this is a pure UI enhancement with no export logic changes.
  Good candidate for early implementation.
- **Button cell**: need to set both `setCellFactory()` and `setButtonCell()`
  so the selected item also shows the swatch, not just the dropdown items.

**Implementation (completed v0.5.0):**
- Added `createColormapCell()` helper method to `RenderedConfigPane` returning a custom `ListCell<String>` that renders a 60x12px `WritableImage` gradient swatch alongside the colormap name
- Applied via `colormapCombo.setCellFactory()` and `colormapCombo.setButtonCell()` so both the dropdown and the selected display show gradient previews
- Uses QuPath's `ColorMaps.getColorMaps()` API to look up each colormap and sample colors across the 0.0-1.0 range

**Cross-references:** F10 (CDV badges on LUT swatches)

---

### F18. Batch Contrast / Display Settings Preview

**Priority:** P3
**Source:** BioVoxxel (5D Contrast Optimizer with print limits)

**Description:**
Before export, show a summary of the display settings that will be applied
to each image, including min/max per channel and any clipping warnings.
Helps users verify that their brightness/contrast settings are appropriate
before committing to a potentially long batch export.

**Implementation sketch:**
- Add a "Review Settings" button or auto-expanding panel in the wizard's
  image selection step (Step 3, `ImageSelectionPane`).
- For each selected image, show: image name, per-channel display range
  (min-max), pixel saturation percentage.
- Flag images with potential issues: saturated channels, very different
  ranges from the group, missing calibration.
- This is purely informational -- no export logic changes.
- Access display settings per image via `ImageDisplay.create(imageData)` and
  then `display.selectedChannels()` to get `ChannelDisplayInfo` with
  `getMinDisplay()` / `getMaxDisplay()`.

**Challenges:**
- **Performance**: reading display settings for many images requires opening
  each image's metadata via `entry.readImageData()`. For large projects
  (100+ images) this could be slow. Use a background thread with progress
  indicator.
- **UI space**: the image selection list (`ImageSelectionPane`) is already
  dense with image names and checkboxes. Adding per-image metadata would
  clutter it. Consider a separate popup or expandable details section.
- **BatchExportTask dependency**: the scan logic is similar to F7 (global
  matched display). If both features are implemented, share the scanning code.

**Effort estimate:**
- New UI panel: 1 (popup dialog, ~200 LOC)
- Modified class: 1 (`ImageSelectionPane` -- add Review button)
- Background scan task: ~100 LOC
- Estimated total LOC: ~350
- Test files: 0 (UI-only)

**Cross-references:** F7 (shares scanning logic)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `ImageSelectionPane.java` | (Step 3 panel) | Add "Review Settings" button |
| `RenderedImageExporter.java` | 394-433 (resolveDisplayServer) | Same ImageDisplay.create() pattern for reading settings |
| `ExportWizard.java` | 168-169 (imageSelectionPane init) | Wire up new button handler |

---

### F19. Outline / Contour Mask Export

**Priority:** P2
**Source:** QuickFigures (ROI overlay display with outlines)

**Description:**
Export masks showing only the outlines/contours of objects rather than
filled regions. Useful for overlaying on the original image in external
software (Fiji, Photoshop) or for publications showing object boundaries.

**Implementation sketch:**
- Add a new mask type `CONTOUR` to `MaskExportConfig.MaskType` (line 17-28).
- In `MaskImageExporter.buildLabelServer()` (line 78-152), the `CONTOUR` case
  cannot use `LabeledImageServer` directly because it only supports filled
  regions. Two approaches:
  (a) **Post-processing**: Build a filled mask using the existing `LabeledImageServer`,
      then apply edge detection (e.g., Sobel or simple erosion-subtraction:
      `original - eroded = contour`). Use Java2D `BufferedImageOp` or manual
      raster processing.
  (b) **Custom rendering**: Bypass `LabeledImageServer`. Instead, iterate
      `imageData.getHierarchy().getAnnotationObjects()`, get each ROI's
      `Shape` via `RoiTools.getShape(roi)`, and draw with
      `Graphics2D.draw(Shape)` using `BasicStroke` at the desired line width.
      This is cleaner and avoids the fill-then-erode hack.
- Approach (b) is preferred. Create a new method `buildContourImage()` in
  `MaskImageExporter` that uses Java2D shape drawing instead of
  `LabeledImageServer`.
- Contour color: same options as existing masks (binary white-on-black,
  per-class colored, grayscale labels).
- Line width: configurable, with downsample-aware scaling. A 2px contour at
  downsample 4 needs `BasicStroke(2 * downsample)` to maintain visual width.

**Challenges:**
- **LabeledImageServer limitation**: QuPath's `LabeledImageServer` only
  generates filled regions via `builder.addLabel()`. There is no built-in
  "outline only" mode. Custom rendering is required.
- **Line width at different downsamples**: a 1px contour at 1x downsample
  is very thin; at 8x downsample it may be invisible. Need downsample-aware
  line width. Add a `contourLineWidth` field to `MaskExportConfig`.
- **Already in roadmap**: the QuIET README mentions "Contour/outline mask
  export" as a planned feature.
- **Script generation**: the `MaskScriptGenerator` currently generates scripts
  using `LabeledImageServer.Builder`. The contour approach needs custom drawing
  code, making the script significantly different from other mask types.

**Effort estimate:**
- New enum value: 1 (MaskType.CONTOUR)
- New field in `MaskExportConfig`: 1 (contourLineWidth)
- New method in `MaskImageExporter`: 1 (`buildContourImage`, ~80 LOC)
- Modified: `buildLabelServer` dispatch (add CONTOUR case)
- Script generator changes: moderate (new script variant for contour)
- Files touched: 4 (`MaskExportConfig`, `MaskImageExporter`, `MaskConfigPane`, `MaskScriptGenerator`)
- Estimated total LOC: ~200
- Test files: 1 new or extend existing

**Cross-references:** F9 (arrow overlays on export images, not masks)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `MaskExportConfig.java` | 17-28 (MaskType enum) | Add CONTOUR value |
| `MaskImageExporter.java` | 78-152 (buildLabelServer) | Add CONTOUR dispatch -- uses custom rendering instead of LabeledImageServer |
| `MaskImageExporter.java` | 39-73 (exportMask) | May need alternate path for contour (custom rendering instead of server.readRegion) |
| `MaskConfigPane.java` | (mask type combo) | Add CONTOUR to dropdown |
| `MaskScriptGenerator.java` | (entire file) | Add contour script variant |

---

### F20. Export with Matched Panel Dimensions

**Priority:** P2
**Source:** QuickFigures (uniform panel sizing in grid layouts)

**Description:**
When exporting multiple images, ensure all outputs have identical pixel
dimensions by padding or cropping to a uniform size. Essential for
creating consistent multi-panel figures without post-processing.

**Implementation sketch:**
- Add `uniformDimensions` boolean and `targetWidth` / `targetHeight` fields
  to `RenderedExportConfig`.
- Pre-scan all selected images in `BatchExportTask` to determine the maximum
  (or median) dimensions at the export downsample. Similar pre-scan step as F7.
  The scan reads `entry.readImageData().getServer().getWidth()` and
  `getServer().getHeight()` -- metadata only, very fast.
- After rendering each image, pad or center-crop to the target dimensions:
  - **Pad**: create a `targetWidth x targetHeight` BufferedImage with background
    color, draw the rendered image centered on it.
  - **Crop**: use `BufferedImage.getSubimage()` to extract the center region.
- Alternative: specify exact target dimensions manually and scale all images
  to fit using `Graphics2D.drawImage(img, 0, 0, targetW, targetH, null)`.

**Challenges:**
- **Aspect ratio mismatch**: images with different aspect ratios can't be
  uniformly sized without distortion, cropping, or padding. Need to pick a
  strategy and make it clear in the UI.
- **Pre-scan cost**: determining dimensions of all images before export
  requires reading metadata for each. Usually fast (metadata only, no pixel
  reading) but adds a batch pre-processing step. Similar to F7's pre-scan.
- **Interacts with F3**: if figure composition (F3) is implemented, uniform
  sizing becomes a property of the grid layout rather than individual exports.
  `FigureComposer` would handle uniform sizing internally.

**Effort estimate:**
- New fields in `RenderedExportConfig`: 3 (boolean + int + int)
- New pre-scan in `BatchExportTask`: ~40 LOC
- Post-processing in `RenderedImageExporter`: ~30 LOC (pad/crop helper)
- New UI controls: 3 (checkbox, width spinner, height spinner)
- Files touched: 4
- Estimated total LOC: ~150

**Cross-references:** F3 (FigureComposer handles this internally), F8 (DPI control affects resulting dimensions)

### Code References
| File | Lines | Relevance |
|------|-------|-----------|
| `BatchExportTask.java` | 119-131 (before main loop) | Insert dimension scan here |
| `RenderedImageExporter.java` | 306-308 (TYPE_INT_RGB result creation) | Apply pad/crop after rendering |
| `RenderedExportConfig.java` | 244-430 (Builder) | Add uniformDimensions + target fields |

---

## Missed Feature Candidates

Based on thorough review of the current codebase, the following potential features
were identified by examining what the code already supports vs. what could be
easily extended:

### F21. Stain Separation / Color Deconvolution Export (Candidate)

**Priority:** P2
**Source:** Codebase analysis -- brightfield counterpart to F4

The codebase already handles brightfield image types (`BRIGHTFIELD_H_E`,
`BRIGHTFIELD_H_DAB`, `BRIGHTFIELD_OTHER`) in `ExportMetadataWriter.writeBrightfieldInfo()`
and tracks stain vectors. QuPath supports color deconvolution to separate
H&E or H-DAB stains into individual components. Exporting these separated
stain channels as individual panels would be the brightfield equivalent of
F4 (split-channel export). QuPath's `TransformedServerBuilder.deconvolveStains()`
provides the API. This fills a gap since F4 explicitly notes it is
"fluorescence-only."

### F22. Per-Annotation Mask Export with Matching Rendered Image (Candidate)

**Priority:** P2
**Source:** Codebase analysis -- combining raw annotation export with rendered overlay

`RawImageExporter.exportAnnotations()` already implements per-annotation
bounding box export with padding and clamping. `MaskImageExporter` exports
masks for the whole image. A combined mode that exports matching image+mask
pairs per annotation (similar to how `TiledImageExporter` produces image+label
pairs) would be valuable for training segmentation models on specific regions.
The `TiledImageExporter` pattern (line 51-54, label server alongside tile
exporter) could be adapted for arbitrary annotation regions.

### F23. Export Preview Comparison (Side-by-Side Before/After) (Candidate)

**Priority:** P3
**Source:** Codebase analysis -- preview infrastructure exists

The preview system (`RenderedConfigPane.handlePreview()`, line 816-903)
currently shows one rendered preview. A split-view showing the original
image alongside the rendered export would help users verify their settings.
The preview infrastructure is already built; this only needs the original
image read alongside the rendered version.

---

## Implementation Priority Matrix

| Feature | Priority | Effort | Dependencies | Recommended Phase | Files Touched | Est. LOC |
|---------|----------|--------|--------------|-------------------|---------------|----------|
| ~~F1. Annotation region export~~ | ~~P1~~ | ~~Medium~~ | ~~None~~ | **DONE (v0.5.0)** | 6 | ~350 |
| ~~F5. Panel labels (A, B, C)~~ | ~~P1~~ | ~~Low~~ | ~~None~~ | **DONE (v0.5.0)** | 10 | ~450 |
| ~~F17. Visual LUT selector~~ | ~~P2~~ | ~~Low~~ | ~~None~~ | **DONE (v0.5.0)** | 1 | ~60 |
| ~~F6. SVG / vector export~~ | ~~P2~~ | ~~High~~ | ~~None~~ | **DONE (v0.6.0)** | 3 | ~500 |
| ~~Object Crops~~ | ~~P1~~ | ~~Medium~~ | ~~None~~ | **DONE (v0.6.0)** | 6 | ~400 |
| ~~Collapsible UI sections~~ | ~~--~~ | ~~Medium~~ | ~~None~~ | **DONE (v0.6.0)** | 7 | ~300 |
| F2. Inset / zoom panels | P1 | High | F1 (done) | Phase 2 | 5 | ~450 |
| ~~F4. Split-channel export~~ | ~~P1~~ | ~~High~~ | ~~None~~ | **DONE (v0.7.0)** | 8 | ~600 |
| ~~F7. Display range matching~~ | ~~P2~~ | ~~Medium~~ | ~~None~~ | **DONE (v0.7.0)** | 8 | ~600 |
| F8. DPI control | P2 | Low | None | Phase 2 | 4-5 | ~200 |
| F11. Dimension labels | P2 | Low | None | Phase 2 | 5 | ~250 |
| F3. Multi-panel grid layout | P1 | High | F4 (done), F5 (done), F20 | Phase 3 | 3-4 | ~500 |
| F10. CDV simulation | P2 | Low | None | Phase 3 | 2-3 | ~180 |
| F19. Contour mask export | P2 | Medium | None | Phase 3 | 4 | ~200 |
| F20. Matched panel dimensions | P2 | Medium | None (but F3 uses) | Phase 3 | 4 | ~150 |
| F9. Arrow / shape overlays | P2 | Medium | None | Phase 3 | 2-3 | ~120 |
| F21. Stain separation export | P2 | Medium | None | Phase 3 | 3-4 | ~200 |
| ~~F6. SVG / vector export~~ | ~~P2~~ | ~~High~~ | ~~None~~ | **DONE (v0.6.0)** | 3 | ~500 |
| F13. Custom text overlays | P2 | Medium | F11 | Phase 4 | 3-4 | ~250 |
| F12. Crop and rotation | P2 | High | F1 | Phase 4 | 3-4 | ~200 |
| F16. Figure templates | P3 | Medium | F3 | Phase 4 | 2-3 | ~200 |
| F14. Metadata embedding | P3 | Medium | None | Phase 4 | 2-3 | ~250 |
| F22. Per-annotation image+mask | P2 | Medium | F1 | Phase 4 | 3-4 | ~200 |
| F15. Interactive channel cycling | P3 | High | F4 | Future | 2-3 | ~350 |
| F18. Batch contrast preview | P3 | Medium | F7 | Future | 2-3 | ~350 |
| F23. Preview comparison | P3 | Low | None | Future | 1-2 | ~100 |

### Dependency Validation

The dependency ordering has been validated against the actual code structure:

1. **F1 before F2**: F1 introduced the annotation region concept (RegionType enum,
   bounding box calculation, paintObjects offset logic). **F1 is now complete (v0.5.0).**
   F2's inset uses the same region selection for defining the inset source area.

2. **F4 + F5 before F3**: F3 (figure composition) needs panels to compose.
   F4 produces split-channel panels; F5 provides panel labels.
   **F5 is now complete (v0.5.0).** F3's `FigureComposer` could work without F4,
   but the primary use case (split-channel figure with labeled panels) benefits from both.

3. **F20 before F3 (soft dependency)**: Uniform dimensions simplify grid layout.
   `FigureComposer` could handle mixed sizes, but uniform input is cleaner.
   This is a soft dependency -- F3 can include its own sizing logic.

4. **F11 before F13**: F13 (custom text) extends F11's `DimensionLabelRenderer`
   to support multiple text items. Building F11 first establishes the rendering
   infrastructure.

5. **F7 before F18**: F18 (batch contrast preview) uses the same image scanning
   logic as F7 (global matched display). Implementing F7 first creates the
   reusable scanning code.

6. **F4 before F15**: F15 (interactive channel cycling) depends on per-channel
   rendering from F4.

7. **F1 before F12**: F12 (crop and rotation) is partially superseded by F1
   (annotation-based cropping). **F1 is now complete (v0.5.0).** F12 adds rotation
   on top of the existing crop capability.

---

## Architectural Considerations

### Current Codebase Strengths
- **Stateless renderers** (ScaleBarRenderer, ColorScaleBarRenderer,
  PanelLabelRenderer) are easy to extend with new overlay types (InsetRenderer,
  DimensionLabelRenderer, etc.). All follow the same pattern: static `draw*()`
  method, save/restore graphics state, compute position from
  `ScaleBarRenderer.Position` enum, delegate text rendering to `TextRenderUtils`.
- **Builder pattern** on RenderedExportConfig makes adding new fields safe --
  26 existing fields demonstrate this works well at scale.
- **Script generation** architecture cleanly separates config from rendering.
  Each render mode has its own `generate*Script()` method with shared helpers
  (`emitScaleBarFunction`, `emitDisplayImports`, etc.).
- **Batch pipeline** (BatchExportTask) is well-structured for adding new
  export paths -- factory methods (`forRendered`, `forMask`, etc.) and
  category-based dispatch.
- **Existing region export in Raw**: `RawImageExporter.exportAnnotations()`
  already implements annotation bounding box + padding + clamping. This
  pattern can be directly reused for F1.

### Code Duplication -- RESOLVED (v0.5.0)
- **`TextRenderUtils` extracted**: `drawOutlinedText()`, `computeOutlineColor()`,
  and `resolveFontSize()` have been extracted from `ScaleBarRenderer` and
  `ColorScaleBarRenderer` into a shared `TextRenderUtils` utility class.
  Both renderers and the new `PanelLabelRenderer` now delegate to this shared class.
  Future text-drawing renderers (F11 DimensionLabelRenderer, etc.) should use
  `TextRenderUtils` directly.

### Potential Pain Points
- **RenderedExportConfig is growing large**: currently has 31 fields (after F1
  and F5). Adding F2 (+6), F8 (+1), F11 (+5), F12 (+5), F20 (+3) could push
  it past 50 fields. **Recommendation**: group related fields into sub-config
  records before Phase 2:
  - `ScaleBarConfig` (6 fields: show, position, color, fontSize, bold + the existing scaleBar fields)
  - `ColorScaleBarConfig` (5 fields: show, position, fontSize, bold)
  - `InsetConfig` (6 fields: show, region, magnification, position, frameColor, frameWidth)
  - `PanelLabelConfig` (6 fields: show, text, position, fontSize, bold, color)
  - `DimensionLabelConfig` (5 fields: show, template, position, fontSize, bold)
  This refactoring is backward-compatible via Builder delegation.

- **RenderedConfigPane UI complexity** -- **RESOLVED (v0.6.0)**: All 5 config panes
  now use collapsible `TitledPane` sections via `SectionBuilder`. Essential
  sections are expanded by default; advanced sections (Scale Bar, Color Scale Bar,
  Panel Label, Object Overlays, Label Masks) start collapsed. This directly
  addresses the recommendation below. Future overlay sections (Inset, Dimension
  Label) should follow the same pattern.

- **Script generation complexity**: each new feature adds more Groovy code
  to the generated script. The classifier overlay script is already ~200 lines
  in the generated output. **Recommendation**: consider a template-based system
  (Velocity, Freemarker, or simple string templates loaded from resources)
  rather than StringBuilder-based code generation. However, the current approach
  keeps generated scripts self-contained, which is a key user requirement.

- **Memory pressure**: features like figure composition (F3) and split-channel
  export (F4) hold multiple full images in memory simultaneously. Need
  careful BufferedImage lifecycle management. The current code pattern
  (render -> write -> discard) is memory-efficient; F3 breaks this by
  holding all panels.

- **Testing**: each new renderer needs its own test class following the
  established pattern (ASCII-only verification, position testing, edge cases).
  The test suite (currently 14 classes) includes `PanelLabelRendererTest` and
  `TextRenderUtilsTest` as templates for new renderer tests.

### QuPath API Notes (Discovered During Review)

- `ChannelDisplayTransformServer.createColorTransformServer()` accepts
  `List<ChannelDisplayInfo>` -- this is the key API for F4 (split channels).
  Calling it with a single-element list produces a single-channel view.

- `ImageDisplay.create(imageData)` is used in `resolveDisplayServer()` to
  create a headless display for reading channel info. This works without
  a viewer and is the correct approach for batch processing.

- `HierarchyOverlay.paintOverlay()` accepts `(Graphics2D, ImageRegion,
  downsample, ImageData, boolean)`. The boolean parameter controls whether
  to paint the overlay in "preview" mode. Used in `paintObjects()` with
  `true`.

- `LabeledImageServer.Builder` does not support outline-only rendering.
  F19 (contour masks) must use custom `Graphics2D.draw(Shape)` instead.

- `ColorMaps.getColorMaps()` returns a `Map<String, ColorMap>`. The map
  is static and cached -- safe to call repeatedly for F17's swatch generation.

- `GeneralTools.stripInvalidFilenameChars()` is used in all `buildOutputFilename()`
  methods. It removes invalid chars rather than replacing them.

- `ImageWriterTools.writeImage()` does not support metadata injection.
  F8 (DPI) and F14 (metadata embedding) both need direct `ImageIO` access.

---

## Sources

- [QuickFigures - PLOS ONE Publication](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0240280)
- [QuickFigures - GitHub](https://github.com/grishkam/QuickFigures)
- [QuickFigures - User Guide](https://github.com/grishkam/QuickFigures/blob/master/UserGuide/User%20Guide.md)
- [BioVoxxel Figure Tools - GitHub](https://github.com/biovoxxel/BioVoxxel-Figure-Tools)
- [BioVoxxel Figure Tools - Documentation](https://biovoxxel.github.io/BioVoxxel-Figure-Tools/)
- [BioVoxxel Figure Tools - image.sc Forum](https://forum.image.sc/t/improved-update-of-biovoxxel-figure-tools-v4-3-2/119232)

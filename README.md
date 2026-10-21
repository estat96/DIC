# DIC — real-time Digital Image Correlation on Android

Point the phone camera at a speckled specimen, focus, lock focus, press **START**: every frame is correlated on the fly and the displacement / strain maps are drawn over the live image. No photos are stored — only the fields (CSV), optionally with synchronised force from an external load cell.

## Get the APK
**Releases** → latest `DIC.apk` (built automatically by GitHub Actions on every push to `main`).

## Workflow
1. **ROI** — toggle, then drag corners / the frame or draw a new box. **Grid** and **Subsets** show the nodes and the subset size before the test.
2. **Focus** (bottom bar) — *Auto*: tap the specimen; *Manual*: slider. Watch *Sharpness* in the status box.
3. **Lock focus** — the lens is held at the current distance (AF off), exposure and white balance are locked, stabilisation is disabled. Nothing changes until you unlock — including when START is pressed.
4. **Calibrate** — pixel → mm:
   - *Ruler* (recommended): drag two markers onto a known length and type it in mm;
   - *Lens model*: estimate from focal length, sensor size and the lens focus distance (Z·pitch/f), with field of view, depth of field, out-of-plane pseudo-strain (Δz/Z) and tilt-induced scale error;
   - *Manual* mm/px.
   The focus distance at calibration is stored; a warning appears if it changes. The status box shows phone **tilt** (gravity sensor) for a wall-mounted or downward-looking setup — keep it below 1°.
5. **START** — correlation mode (Settings): *versus first frame* or *frame to frame* (accumulated). **Reset reference** makes the current frame the new zero.
6. **Field** — |U|, u, v, εxx, εyy, εxy, ε1, ε2, εeq, ZNCC. **Strain** — *Subset* (IC-GN gradients), *Mesh* (Q4 finite elements on the DIC grid, nodal averaging), *Local fit* (least-squares strain window). **Scale bar** toggles the bar in the lower right. **Display…** — vectors, deformed mesh, colour limits, live force plot.
7. **Force sensor** — starts a local-only Wi-Fi hotspot (no internet) and a TCP/UDP receiver on port 5000. The device sends one number per line. See `firmware/force_sender` (ESP32 + HX711). Tare and scaling factor in the app.
8. **Results / Player** — browse recordings, play / scrub fields, tap a node for its history. **Analysis** — force–elongation (virtual extensometer), stress–strain, transverse–axial strain; E, ν, Rp0.2, Rm, uniform and final strain.

## Algorithm
- Luminance (Y) plane straight from the camera buffer, no copies, no stored images.
- Integer ZNCC search around the previous solution, then IC-GN (inverse-compositional Gauss–Newton) with an affine subset shape function and ZNSSD criterion; Green–Lagrange strains.
- Frame-to-frame mode moves nodes with the material and accumulates the deformation gradient (F = F_inc·F).
- Parallel C++/NDK on all cores. Synthetic test: ≈0.005 px displacement error, ≈20 ms per frame for 270 nodes.

## CSV (v3)
Header lines `# mode= # image=W,H # grid=nx,ny,step,subset # scale_mm_per_px= # calibration= # focus_diopters= # strain= # force_unit=`, then
`frame,t_ms,x_px,y_px,u,v,exx,eyy,exy,zncc,ux,uy,vx,vy,force` — all nodes of every recorded frame (NaN = rejected); frame 0 is the reference.
Default folder: `Android/data/com.dic.app/files/results/`, or any folder chosen in Settings.

## Tips
Random speckle 3–5 px, diffuse light, tripod, sensor parallel to the specimen, focus locked, calibrate after locking focus.

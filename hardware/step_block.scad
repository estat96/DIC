// Out-of-plane (Z) step block: pads at known heights for ChArUco CH-XS2 stickers (22 × 16 mm).
// Measures how the scale changes with distance (pseudo-strain Δz/Z) and checks the lens focus-distance model.
// After printing, MEASURE each step height with a caliper/micrometer and enter the measured values.
include <common.scad>

steps = [0, 0.5, 1, 2, 4];   // nominal heights above pad 0, mm
pad = [26, 20];              // pad size (x, y)
base_h = 4;

difference() {
    union() {
        cube([pad[0] * len(steps), pad[1] + 8, base_h]);
        for (i = [0 : len(steps) - 1])
            translate([i * pad[0] + 1, 0, base_h - 0.01]) cube([pad[0] - 2, pad[1], steps[i] + 1.0]);
    }
    for (i = [0 : len(steps) - 1])
        translate([i * pad[0] + pad[0] / 2, pad[1] + 4, base_h - 0.59]) label(str("+", steps[i]), 3.5, 0.6);
}

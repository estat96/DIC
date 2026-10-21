// Tilt wedges for checking tilt estimation (CH-S5 sticker 55 × 40 mm on the slope).
include <common.scad>

angles = [1, 2, 5, 10];
size = [64, 48];
h0 = 6;

module wedge(a) {
    difference() {
        hull() {
            cube([size[0], size[1], h0]);
            cube([0.01, size[1], h0 + size[0] * tan(a)]);
        }
        // angle engraved on the front face
        translate([size[0] * 0.65, 0.59, h0 / 2]) rotate([90, 0, 0]) label(str(a, " deg"), 3, 0.6);
    }
}
for (i = [0 : len(angles) - 1]) translate([(i % 2) * (size[0] + 6), floor(i / 2) * (size[1] + 6), 0]) wedge(angles[i]);

// Shared helpers. Units: mm. Print in PETG/PLA, 0.2 mm layers, 4 perimeters, 40 % infill.
$fn = 64;
NUT_14_AF = 11.11;   // 1/4"-20 hex nut across flats
NUT_14_H  = 5.6;

module hex(af, h) { cylinder(d = af / cos(30), h = h, $fn = 6); }

module label(t, size = 4, depth = 0.6) {
    linear_extrude(depth) text(t, size = size, halign = "center", valign = "center", font = "Liberation Sans:style=Bold");
}

// 1/4"-20 tripod socket: captive nut from inside + bolt hole, opening at z = 0 (downwards)
module tripod_socket(depth = 12) {
    translate([0, 0, -0.01]) cylinder(d = 6.8, h = depth + 0.02);
    translate([0, 0, 3]) hex(NUT_14_AF + 0.3, NUT_14_H + 0.4);
}

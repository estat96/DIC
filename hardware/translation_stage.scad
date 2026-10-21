// Manual translation stage: M6 screw (1.0 mm pitch) → 0.1 mm per index mark (36°).
// Parts: "base", "carriage", "knob". Hardware: M6 × 100 threaded rod or bolt, 2× M6 nuts, 2× M6 washers.
// Use: glue a speckle or ChArUco patch on the carriage, turn the knob by N marks, compare DIC with N × 0.1 mm.
include <common.scad>

part = "all";
L = 110; Wb = 50; Hb = 10;      // base
dov = 6;                        // dovetail height
cl = 0.3;                       // sliding clearance
car_l = 50;

module dovetail(l, w_top, w_bot, h) {
    rotate([90, 0, 90]) linear_extrude(l) polygon([[-w_bot / 2, 0], [w_bot / 2, 0], [w_top / 2, h], [-w_top / 2, h]]);
}

module base() {
    difference() {
        union() {
            cube([L, Wb, Hb]);
            translate([0, Wb / 2, Hb - 0.01]) dovetail(L - 14, 22, 16, dov);   // rail: 16 mm at the bottom, 22 mm at the top (retaining)
            translate([L - 14, 0, 0]) cube([14, Wb, Hb + 22]);                // bearing block
        }
        // screw hole through the bearing block (axis along x at screw height)
        translate([L - 15, Wb / 2, Hb + 15]) rotate([0, 90, 0]) cylinder(d = 6.4, h = 20);
        // tripod / table screw holes
        for (x = [15, L - 40]) translate([x, Wb / 2, -1]) cylinder(d = 4.5, h = Hb + 2);
        translate([L / 2 - 10, Wb / 2, 0]) tripod_socket(Hb - 1);
        // mm scale engraved along the side
        for (i = [0 : 2 : 60]) translate([10 + i, 0.6, Hb - (i % 10 == 0 ? 6 : 3)]) cube([0.5, 1, 10]);
    }
}

module carriage() {
    difference() {
        translate([0, 0, 0]) cube([car_l, Wb, 22]);
        // dovetail groove with clearance
        translate([-1, Wb / 2, -0.01]) dovetail(car_l + 2, 22 + 2 * cl, 16 + 2 * cl, dov + cl);
        // captive M6 nut + screw channel along x at the same height as the bearing hole (15 mm above rail base → 15 - 0 here, carriage sits on rail top at z = Hb)
        translate([-1, Wb / 2, 15]) rotate([0, 90, 0]) cylinder(d = 6.6, h = car_l + 2);
        translate([car_l / 2 - 2.8, Wb / 2, 15]) rotate([0, 90, 0]) rotate([0, 0, 30]) hex(10.2, 5.6);
        translate([car_l / 2 - 2.8, Wb / 2 - 6, 15]) cube([5.6, 12, 20]);   // nut insertion slot from the top
        // index pointer
        translate([car_l - 1, -1, 18]) cube([2, 3, 5]);
    }
}

module knob() {
    difference() {
        union() {
            cylinder(d = 36, h = 8);
            cylinder(d = 14, h = 16);
        }
        translate([0, 0, -1]) hex(10.2, 6.5);                // M6 nut/bolt head locked in the knob
        translate([0, 0, -1]) cylinder(d = 6.4, h = 20);
        for (i = [0 : 9]) rotate([0, 0, i * 36]) translate([15, -0.5, 7.4]) cube([3.5, 1, 1]);   // 10 marks = 0.1 mm each
        rotate([0, 0, 0]) translate([11, -0.5, 7.4]) cube([3, 1, 1]);
        for (i = [0 : 9]) rotate([0, 0, i * 36 + 90]) translate([0, 12.5, 7.4]) linear_extrude(1) text(str(i), size = 3, halign = "center");
    }
}

if (part == "base" || part == "all") base();
if (part == "carriage" || part == "all") translate([0, Wb + 10, 0]) carriage();
if (part == "knob" || part == "all") translate([L + 30, 25, 0]) knob();

// Landscape tripod cradle for HUAWEI Pura 70 (157.6 × 74.3 × 7.95 mm).
// The phone slides in from the open end; the camera end stays free.
// Phone is held by the side walls and the screen-side lips (0.35 mm clearance; add felt tape if loose).
// Back plate has a large window so the camera island never touches the cradle.
// Tripod socket (1/4"-20, captive nut) under the long edge, at the grip centre.
include <common.scad>

phone_l = 157.6; phone_w = 74.3; phone_t = 7.95;
case_extra = 0;      // add thickness of a phone case (mm, per side)
clear = 0.35;        // sliding clearance
grip_len = 92;       // length of the phone held (from the bottom end); camera end stays free
wall = 3.0;          // side walls
back = 3.0;          // back plate
lip = 3.2;           // screen-side retaining lip width (covers the bezel only)
foot_h = 14;         // tripod foot height

W = phone_w + 2 * case_extra + 2 * clear;
T = phone_t + 2 * case_extra + 2 * clear;

module cradle() {
    difference() {
        union() {
            // shell: back + two long sides + closed bottom end
            cube([grip_len + wall, W + 2 * wall, T + back + wall]);
            // tripod foot under the lower long side
            translate([(grip_len + wall) / 2 - 22, -foot_h, 0]) cube([44, foot_h + 0.01, T + back + wall]);
        }
        // phone pocket (open at x = grip_len + wall)
        translate([wall, wall, back]) cube([grip_len + 1, W, T]);
        // screen opening, leaving a lip on the long sides and the bottom end
        translate([wall + lip, wall + lip, back + T - 0.01]) cube([grip_len + 1, W - 2 * lip, wall + 1]);
        // back window (ventilation, weight; the camera is outside the cradle anyway)
        translate([wall + 12, wall + 12, -1]) cube([grip_len - 20, W - 24, back + 2]);
        // charging port / button relief at the bottom end
        translate([-1, wall + W / 2 - 9, back + 1]) cube([wall + 2, 18, T - 2]);
        // tripod socket from below, axis along -y
        translate([(grip_len + wall) / 2, -foot_h, (T + back + wall) / 2]) rotate([-90, 0, 0]) tripod_socket(foot_h - 1);
        // engraved label
        translate([(grip_len + wall) / 2, -foot_h + 0.5, (T + back + wall) / 2 + 7]) rotate([90, 0, 0]) label("DIC", 4, 0.6);
    }
}

cradle();

// Magnetic target paddle: holds a printed ChArUco card (glued to 2 mm glass / aluminium composite)
// flat in the specimen plane. Magnets (10 × 3 mm) stick to steel grips of the test machine.
// Default pocket fits CH-M12 (132 × 96 mm board + margin). Change card size for other targets.
include <common.scad>

card = [150, 112];    // card size incl. white margin
card_t = 2.2;         // glass/ACP thickness
rim = 6; plate = 3;
magnet_d = 10.3; magnet_h = 3.2;

difference() {
    union() {
        cube([card[0] + 2 * rim, card[1] + 2 * rim, plate + card_t]);
        translate([card[0] / 2 + rim - 12, -30, 0]) cube([24, 31, plate + 4]);        // handle
    }
    // card pocket (card top flush with the rim)
    translate([rim, rim, plate]) cube([card[0], card[1], card_t + 1]);
    // finger notches to remove the card
    for (x = [rim + 20, card[0] + rim - 20]) translate([x, rim + card[1] / 2, plate]) cylinder(d = 16, h = 10);
    // magnet pockets from the back
    for (x = [rim + 12, card[0] + rim - 12]) for (y = [rim + 12, card[1] + rim - 12])
        translate([x, y, -0.01]) cylinder(d = magnet_d, h = magnet_h);
    // lightening window in the back plate (glass stays supported by the rim)
    translate([rim + 25, rim + 25, -1]) cube([card[0] - 50, card[1] - 50, plate + 2]);
    // reference edge marks: align the board X axis with the loading axis
    translate([card[0] / 2 + rim, card[1] + 2 * rim - 1.5, plate + card_t - 0.6]) cube([1, 3, 1]);
}

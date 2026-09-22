#!/usr/bin/env bash
# Turns an AVD into a Pixel-Fold-shaped foldable with two logical displays:
# the inner panel as the default display, the cover as a second one that
# takes over when the device is CLOSED, so `wm size` really changes with
# the posture (D7). The SDK's classic "7.6in Foldable" profile only masks
# half of one display when folded; the window keeps its size, and the
# grid never sees a cover width.
#
# Usage: e2e/avd/pixel-fold.sh <path/to/config.ini>
#
# Needs a system image whose advancedFeatures.ini carries
# `SupportPixelFold = on` (the google_apis images do; the default images
# refuse the profile with "requires foldable feature"). The keys are those
# of the SDK's `pixel_fold` device profile; the runner's SDK does not ship
# that profile, which is why they live here. CI runs this as the
# emulator-runner's pre-emulator-launch-script; locally:
#   avdmanager create avd -n fold -k "system-images;android-36;google_apis;x86_64" -d "7.6in Foldable"
#   e2e/avd/pixel-fold.sh ~/.android/avd/fold.avd/config.ini
set -euo pipefail
ini="${1:?config.ini path}"
[ -f "$ini" ] || { echo "no such file: $ini" >&2; exit 1; }

set_key() { # $1 = key, $2 = value
  if grep -q "^$1=" "$ini"; then
    sed -i "s|^$1=.*|$1=$2|" "$ini"
  else
    printf '%s=%s\n' "$1" "$2" >> "$ini"
  fi
}

set_key hw.device.name pixel_fold
set_key hw.device.manufacturer Google
set_key hw.lcd.width 2208
set_key hw.lcd.height 1840
set_key hw.lcd.density 420
set_key hw.displayRegion.0.1.xOffset 0
set_key hw.displayRegion.0.1.yOffset 0
set_key hw.displayRegion.0.1.width 1080
set_key hw.displayRegion.0.1.height 2092
set_key hw.sensor.hinge yes
set_key hw.sensor.hinge.count 1
set_key hw.sensor.hinge.type 1
set_key hw.sensor.hinge.sub_type 1
set_key hw.sensor.hinge.ranges 0-180
set_key hw.sensor.hinge.defaults 180
set_key hw.sensor.hinge.areas 1080-0-0-1840
set_key hw.sensor.posture_list "1, 2, 3"
set_key hw.sensor.hinge_angles_posture_definitions "0-30, 30-150, 150-180"
set_key hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture 1
echo "pixel_fold keys written to $ini"

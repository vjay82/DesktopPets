#requires -Version 5
<#
.SYNOPSIS
    Write hand-crafted "play bow" sprites for the quadruped pets.

.NOTES
    A play bow is the inverse of the sit pose: rear stays UP (hind legs
    standing tall, butt high, tail wagging), front goes DOWN (chest and
    head lying along the ground, front paws extended forward on the
    floor). Mirroring the idle sprite via any single transform cannot
    produce this silhouette - it requires actually redrawing the front
    half flat. The Sit tiles do the same thing in reverse: they preserve
    the idle's front half and redraw the rear as a sloped triangle
    going down to the ground.

    Right-facing tile is hand-drawn with rectangles; left-facing tile is
    the right-facing tile wrapped in a `translate(vb 0) scale(-1 1)`
    mirror group, so the two stay in sync.

    Skipped: Ducky (too low-slung to bow visually) and Bird (birds don't
    play-bow). Their INVITE_PLAY behavior uses a different visual cue.
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------------------
# Beagle / Dog play bow, viewBox 48x48, right-facing.
# Palette matches Dog/Idle and Dog/Sit:
#   #4a2c18 dark outline / nose / eye
#   #d4a373 body fur
#   #a47a52 knees / joints / mid-tone
#   url(#belly) = white-to-tan vertical gradient for belly highlights
#   #1a1410 = eye dot
# ---------------------------------------------------------------------------
$dogPose = @'
  <!-- =========================================================
       REAR HALF: every rect from Dog/Idle/tile000.svg that lies
       at x < 24, copied verbatim. This makes the rump, tail and
       hind legs of the bow pose visually identical to the idle.
       ========================================================= -->
  <!-- tail stub on the left (same as idle) -->
  <rect x="12" y="23" width="1" height="1" fill="url(#belly)"/>
  <rect x="13" y="24" width="1" height="2" fill="#d4a373"/>
  <rect x="14" y="25" width="1" height="2" fill="#4a2c18"/>
  <rect x="15" y="26" width="2" height="2" fill="#4a2c18"/>
  <!-- back top dark + left edge of rump -->
  <rect x="18" y="25" width="6" height="2" fill="#4a2c18"/>
  <rect x="17" y="25" width="1" height="2" fill="#d4a373"/>
  <!-- body fur block (left half of idle's body) -->
  <rect x="17" y="27" width="7" height="2" fill="#d4a373"/>
  <rect x="17" y="29" width="7" height="3" fill="#d4a373"/>
  <!-- belly shading + stripe -->
  <rect x="17" y="32" width="1" height="1" fill="#a47a52"/>
  <rect x="18" y="32" width="6" height="1" fill="url(#belly)"/>
  <rect x="18" y="33" width="1" height="1" fill="#a47a52"/>
  <rect x="19" y="33" width="5" height="1" fill="url(#belly)"/>
  <!-- both visible hind legs (near + far), paws on ground -->
  <rect x="19" y="34" width="2" height="3" fill="#d4a373"/>
  <rect x="20" y="35" width="1" height="1" fill="#a47a52"/>
  <rect x="19" y="37" width="2" height="1" fill="url(#belly)"/>
  <rect x="22" y="35" width="2" height="2" fill="#a47a52"/>
  <rect x="22" y="37" width="2" height="1" fill="url(#belly)"/>

  <!-- =========================================================
       SLOPING BACK + LOWERED FRONT BODY: a wedge that fills the
       transition zone from the upright rear (top at y=25, bottom
       at y=33) down to the head's back (top at y=32, bottom at
       y=36). All columns from x=24 through x=36 are filled
       solidly so there is no visible gap between the rear, the
       wedge, and the translated head.
       ========================================================= -->
  <!-- dark back-line staircase (top of the wedge) -->
  <rect x="24" y="26" width="1" height="1" fill="#4a2c18"/>
  <rect x="25" y="27" width="1" height="1" fill="#4a2c18"/>
  <rect x="26" y="28" width="1" height="1" fill="#4a2c18"/>
  <rect x="27" y="29" width="1" height="1" fill="#4a2c18"/>
  <rect x="28" y="30" width="1" height="1" fill="#4a2c18"/>
  <rect x="29" y="31" width="1" height="1" fill="#4a2c18"/>
  <!-- flat dark back-line continues across the lowered chest to the head's back-bar at x=37 -->
  <rect x="30" y="32" width="7" height="1" fill="#4a2c18"/>
  <!-- body fur fill under the diagonal stair (one column per step) -->
  <rect x="24" y="27" width="1" height="6" fill="#d4a373"/>
  <rect x="25" y="28" width="1" height="5" fill="#d4a373"/>
  <rect x="26" y="29" width="1" height="4" fill="#d4a373"/>
  <rect x="27" y="30" width="1" height="3" fill="#d4a373"/>
  <rect x="28" y="31" width="1" height="2" fill="#d4a373"/>
  <rect x="29" y="32" width="1" height="1" fill="#d4a373"/>
  <!-- body fur fill across the lowered chest (x=30..36, y=33..35) -->
  <rect x="30" y="33" width="7" height="3" fill="#d4a373"/>
  <!-- belly stripe under the wedge (continuous with rear) -->
  <rect x="24" y="33" width="6" height="1" fill="url(#belly)"/>
  <rect x="24" y="34" width="6" height="1" fill="#a47a52"/>
  <rect x="30" y="36" width="7" height="1" fill="url(#belly)"/>

  <!-- =========================================================
       HEAD: the entire idle head (x=27..36, y=23..30) translated
       by (+9, +7) so it lies low at the front of the bow. Every
       rect below is the matching idle rect with x += 9, y += 7.
       The "back of head" dark vertical bar (originally at x=28
       y=25..29) now sits at x=37 y=32..36, directly attaching to
       the wedge's right edge so the head reads as a continuation
       of the body, not a separate creature.
       ========================================================= -->
  <!-- ear top dark (idle 29,23,2,2 -> 38,30,2,2) -->
  <rect x="38" y="30" width="2" height="2" fill="#4a2c18"/>
  <!-- back-of-head dark bar (idle 28,25,2,5 -> 37,32,2,5) -->
  <rect x="37" y="32" width="2" height="5" fill="#4a2c18"/>
  <!-- under-ear dark patch (idle 27,29,2,2 -> 36,36,2,2) -->
  <rect x="36" y="36" width="2" height="2" fill="#4a2c18"/>
  <!-- head top dark (idle 30,23,4,1 -> 39,30,4,1) -->
  <rect x="39" y="30" width="4" height="1" fill="#4a2c18"/>
  <!-- forehead/cheek brown (idle 30,24,5,2 -> 39,31,5,2) -->
  <rect x="39" y="31" width="5" height="2" fill="#d4a373"/>
  <!-- cheek brown (idle 30,26,2,2 -> 39,33,2,2) -->
  <rect x="39" y="33" width="2" height="2" fill="#d4a373"/>
  <!-- snout top tan (idle 32,26,4,2 -> 41,33,4,2) -->
  <rect x="41" y="33" width="4" height="2" fill="url(#belly)"/>
  <!-- snout middle tan (idle 32,28,5,2 -> 41,35,5,2) -->
  <rect x="41" y="35" width="5" height="2" fill="url(#belly)"/>
  <!-- snout bottom tan (idle 33,30,3,1 -> 42,37,3,1) -->
  <rect x="42" y="37" width="3" height="1" fill="url(#belly)"/>
  <!-- eye (idle 32,25,1,1 -> 41,32,1,1) -->
  <rect x="41" y="32" width="1" height="1" fill="#1a1410"/>
  <!-- nose (idle 35,27,1,1 -> 44,34,1,1) -->
  <rect x="44" y="34" width="1" height="1" fill="#1a1410"/>

  <!-- =========================================================
       FORELEGS: two visible front paws below the lowered chest.
       ========================================================= -->
  <rect x="30" y="37" width="2" height="1" fill="url(#belly)"/>
  <rect x="33" y="37" width="2" height="1" fill="url(#belly)"/>
'@

$dogDefs = @'
  <defs>
    <linearGradient id="belly" x1="0" y1="32" x2="0" y2="34" gradientUnits="userSpaceOnUse" spreadMethod="pad">
      <stop offset="0" stop-color="#f7eede"/>
      <stop offset="1" stop-color="#c89a6a"/>
    </linearGradient>
  </defs>
'@

# ---------------------------------------------------------------------------
# Cat play bow, viewBox 32x32, right-facing.
# Hand-drawn with rectangles in the idle cat palette:
#   #2f2f2e dark outline / fur
#   #b5b5b5 mid-tone shading
#   #e0e0e0 light belly
#   #1a1410 = eye / nose dot
# ---------------------------------------------------------------------------
$catPose = @'
  <!-- =========================================================
       Cat play-bow pose, right-facing. ViewBox 0..32.
       Style: matches Cat/Idle. White fur (#e0e0e0) interior
       with a 1-px dark silhouette (#2f2f2e). Subtle grey
       belly shading (#b5b5b5). Eye/nose are dark dots.
       Ground line at y=22.

       Layout (right-facing):
         x= 8..14 : rear / rump (tall, tail curls above)
         x=14..18 : sloping back going down toward the floor
         x=18..28 : lowered body + head lying on ground
         ear at top of head, tail curled up above rear.
       ========================================================= -->

  <!-- ===== Silhouette base (dark) ===== -->
  <!-- rear standing tall -->
  <rect x="8"  y="12" width="7"  height="10" fill="#2f2f2e"/>
  <!-- sloping back -->
  <rect x="15" y="14" width="1"  height="8"  fill="#2f2f2e"/>
  <rect x="16" y="15" width="1"  height="7"  fill="#2f2f2e"/>
  <rect x="17" y="16" width="1"  height="6"  fill="#2f2f2e"/>
  <rect x="18" y="17" width="1"  height="5"  fill="#2f2f2e"/>
  <!-- lowered body + head on the ground -->
  <rect x="19" y="18" width="9"  height="4"  fill="#2f2f2e"/>
  <!-- nose extends 1 px past the head -->
  <rect x="28" y="19" width="1"  height="2"  fill="#2f2f2e"/>

  <!-- ===== Ear: pricked up on top of the head =====
       Drawn as a single tall block so it visually merges with the
       head silhouette below (no anti-aliasing gap). -->
  <rect x="25" y="16" width="2"  height="4"  fill="#2f2f2e"/>

  <!-- ===== Tail: curled UP, anchored to rear at x=11..14 =====
       The tail block extends down into the rear silhouette so the
       two render as a single continuous shape. -->
  <rect x="11" y="9"  width="4"  height="4"  fill="#2f2f2e"/>
  <!-- white tail interior -->
  <rect x="12" y="9"  width="2"  height="3"  fill="#e0e0e0"/>

  <!-- ===== White fur overlay (inset 1 px from silhouette) ===== -->
  <!-- rear interior -->
  <rect x="9"  y="13" width="5"  height="7"  fill="#e0e0e0"/>
  <!-- slope interior (each diagonal column 1px inset on right) -->
  <rect x="14" y="14" width="1"  height="6"  fill="#e0e0e0"/>
  <rect x="15" y="15" width="1"  height="5"  fill="#e0e0e0"/>
  <rect x="16" y="16" width="1"  height="4"  fill="#e0e0e0"/>
  <rect x="17" y="17" width="1"  height="3"  fill="#e0e0e0"/>
  <rect x="18" y="18" width="1"  height="2"  fill="#e0e0e0"/>
  <!-- lowered body interior -->
  <rect x="19" y="19" width="8"  height="1"  fill="#e0e0e0"/>
  <!-- ear interior shading (so it reads as a pricked ear, not a block) -->
  <rect x="26" y="17" width="1"  height="1"  fill="#e0e0e0"/>

  <!-- ===== Shading (subtle grey on belly) ===== -->
  <rect x="9"  y="20" width="5"  height="1"  fill="#b5b5b5"/>
  <rect x="19" y="20" width="8"  height="1"  fill="#b5b5b5"/>

  <!-- ===== Eye + nose dots ===== -->
  <rect x="24" y="19" width="1"  height="1"  fill="#1a1410"/>
  <rect x="28" y="19" width="1"  height="1"  fill="#1a1410"/>

  <!-- ===== Paws on ground (4 little dark feet) ===== -->
  <rect x="9"  y="21" width="1"  height="1"  fill="#1a1410"/>
  <rect x="13" y="21" width="1"  height="1"  fill="#1a1410"/>
  <rect x="20" y="21" width="1"  height="1"  fill="#1a1410"/>
  <rect x="25" y="21" width="1"  height="1"  fill="#1a1410"/>
'@

$pets = @(
    @{ name='Dog'; vb=48; defs=$dogDefs; pose=$dogPose },
    @{ name='Cat'; vb=32; defs='';       pose=$catPose }
)

foreach ($pet in $pets) {
    $outDir = Join-Path $root ("src/main/resources/Sprites/$($pet.name)/PlayBow")
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Path $outDir | Out-Null
    }
    $vb = $pet.vb

    foreach ($variant in @(
            @{ idx=0; mirror=$false; label='right' },
            @{ idx=1; mirror=$true;  label='left'  })) {

        if ($variant.mirror) {
            $body = "  <g transform=`"translate($vb 0) scale(-1 1)`">`r`n$($pet.pose)`r`n  </g>"
        } else {
            $body = $pet.pose
        }

        $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb" shape-rendering="crispEdges">
$($pet.defs)
$body
</svg>
"@
        $out = Join-Path $outDir ("tile{0:000}.svg" -f $variant.idx)
        [System.IO.File]::WriteAllText($out, $svg, [System.Text.UTF8Encoding]::new($false))
        Write-Host "  wrote $($pet.name)/PlayBow/tile$('{0:000}' -f $variant.idx).svg ($($variant.label))"
    }
}
Write-Host "done."

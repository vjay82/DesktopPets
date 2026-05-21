#requires -Version 5
<#
.SYNOPSIS
    Generate N dance-pose SVG frames per pet by wrapping the pet's idle
    sprite content in per-frame transforms (hop / lean / spin / squat).

.NOTES
    The pet sprites are sealed traced paths whose coordinates live inside
    an outer `scale(0.125 0.125) translate(0,256) scale(1,-1)` group
    (or similar for non-32x32 viewboxes). Re-drawing the bodies frame by
    frame is impractical. Instead we treat each existing idle SVG as
    "the body" and apply a fresh viewBox-space transform to it per dance
    frame so the WHOLE body hops, leans, mirrors, etc.

    Run from the repo root: pwsh -File tools/gen-dance-frames.ps1
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Per-pet config: idle source, output Dance dir, viewBox dimensions and
# pivot points (center-x, feet-y) used by the rotation transforms so the
# pet rotates around its feet (a head-pivot would slide the feet off the
# floor and look like teleporting).
$pets = @(
    @{ name='Cat';   idle='Cat/Idle/tile000.svg';   vb=32; cx=16; feetY=29 },
    @{ name='Dog';   idle='Dog/Idle/tile000.svg';   vb=48; cx=24; feetY=38 },
    @{ name='Ducky'; idle='Ducky/Idle/tile000.svg'; vb=64; cx=32; feetY=56 },
    @{ name='Bird';  idle='Bird/Idle/tile001.svg';  vb=32; cx=16; feetY=28 }
)

# Frame transforms in viewBox coordinates. {0}=cx {1}=feetY {2}=vb
# Six frames cycle as: hop, lean-left, big-hop, lean-right, mirror-spin,
# squat. At ~180 ms/frame that's a ~1.1 s loop the eye easily parses as
# "the pet is dancing" rather than the previous 2-frame side-shuffle.
$frames = @(
    'translate(0 -1)',                                  # 0: small hop
    'rotate(-12 {0} {1})',                              # 1: lean left
    'translate(0 -3)',                                  # 2: bigger hop
    'rotate(12 {0} {1})',                               # 3: lean right
    'translate({2} 0) scale(-1 1)',                     # 4: mirror (face-around)
    'translate(0 1) scale(1 0.92) translate(0 {1})'     # 5: squat (vertical squish at feet)
)

function Get-InnerSvg {
    param([string]$Path)
    $raw = Get-Content -Raw -Path $Path
    # Strip XML decl + outer <svg ...> ... </svg>, return only the inner
    # markup so we can re-wrap it in a per-frame <g transform="...">.
    if ($raw -notmatch '(?si)<svg\b[^>]*>(.*)</svg\s*>') {
        throw "No <svg> root found in $Path"
    }
    return $Matches[1].Trim()
}

foreach ($pet in $pets) {
    $idlePath = Join-Path $root ('src/main/resources/Sprites/' + $pet.idle)
    if (-not (Test-Path -LiteralPath $idlePath)) {
        Write-Warning "missing idle source $idlePath, skipping $($pet.name)"
        continue
    }
    $inner = Get-InnerSvg -Path $idlePath
    $danceDir = Join-Path $root ("src/main/resources/Sprites/$($pet.name)/Dance")
    if (-not (Test-Path -LiteralPath $danceDir)) {
        New-Item -ItemType Directory -Path $danceDir | Out-Null
    }
    for ($i = 0; $i -lt $frames.Count; $i++) {
        $tf = [string]::Format($frames[$i], $pet.cx, $pet.feetY, $pet.vb)
        # Frame 5 ("squat") uses the special form
        # `translate(0 1) scale(1 0.92) translate(0 {1})` — applied
        # left-to-right that squashes vertically around the FEET (translate
        # the feet line to origin, scale-Y, translate back, plus a 1px
        # downward nudge so the squashed shape doesn't reveal a gap above
        # the floor). Recompose explicitly so the math is obvious:
        if ($i -eq 5) {
            $tf = "translate(0 1) translate(0 $($pet.feetY)) scale(1 0.92) translate(0 -$($pet.feetY))"
        }
        $vb = $pet.vb
        $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb">
  <g transform="$tf">
$inner
  </g>
</svg>
"@
        $out = Join-Path $danceDir ("tile{0:000}.svg" -f $i)
        # Write UTF-8 WITHOUT BOM — javac would not be affected here but
        # SVG parsers are picky about a BOM appearing before <?xml?>.
        [System.IO.File]::WriteAllText($out, $svg, [System.Text.UTF8Encoding]::new($false))
        Write-Host "  wrote $($pet.name)/Dance/tile$('{0:000}' -f $i).svg"
    }
}
Write-Host "done."

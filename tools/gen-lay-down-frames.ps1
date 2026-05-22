#requires -Version 5
<#
.SYNOPSIS
    Generate a single "lay-down" SVG frame per pet by wrapping the pet's
    idle sprite in a vertical-squash transform pivoted at the feet, so the
    whole body compresses toward the floor — reading as "lying down" /
    "sphinx pose" without needing per-species hand-drawn art.

.NOTES
    Same trick as gen-dance-frames.ps1: the sealed traced paths live
    inside an outer scale/translate group, so we treat the entire idle
    body as a unit and apply a fresh viewBox-space transform on top of
    it. Scale-Y is 0.55 (vs dance's 0.92 squat) to make the pose clearly
    "on the ground" rather than just a quick squat beat.

    Run from the repo root: pwsh -File tools/gen-lay-down-frames.ps1
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$pets = @(
    @{ name='Cat';   idle='Cat/Idle/tile000.svg';   vb=32; feetY=29; method='squash' },
    @{ name='Dog';   idle='Dog/Idle/tile000.svg';   vb=48; feetY=38; method='clip';   cutY=32 },
    @{ name='Ducky'; idle='Ducky/Idle/tile000.svg'; vb=64; feetY=56; method='squash' },
    @{ name='Bird';  idle='Bird/Idle/tile001.svg'; vb=32; feetY=28; method='squash' }
)

# 'squash' method: vertical squash pivoted at the feet line: translate-to-origin,
# scale-Y, translate-back. The leading `translate(0 1)` nudges the
# squashed shape one pixel down so it doesn't reveal a hairline gap
# above the floor on rounding.
$squash = 0.55

# 'clip' method (used for Dog): rather than squashing (which leaves
# proportionally-shorter but still-visible standing legs), clip away the
# leg region and slide the remaining head/torso down so its cut edge
# rests on the floor. The result reads as "lying on belly, legs tucked".

function Get-InnerSvg {
    param([string]$Path)
    $raw = Get-Content -Raw -Path $Path
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
    $outDir = Join-Path $root ("src/main/resources/Sprites/$($pet.name)/LayDown")
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Path $outDir | Out-Null
    }
    $vb = $pet.vb
    if ($pet.method -eq 'clip') {
        $cutY = $pet.cutY
        $drop = $pet.feetY - $cutY
        $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb">
  <defs>
    <clipPath id="bodyOnly" clipPathUnits="userSpaceOnUse">
      <rect x="0" y="0" width="$vb" height="$cutY"/>
    </clipPath>
  </defs>
  <g transform="translate(0 $drop)">
    <g clip-path="url(#bodyOnly)">
$inner
    </g>
  </g>
</svg>
"@
    } else {
        $tf = "translate(0 1) translate(0 $($pet.feetY)) scale(1 $squash) translate(0 -$($pet.feetY))"
        $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb">
  <g transform="$tf">
$inner
  </g>
</svg>
"@
    }
    $out = Join-Path $outDir "tile000.svg"
    [System.IO.File]::WriteAllText($out, $svg, [System.Text.UTF8Encoding]::new($false))
    Write-Host "  wrote $($pet.name)/LayDown/tile000.svg"
}
Write-Host "done."

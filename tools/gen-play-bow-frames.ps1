#requires -Version 5
<#
.SYNOPSIS
    Generate a single "play-bow" SVG frame per quadruped pet by rotating
    the pet's idle sprite forward around the feet — front low / hind high,
    the classic dog/cat "invite to play" signal.

.NOTES
    Same trick as gen-dance-frames.ps1 / gen-lay-down-frames.ps1: wrap the
    idle body in a viewBox-space transform so the entire traced figure
    leans. Positive degrees in our coordinate system tilt the body toward
    its facing direction (right) — i.e. front-low. Ducky is skipped on
    purpose: its low-slung body can't bow visually, so its INVITE_PLAY
    code path bops up and down instead. Bird is skipped: birds don't
    play-bow.
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$pets = @(
    @{ name='Cat'; idle='Cat/Idle/tile000.svg'; vb=32; cx=16; feetY=29 },
    @{ name='Dog'; idle='Dog/Idle/tile000.svg'; vb=48; cx=24; feetY=38 }
)

$angle = 15

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
    $outDir = Join-Path $root ("src/main/resources/Sprites/$($pet.name)/PlayBow")
    if (-not (Test-Path -LiteralPath $outDir)) {
        New-Item -ItemType Directory -Path $outDir | Out-Null
    }
    $vb = $pet.vb
    # tile000.svg = right-facing bow: rotate forward (toward the right
    # side of the viewBox). tile001.svg = left-facing bow: mirror the
    # whole body across the viewBox center, then apply the same forward
    # rotation. Because the inner transform's anchor (cx) sits on the
    # mirror axis, the rotation still pivots at the feet.
    $tfRight = "rotate($angle $($pet.cx) $($pet.feetY))"
    $tfLeft  = "translate($vb 0) scale(-1 1) rotate($angle $($pet.cx) $($pet.feetY))"
    foreach ($variant in @(
            @{ idx=0; tf=$tfRight; label='right' },
            @{ idx=1; tf=$tfLeft;  label='left'  })) {
        $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb">
  <g transform="$($variant.tf)">
$inner
  </g>
</svg>
"@
        $out = Join-Path $outDir ("tile{0:000}.svg" -f $variant.idx)
        [System.IO.File]::WriteAllText($out, $svg, [System.Text.UTF8Encoding]::new($false))
        Write-Host "  wrote $($pet.name)/PlayBow/tile$('{0:000}' -f $variant.idx).svg ($($variant.label))"
    }
}
Write-Host "done."

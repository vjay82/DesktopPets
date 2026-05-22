#requires -Version 5
<#
.SYNOPSIS
    Generate 3 dog "scratch" frames derived from the dog sit sprite by
    wrapping its sealed inner SVG in viewBox-space rocking-tilt
    transforms pivoted at the feet line. Same trick as
    gen-dance-frames.ps1 / gen-lay-down-frames.ps1.

.NOTES
    The previous Dog/Scratch/scratch.svg was a one-off hand-drawn pose
    that didn't visually match the dog's normal sprites (different
    body proportions, extra props). This generator re-derives the
    scratch pose from the actual dog sit art so it always reads as
    "the same dog, sitting on its haunches, jittering" rather than
    "different cartoon".

    Run from the repo root:
        powershell -NoProfile -ExecutionPolicy Bypass -File tools/gen-dog-scratch-frames.ps1
#>

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$basePath = Join-Path $root 'src/main/resources/Sprites/Dog/Sit/tile000.svg'
$outDir   = Join-Path $root 'src/main/resources/Sprites/Dog/Scratch'
$vb       = 48
$cx       = 24
$feetY    = 38

function Get-InnerSvg {
    param([string]$Path)
    $raw = Get-Content -Raw -Path $Path
    if ($raw -notmatch '(?si)<svg\b[^>]*>(.*)</svg\s*>') {
        throw "No <svg> root found in $Path"
    }
    return $Matches[1].Trim()
}

if (-not (Test-Path -LiteralPath $basePath)) {
    throw "missing base sprite: $basePath"
}
if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$inner = Get-InnerSvg -Path $basePath

# Rocking tilt frames pivoted at the feet (cx,feetY) so the rump stays
# planted on the floor while the head/shoulder oscillates. The small
# vertical bob (translate-y +/- 1) reinforces the "jitter" of an itch
# scratch. 3 frames at ~150ms gives a ~0.45s loop that reads clearly
# as scratching when combined with the paw emote bobbing above.
$frames = @(
    "translate(0 -1) rotate(-7 $cx $feetY)",   # lean left + lift
    "translate(0 1) rotate(4 $cx $feetY)",     # settle right + drop
    "translate(0 -1) rotate(-5 $cx $feetY)"    # lean left again
)

for ($i = 0; $i -lt $frames.Count; $i++) {
    $tf  = $frames[$i]
    $name = ("tile{0:000}.svg" -f $i)
    $svg = @"
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $vb $vb" width="$vb" height="$vb">
  <g transform="$tf">
$inner
  </g>
</svg>
"@
    $out = Join-Path $outDir $name
    [System.IO.File]::WriteAllText($out, $svg, [System.Text.UTF8Encoding]::new($false))
    Write-Host "  wrote Dog/Scratch/$name"
}
Write-Host "done."

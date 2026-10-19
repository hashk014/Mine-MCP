$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$videoRoot = $PSScriptRoot
$cueList = Get-Content -LiteralPath (Join-Path $videoRoot 'narration.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$voice.SelectVoice('Microsoft Zira Desktop')
$voice.Rate = 1
$voice.Volume = 100
try {
    foreach ($cue in $cueList) {
        $wavePath = Join-Path $videoRoot ('public\audio\' + $cue.id + '.wav')
        $voice.SetOutputToWaveFile($wavePath)
        $voice.Speak($cue.line)
        $voice.SetOutputToNull()
        Write-Output ('Narration rendered: ' + $cue.id)
    }
} finally { $voice.Dispose() }

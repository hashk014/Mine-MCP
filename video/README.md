# Mine MCP demonstration

Editable Remotion composition: seven scenes, 1920×1080, 30 fps, 1501 frames. The final H.264/AAC MP4 is a [release asset](https://github.com/hashk014/Mine-MCP/releases/download/v0.1.1-alpha.1/mine-mcp-demo.mp4); English and French SRT files accompany it.

The captures come from the real Minecraft 1.21.11 client with Mine MCP 0.1.1. A scripted MCP client opens a chest, reads its slots, transfers 12 diamonds and reads the resulting inventory. See [the evidence](../docs/media/demo-evidence.json) and [capture script](../scripts/capture_demo.py). Explanatory cards are motion graphics, not additional in-game UI. This does not claim autonomous model gameplay.

## Render

Node.js 18+ and Chrome/Chromium are required. Set `REMOTION_BROWSER_EXECUTABLE` if Chrome is not installed at the default Windows path. FFmpeg/ffprobe must be on PATH for audio preparation and validation.

```sh
npm ci --prefix video --ignore-scripts
node video/prepare.mjs
node video/render.mjs --stills
node video/render.mjs
ffprobe -v error -show_streams -show_format docs/media/mine-mcp-demo.mp4
ffmpeg -v error -i docs/media/mine-mcp-demo.mp4 -f null -
```

The committed WAV files make rendering independent of speech services. To regenerate the narration on Windows, use `powershell -NoProfile -File video/make_audio.ps1` (Microsoft Zira Desktop voice), then run `prepare.mjs`. The composition's Zod schema exposes brand and CTA props. All animation uses Remotion frames and deterministic timing.

Inspect the stills before encoding, including frame 0 and the final frame. Re-capture gameplay only in a disposable singleplayer instance: `scripts/capture_demo.py` deliberately changes its test world and inventory using normal permissioned commands.

## Credits and validation

Original cube/network logo, layout, typography treatment and animation authored for Mine MCP. Gameplay images depict Minecraft, © Mojang/Microsoft; this project is unofficial and unaffiliated. Font stack uses Arial and Consolas from the rendering machine; fonts are not redistributed. Narration uses the installed Windows speech engine. No third-party artwork, soundtrack or template is bundled.

Workflow guidance follows the MIT-licensed [iart.ai motion-skills](https://github.com/iart-ai/motion-skills), specifically motion-art-direction, remotion-video and explainer-video. The editable composition and logo are covered by this repository's MIT license; Minecraft imagery remains subject to its owners' rights.

Final output verified with ffprobe: 1920×1080, H.264 video, AAC narration, 30 fps, 50.048 seconds; complete FFmpeg decode passed without errors. Nine storyboard frames were visually inspected for readable captions, correct evidence, and meaningful opening/closing frames. See [media validation](../docs/media/validation.json).

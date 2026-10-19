import {bundle} from '@remotion/bundler';
import {selectComposition,renderStill,renderMedia} from '@remotion/renderer';
import {readFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const timing=JSON.parse(await readFile(path.join(root,'src/timing.json'),'utf8'));
const serveUrl=await bundle({entryPoint:path.join(root,'src/index.jsx'),publicDir:path.join(root,'public')});
const browserExecutable=process.env.REMOTION_BROWSER_EXECUTABLE || (process.platform==='win32'?'C:/Program Files/Google/Chrome/Application/chrome.exe':undefined);
const inputProps={brand:'Mine MCP',cta:'Get the alpha on CurseForge'};
const composition=await selectComposition({serveUrl,id:'MineMCPDemo',inputProps,browserExecutable});
const out=path.join(root,'out');await mkdir(out,{recursive:true});
if(process.argv.includes('--stills')){
 const frames=[0,...timing.cues.map(c=>c.from+Math.min(c.duration-20,65)),timing.durationInFrames-1];
 for(const frame of frames){await renderStill({serveUrl,composition,inputProps,browserExecutable,frame,output:path.join(out,`frame-${String(frame).padStart(4,'0')}.png`),logLevel:'warn'}); console.log('Rendered still',frame);}
}else{
 await renderMedia({serveUrl,composition,inputProps,browserExecutable,codec:'h264',audioCodec:'aac',crf:18,pixelFormat:'yuv420p',outputLocation:path.join(root,'../docs/media/mine-mcp-demo.mp4'),concurrency:4,logLevel:'warn',onProgress:p=>{if(p.renderedFrames%150===0) console.log('Frames',p.renderedFrames,'/',timing.durationInFrames)}});
 console.log('Rendered docs/media/mine-mcp-demo.mp4');
}

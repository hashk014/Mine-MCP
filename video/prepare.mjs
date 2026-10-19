import {readFile, writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
const root=path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/,'$1'));
const cues=JSON.parse(await readFile(path.join(root,'narration.json'),'utf8'));
let from=0;
for(const cue of cues){
  const file=path.join(root,'public/audio',cue.id+'.wav');
  const seconds=Number(execFileSync('ffprobe',['-v','error','-show_entries','format=duration','-of','csv=p=0',file],{encoding:'utf8'}).trim());
  cue.from=from; cue.duration=Math.ceil(Math.max(cue.minSeconds,seconds+0.8)*30); cue.audioSeconds=seconds;
  from+=cue.duration;
}
await writeFile(path.join(root,'src/timing.json'),JSON.stringify({fps:30,width:1920,height:1080,durationInFrames:from,cues},null,2)+'\n');
function stamp(f,sep){const ms=Math.round(f/30*1000);return `${String(Math.floor(ms/3600000)).padStart(2,'0')}:${String(Math.floor(ms/60000)%60).padStart(2,'0')}:${String(Math.floor(ms/1000)%60).padStart(2,'0')}${sep}${String(ms%1000).padStart(3,'0')}`;}
for(const language of ['en','fr']){
 const srt=cues.map((c,i)=>`${i+1}\n${stamp(c.from+8,',')} --> ${stamp(c.from+c.duration-8,',')}\n${language==='en'?c.caption:c.fr}\n`).join('\n');
 await writeFile(path.join(root,`../docs/media/mine-mcp-demo.${language}.srt`),srt);
}
console.log(`Timed ${cues.length} scenes: ${from} frames / ${(from/30).toFixed(2)} sec`);

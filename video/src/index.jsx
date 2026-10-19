import React from 'react';
import {registerRoot,Composition} from 'remotion';
import {z} from 'zod';
import {Demo} from './video';
import timing from './timing.json';
const schema=z.object({brand:z.string(),cta:z.string()});
registerRoot(()=> <Composition id="MineMCPDemo" component={Demo} schema={schema} defaultProps={{brand:'Mine MCP',cta:'Get the alpha on CurseForge'}} durationInFrames={timing.durationInFrames} calculateMetadata={()=>({durationInFrames:timing.cues.reduce((n,c)=>n+c.duration,0)})} width={1920} height={1080} fps={30}/>);

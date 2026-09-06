/* UV assembly of the generated horror material, executed inside Blockbench through MCP. */
(() => {
  if (!__himImage.complete || !__himImage.naturalWidth) throw Error('Him master is not decoded');
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 256;
  const ctx = canvas.getContext('2d'); ctx.imageSmoothingEnabled = false;
  const parts = {head:[0,0,8,8,8],body:[16,16,8,12,4],right_arm:[40,16,4,12,4],
    left_arm:[32,48,4,12,4],right_leg:[0,16,4,12,4],left_leg:[16,48,4,12,4]};
  function faces([u,v,w,h,d]) { return {up:[u+d,v,w,d],down:[u+d+w,v,w,d],
    west:[u,v+d,d,h],north:[u+d,v+d,w,h],east:[u+d+w,v+d,d,h],south:[u+2*d+w,v+d,w,h]}; }
  const head = {up:[213,0,207,184],down:[243,340,147,42],west:[11,184,202,199],
    north:[213,184,207,199],east:[420,184,205,199],south:[630,196,205,187]};
  for (const [part,uv] of Object.entries(parts)) for (const [face,dst] of Object.entries(faces(uv))) {
    let src;
    if (part==='head') src=head[face];
    else if (part==='body') src=face==='up'||face==='down'?[527,394,200,104]:[492,500,260,317];
    else if (part.endsWith('arm')) src=face==='up'?[874,393,77,105]:face==='down'?[982,759,95,57]:[982,501,105,317];
    else src=face==='up'?[323,829,122,69]:face==='down'?[326,1168,115,63]:[322,905,124,326];
    ctx.drawImage(__himImage,...src,...dst.map(n=>n*4));
  }
  const glow=document.createElement('canvas');glow.width=glow.height=256;
  const gctx=glow.getContext('2d'),pixels=ctx.getImageData(0,0,256,256),mask=gctx.createImageData(256,256);
  let lit=0;
  for(let y=40;y<56;y++)for(let x=32;x<64;x++){
    const i=(y*256+x)*4;
    if(Math.min(pixels.data[i],pixels.data[i+1],pixels.data[i+2])>205){
      mask.data.set([225,236,229,228],i);lit++;
    }
  }
  if(lit<4||lit>80)throw Error('Invalid eye mask: '+lit);
  gctx.putImageData(mask,0,0);
  Texture.all[0].fromDataURL(canvas.toDataURL('image/png'));
  Project.texture_width=Project.texture_height=64;
  globalThis.__himPacked=canvas.toDataURL('image/png');globalThis.__himGlow=glow.toDataURL('image/png');
  Canvas.updateAll();
  return JSON.stringify({texture:256,logicalUV:64,eyePixels:lit});
})();

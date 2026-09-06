// Detailed, editable storm art prototype; not consumed by the game runtime.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const dir = path.resolve(__dirname, '../docs/art/world_interface');
const name = 'world_interface_storm_form1_detail_v2';
const elements = [], outliner = [];
let sequence = 0;
function uuid() {
  const h = crypto.createHash('md5').update(name + ':' + sequence++).digest('hex');
  return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-8${h.slice(17,20)}-${h.slice(20)}`;
}
let seed = 92461;
function rand() { seed = (1664525 * seed + 1013904223) >>> 0; return seed / 4294967296; }
const palette = [[31,29,38],[49,46,58],[64,58,72],[78,72,84],[112,106,116],[143,141,121],[113,49,166],[222,159,255],[42,38,53],[162,87,214],[16,12,24],[101,91,114]];
function crc32(b) { let c=0xffffffff; for(const a of b){c^=a;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^0xffffffff)>>>0; }
function chunk(type,data) { const t=Buffer.from(type), b=Buffer.alloc(12+data.length);b.writeUInt32BE(data.length);t.copy(b,4);data.copy(b,8);b.writeUInt32BE(crc32(Buffer.concat([t,data])),8+data.length);return b; }
const TILE=32,w=TILE*palette.length,h=TILE,pixels=Buffer.alloc(h*(w*4+1));
function noise(x,y,s){let n=Math.imul(x+211,374761393)^Math.imul(y+73,668265263)^Math.imul(s+19,1274126177);n=Math.imul(n^(n>>>13),1274126177);return ((n^(n>>>16))>>>0)/4294967296;}
for(let y=0;y<h;y++)for(let x=0;x<w;x++) {
  const tile=Math.floor(x/TILE),u=x%TILE,p=palette[tile],i=y*(w*4+1)+1+x*4;
  const coarse=(noise(Math.floor(u/5),Math.floor(y/4),tile)-0.5)*10;
  let value=coarse+(noise(u,y,tile)-0.5)*5;
  const crack=8+Math.floor(y/5)%3+Math.floor(y/11)*3;
  if(tile<6||tile===8||tile===11){if(u===crack||((y===20||y===21)&&u>crack&&u<26))value-=14;if(u===crack-1)value+=6;}
  if(tile===7)value=18-Math.max(Math.abs(u-15.5),Math.abs(y-15.5))*1.7;
  if(tile===6||tile===9)value+=(Math.sin(y/5)+Math.cos(u/7))*6;
  for(let k=0;k<3;k++)pixels[i+k]=Math.max(0,Math.min(255,p[k]+value));pixels[i+3]=255;
}
const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(w);ihdr.writeUInt32BE(h,4);ihdr[8]=8;ihdr[9]=6;
const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]);
function group(label,origin=[0,0,0],parent=null) {const g={name:label,origin,rotation:[0,0,0],uuid:uuid(),export:true,isOpen:false,visibility:true,children:[]};(parent?parent.children:outliner).push(g);return g;}
function cube(g,label,from,size,mat=1,rotation=[0,0,0],origin=from) {
  const faces={};for(const f of ['north','south','east','west','up','down']) {
    const a=(f==='east'||f==='west')?size[2]:size[0],b=(f==='up'||f==='down')?size[2]:size[1];
    faces[f]={uv:[mat*TILE+1,1,mat*TILE+Math.min(31,1+a*2),Math.min(31,1+b*2)],texture:0};
  }
  const e={name:label,type:'cube',uuid:uuid(),from:from.map(v=>+v.toFixed(3)),to:from.map((v,i)=>+(v+size[i]).toFixed(3)),origin:origin.map(v=>+v.toFixed(3)),rotation:rotation.map(v=>+v.toFixed(3)),box_uv:false,rescale:false,autouv:0,color:mat%8,faces};
  elements.push(e);g.children.push(e.uuid);return e;
}
function bevel(g,label,from,size,mat=1,inset=0.6){
  const b=Math.min(inset,...size.map(v=>v*0.18)),[x,y,z]=from,[dx,dy,dz]=size;
  cube(g,label+'_core',[x+b,y+b,z],[dx-2*b,dy-2*b,dz],mat);
  cube(g,label+'_rim_x',[x,y+b,z+b],[dx,dy-2*b,dz-2*b],mat);
  cube(g,label+'_rim_y',[x+b,y,z+b],[dx-2*b,dy,dz-2*b],Math.min(mat+1,4));
}
const root=group('storm_form_01'), body=group('body_mass',[0,100,0],root);
cube(body,'inner_mass',[-26,76,-26],[52,60,52],0);
const shell=group('voxel_shell',[0,104,0],body);
const layers=[[68,24,22],[78,36,31],[88,44,36],[98,49,39],[108,47,38],[118,40,34],[128,32,27],[138,19,17]];
for(let l=0;l<layers.length;l++) {
  const [y,rx,rz]=layers[l];
  for(let x=-rx;x<rx;x+=10)for(let z=-rz;z<rz;z+=10) {
    const cx=x+5,cz=z+5,dist=(cx/rx)**2+(cz/rz)**2;
    if(dist>1.12)continue;
    if(dist<0.50 && l>0 && l<layers.length-1)continue;
    const lift=rand()*3,mat=rand()<0.27?0:(rand()<0.72?1:2);
    bevel(shell,`shell_${l}_${x}_${z}`,[x-0.5,y+lift,z-0.5],[10.8,11.4,10.8],mat,0.7);
    if(dist>0.78&&rand()<0.6){
      const rr=Math.hypot(cx,cz),nx=cx/rr,nz=cz/rr;
      const px=cx+nx*4.2,pz=cz+nz*4.2;
      const p=group(`shell_scale_${l}_${x}_${z}`,[px,y+4,pz],shell);p.rotation=[Math.round(nz*8),-Math.atan2(nx,nz)*180/Math.PI,Math.round(-nx*8)];
      bevel(p,'overlap_plate',[px-3,y+2,pz-1.0],[6.5,7,2.1],2,0.35);
    }
  }
}
const crust=group('swallowed_terrain',[0,104,0],body);
for(let i=0;i<64;i++) {
  const a=rand()*Math.PI*2,y=79+rand()*59,t=(y-105)/43;
  const r=Math.sqrt(Math.max(0.12,1-t*t));
  const x=Math.cos(a)*46*r,z=Math.sin(a)*37*r;
  const s=3+rand()*5;
  const rock=group(`terrain_fragment_${i}`,[x,y,z],crust);rock.rotation=[0,Math.round(rand()*3)*15,0];
  bevel(rock,'fractured_rock',[x-s/2,y,z-s/2],[s,3+rand()*7,s],i%8===0?5:3,0.45);
  if(i%3===0)cube(rock,'broken_edge',[x-s/2+0.6,y+2,z-s/2-0.35],[s*0.52,0.55,s*0.3],i%8===0?4:2);
}
const crown=group('asymmetric_crown',[0,133,0],body);
for(const [x,y,z,s,ht] of [[-19,132,10,12,18],[-10,141,5,9,16],[4,141,0,11,10],[18,131,13,12,16],[27,119,22,10,15],[-35,111,9,9,15]])
  {bevel(crown,'torn_crown',[x,y,z],[s,ht,s],1,1);bevel(crown,'fractured_cap',[x+1.3,y+ht-1,z+1.3],[s*0.65,3,s*0.65],2,0.45);}
const heads=group('heads',[0,88,-25],root);
function head(label,cx,cy,cz,s,yaw) {
  const g=group(label,[cx,cy,cz+7*s],heads);g.rotation=[0,yaw,0];
  const q=(n,x,y,z,dx,dy,dz,m=2)=>cube(g,n,[cx+x*s,cy+y*s,cz+z*s],[dx*s,dy*s,dz*s],m);
  const bq=(n,x,y,z,dx,dy,dz,m=2,b=0.45)=>bevel(g,n,[cx+x*s,cy+y*s,cz+z*s],[dx*s,dy*s,dz*s],m,b*s);
  bq('short_neck',-5,-2,6,10,10,13,0,0.7);
  for(let i=0;i<3;i++)bq('vertebra_'+i,-5.6,-1+i*0.6,7+i*3.5,11.2,9,1.5,1,0.25);
  bq('cranium',-10,5,-5,20,10,14,2,0.8);
  bq('crown',-8,14,-3,16,2,10,1,0.5);
  bq('forehead',-8.5,9,-8.1,17,4.2,3.5,2);
  q('suture_vertical',-0.35,10,-8.25,0.65,4,0.35,0);
  q('suture_cross',-3,11.4,-8.3,6,0.5,0.35,0);
  for(const side of [-1,1]) {
    bq('temple_'+side,side<0?-10.5:7.7,0,-5.7,2.8,11.5,9.7,1,0.4);
    bq('brow_'+side,side<0?-10:-0.1,7.3,-9.1,10.1,3,3,1,0.5);
    q('socket_'+side,side<0?-8.4:2.2,2.2,-6.5,6.2,5.3,1.0,10);
    q('iris_glow_'+side,side<0?-7.4:3,3.7,-7.1,4.4,1.8,0.55,6);
    q('eye_slit_'+side,side<0?-7:3.4,4.2,-7.7,3.6,0.8,0.45,7);
    bq('lower_orbit_'+side,side<0?-9:2.2,1.0,-8,6.8,1.4,2,2,0.2);
    bq('cheekbone_'+side,side<0?-10.2:7,-3.5,-8,3.2,5.4,4,2,0.45);
    bq('jaw_hinge_'+side,side<0?-9.8:7.8,-8.3,-2,2,7,6,1,0.25);
    q('cheek_seam_'+side,side<0?-9.5:8.5,-1,-8.15,0.45,3,0.4,0);
  }
  bq('nose_bridge',-1.6,1,-8.8,3.2,7,3,1,0.3);
  q('nasal_cavity',-1.7,-0.7,-9,3.4,2.6,0.6,10);
  q('mouth_back',-6.5,-10,4.0,13,11.5,2,10);
  q('throat_aura',-4,-9,3.5,8,8,0.5,6);
  q('throat_inner',-2.5,-8.5,3,5,6,0.3,9);
  q('throat_light',-1.3,-7.5,2.8,2.6,3.5,0.2,7);
  for(let i=0;i<3;i++) {
    q('palate_'+i,-6.4,-0.5-i*0.3,-5+i*3,12.8,0.8,0.8,2);
    q('mouth_wall_l_'+i,-7.4+i*0.3,-9,-5+i*3,1.1,9,1.6,1);
    q('mouth_wall_r_'+i,6.3-i*0.3,-9,-5+i*3,1.1,9,1.6,1);
  }
  const jaw=group('jaw',[cx,cy-7*s,cz+4*s],g);jaw.rotation=[18,0,0];
  bevel(jaw,'lower_jaw',[cx-9*s,cy-12.5*s,cz-7.5*s],[18*s,3*s,13*s],2,0.6*s);
  bevel(jaw,'chin',[cx-6.5*s,cy-13*s,cz-8*s],[13*s,2.5*s,3*s],1,0.4*s);
  for(let k=0;k<6;k++) {
    const tx=-6.8+k*2.35,th=1.7+(k%3)*0.5;
    bq(`upper_tooth_${k}`,tx,-th,-7.5,1.35,th,1.6,4,0.18);
    bevel(jaw,`lower_tooth_${k}`,[cx+tx*s,cy-9.7*s,cz-6.8*s],[1.3*s,(1.5+(k%2)*0.6)*s,1.6*s],4,0.15*s);
  }
  for(let i=0;i<3;i++)bq('skull_side_plate_'+i,-10.4,4+i*2.4,-1+i*2,20.8,1.3,1.3,1,0.2);
  return g;
}
head('head_center',0,88,-47,1.14,0);
head('head_left',-29,83,-35,0.87,-16);
head('head_right',29,85,-35,0.87,16);
const limbs=group('main_tentacles',[0,72,0],root);
function chain(label,points,thickness,parent,mat=1) {
  let p=group(label,points[0],parent);
  const fine=[];
  for(let i=0;i<points.length-1;i++) {
    const a=points[Math.max(0,i-1)],b=points[i],c=points[i+1],d=points[Math.min(points.length-1,i+2)];
    for(let j=0;j<3;j++){const t=j/3;fine.push(b.map((v,k)=>0.5*((2*v)+(-a[k]+c[k])*t+(2*a[k]-5*v+4*c[k]-d[k])*t*t+(-a[k]+3*v-3*c[k]+d[k])*t*t*t)));}
  }
  fine.push(points.at(-1));
  for(let i=0;i<fine.length-1;i++) {
    const a=fine[i],b=fine[i+1],d=b.map((v,k)=>v-a[k]),len=Math.hypot(...d),t=thickness*Math.pow(1-i/fine.length,0.8);
    const g=group(`segment_${String(i).padStart(2,'0')}`,a,p);
    const pitch=Math.atan2(-d[2],-d[1])*180/Math.PI,roll=Math.asin(d[0]/len)*180/Math.PI;
    const segment=group('shell',[...a],g);segment.rotation=[pitch,0,roll];
    const inset=t*0.16;
    cube(segment,'taper_core',[a[0]-t/2+inset,a[1]-len-0.5,a[2]-t/2],[t-2*inset,len+1,t],mat);
    cube(segment,'taper_sides',[a[0]-t/2,a[1]-len-0.5,a[2]-t/2+inset],[t,len+1,t-2*inset],mat);
    if(thickness>5) {
      const ridge=t*0.73;
      cube(segment,'dorsal_scale',[a[0]-ridge/2,a[1]-len*0.8,a[2]+t*0.42],[ridge,len*0.65,t*0.12],i%3?1:2);
      cube(segment,'ventral_seam',[a[0]-t*0.31,a[1]-len*0.9,a[2]-t*0.51],[t*0.62,0.45,t*0.08],2);
      if(i%5===1)cube(segment,'violet_fissure',[a[0]+t*0.30,a[1]-len*0.72,a[2]-t*0.49],[Math.max(0.15,t*0.04),len*0.38,0.13],6);
    }
    p=g;
  }
}
chain('tentacle_front_left',[[-29,77,-23],[-39,66,-29],[-45,52,-32],[-47,37,-33],[-43,22,-38],[-34,11,-47],[-22,8,-52]],10,limbs);
chain('tentacle_front_right',[[28,77,-23],[38,65,-29],[42,50,-35],[43,34,-38],[50,21,-40],[60,17,-36],[65,23,-30]],10,limbs);
chain('tentacle_back_left',[[-27,77,20],[-38,66,28],[-44,51,32],[-49,35,34],[-53,19,30],[-59,12,23],[-65,16,17]],11,limbs,0);
chain('tentacle_back_right',[[27,77,20],[37,65,28],[41,51,30],[39,34,33],[32,20,34],[22,14,31],[17,18,27]],11,limbs,0);
const feelers=group('minor_feelers_decorative',[0,70,0],root);
for(let i=0;i<7;i++) {const x=-24+i*8,z=5+(i%3)*7;chain(`feeler_${i}`,[[x,72,z],[x+3,59,z+2],[x+5,48-i%3*3,z],[x+2,35-i%3*4,z-4],[x-4,27-i%3*4,z-2],[x-7,29-i%3*4,z+3]],3.5,feelers,0);}
const kernel=group('interface_kernel',[0,74,-27],root);
cube(kernel,'recess',[-4,70,-29],[8,8,4],0);cube(kernel,'muted_core',[-2,72,-29.5],[4,4,1],6);
const model={meta:{format_version:'4.5',model_format:'free',box_uv:false},name,model_identifier:name,resolution:{width:w,height:h},elements,outliner,textures:[{path:'',name:name+'_palette.png',id:'0',uuid:uuid(),particle:false,render_mode:'normal',source:'data:image/png;base64,'+png.toString('base64')}],animations:[]};
const ids=new Set(elements.map(e=>e.uuid));let groups=0,refs=0;
function validate(nodes){for(const n of nodes){if(typeof n==='string'){if(!ids.has(n))throw Error('Missing element');refs++;}else {groups++;validate(n.children);}}}
validate(outliner);if(refs!==elements.length)throw Error('Orphan element');
for(const e of elements){if(!e.from.every(Number.isFinite)||!e.rotation.every(Number.isFinite)||!e.to.every((v,i)=>v>e.from[i]))throw Error('Invalid cube '+e.name);}
fs.mkdirSync(dir,{recursive:true});
fs.writeFileSync(path.join(dir,name+'.bbmodel'),JSON.stringify(model,null,2));
fs.writeFileSync(path.join(dir,name+'_palette.png'),png);
fs.writeFileSync(path.join(dir,name+'_manifest.json'),JSON.stringify({purpose:'Detailed art prototype; not runtime model',elements:elements.length,groups,heads:3,mainTentacles:4,minorFeelers:7,texture:[w,h],unit:'Blockbench units; 16 units per block before any future runtime scale',generator:'tools/build_world_interface_storm_detail.cjs'},null,2));
console.log(JSON.stringify({path:path.join(dir,name+'.bbmodel'),elements:elements.length,groups,heads:3,mainTentacles:4}));

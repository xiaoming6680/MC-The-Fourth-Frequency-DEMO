// Cumulative phase 2/3 art models with native Blockbench preview animations.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const dir=path.resolve(__dirname,'../docs/art/world_interface');
const base=JSON.parse(fs.readFileSync(path.join(dir,'world_interface_storm_form1_detail_v2.bbmodel'),'utf8'));
const m=structuredClone(base),root=m.outliner[0];
let seq=0,seed=72693;
const rnd=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
function id(){const h=crypto.createHash('md5').update('storm-phases-v2:'+seq++).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-8${h.slice(17,20)}-${h.slice(20)}`;}
function group(name,origin,parent=root){const g={name,origin,rotation:[0,0,0],uuid:id(),export:true,isOpen:false,visibility:true,children:[]};parent.children.push(g);return g;}
function cube(g,name,from,size,mat=1){const faces={};for(const f of ['north','south','east','west','up','down']){const a=/east|west/.test(f)?size[2]:size[0],b=/up|down/.test(f)?size[2]:size[1];faces[f]={texture:0,uv:[mat*32+1,1,mat*32+Math.min(31,1+a*2),Math.min(31,1+b*2)]};}const e={name,type:'cube',uuid:id(),from:from.map(v=>+v.toFixed(4)),to:from.map((v,i)=>+(v+size[i]).toFixed(4)),origin:from.map(v=>+v.toFixed(4)),rotation:[0,0,0],box_uv:false,autouv:0,rescale:false,color:mat%8,faces};m.elements.push(e);g.children.push(e.uuid);}
function bevel(g,n,p,s,mat=1,b=.7){b=Math.min(b,...s.map(v=>v*.18));const[x,y,z]=p,[a,c,d]=s;cube(g,n+'_core',[x+b,y+b,z],[a-2*b,c-2*b,d],mat);cube(g,n+'_sides',[x,y+b,z+b],[a,c-2*b,d-2*b],mat);cube(g,n+'_edge',[x+b,y,z+b],[a-2*b,c,d-2*b],Math.min(mat+1,4));}
function accrete(g,layers,cx=0,cz=12){
  for(let l=0;l<layers.length;l++){const[y,rx,rz]=layers[l];for(let x=-rx;x<rx;x+=11)for(let z=-rz;z<rz;z+=11){const d=((x+5.5)/rx)**2+((z+5.5)/rz)**2;if(d>1.04)continue;if(d<.57&&l>0&&l<layers.length-1)continue;const px=x+cx,pz=z+cz;if(y<132&&pz<-18&&Math.abs(px)<44)continue;
    bevel(g,`mass_${l}_${x}_${z}`,[px,y+rnd()*2.3,pz],[11.5,11.8,11.5],rnd()<.35?0:1,.8);
    if(d>.8&&rnd()<.4){const scale=group(`broken_scale_${l}_${x}_${z}`,[px+5,y+4,pz+5],g);scale.rotation=[(rnd()-.5)*16,(rnd()-.5)*25,(rnd()-.5)*12];bevel(scale,'scale',[px+1,y+1,pz-1],[8,9,2.5],2,.45);}
  }}
}
function spur(parent,label,a,b,width){const d=b.map((v,i)=>v-a[i]),len=Math.hypot(...d),g=group(label,a,parent);g.rotation=[Math.atan2(-d[2],-d[1])*180/Math.PI,0,Math.asin(d[0]/len)*180/Math.PI];bevel(g,'root',[a[0]-width/2,a[1]-len*.50,a[2]-width/2],[width,len*.52,width],1,.7);bevel(g,'fracture',[a[0]-width*.33,a[1]-len*.82,a[2]-width*.33],[width*.66,len*.35,width*.66],2,.45);bevel(g,'tip',[a[0]-width*.18,a[1]-len,a[2]-width*.18],[width*.36,len*.24,width*.36],1,.3);}
function terrain(g,phase,count){for(let i=0;i<count;i++){const a=rnd()*Math.PI*2,y=phase===2?126+rnd()*54:150+rnd()*67,rx=phase===2?53:72,rz=phase===2?41:56;const x=Math.cos(a)*rx,z=Math.sin(a)*rz+20,w=3+rnd()*6;const p=group(`terrain_${phase}_${i}`,[x,y,z],g);p.rotation=[(rnd()-.5)*18,(rnd()-.5)*40,(rnd()-.5)*15];bevel(p,'fragment',[x-w/2,y,z-w/2],[w,4+rnd()*9,w],i%5===0?5:3,.5);if(i%4===0){cube(p,'stratum',[x-w/2,y+3,z-w/2-.25],[w,.8,w*.25],4);cube(p,'purple_seam',[x+.3,y+.4,z-w/2-.3],[.5,3,.25],6);}}}
const limbGroup=root.children.find(g=>g.name==='main_tentacles');
function chain(label,points,width){const limb=group(label,points[0],limbGroup);let parent=limb;const fine=[];for(let i=0;i<points.length-1;i++){const a=points[Math.max(0,i-1)],b=points[i],c=points[i+1],d=points[Math.min(points.length-1,i+2)];for(let j=0;j<3;j++){const t=j/3;fine.push(b.map((v,k)=>.5*(2*v+(-a[k]+c[k])*t+(2*a[k]-5*v+4*c[k]-d[k])*t*t+(-a[k]+3*v-3*c[k]+d[k])*t*t*t)));}}fine.push(points.at(-1));
  for(let i=0;i<fine.length-1;i++){const a=fine[i],b=fine[i+1],d=b.map((v,k)=>v-a[k]),len=Math.hypot(...d),t=width*Math.pow(1-i/fine.length,.8);const bone=group(`segment_${String(i).padStart(2,'0')}`,a,parent),shell=group('shell',a,bone);shell.rotation=[Math.atan2(-d[2],-d[1])*180/Math.PI,0,Math.asin(d[0]/len)*180/Math.PI];cube(shell,'inner',[a[0]-t*.34,a[1]-len-.4,a[2]-t*.5],[t*.68,len+.8,t],0);cube(shell,'sides',[a[0]-t*.5,a[1]-len-.4,a[2]-t*.34],[t,len+.8,t*.68],1);cube(shell,'dorsal_scale',[a[0]-t*.36,a[1]-len*.8,a[2]+t*.43],[t*.72,len*.6,t*.11],2);cube(shell,'ventral_seam',[a[0]-t*.3,a[1]-len*.88,a[2]-t*.52],[t*.6,.45,t*.08],2);if(i%5===1)cube(shell,'fissure',[a[0]+t*.28,a[1]-len*.7,a[2]-t*.51],[Math.max(.12,t*.04),len*.4,.15],6);parent=bone;}return limb;
}
function animation(name,length,loop=false){return {uuid:id(),name,loop:loop?'loop':'once',override:false,length,snapping:24,selected:false,anim_time_update:'',blend_weight:'',start_delay:'',loop_delay:'',animators:{}};}
function channel(a,g,c,frames){const b=a.animators[g.uuid]??={name:g.name,type:'bone',rotation_global:false,quaternion_interpolation:false,keyframes:[]};for(const [time,v]of frames)b.keyframes.push({channel:c,data_points:[{x:String(v[0]),y:String(v[1]),z:String(v[2])}],uuid:id(),time,color:-1,interpolation:'catmullrom'});}
function wave(a,g,c,amp,phase=0,offset=[0,0,0]){const frames=[];for(let i=0;i<=8;i++){const value=Math.sin(i/8*Math.PI*2+phase);frames.push([i/8*a.length,amp.map((v,k)=>+(offset[k]+v*value).toFixed(4))]);}frames.at(-1)[1]=[...frames[0][1]];channel(a,g,c,frames);}
function makeAnimations(phase,newGrowth){
  const heads=root.children.find(g=>g.name==='heads').children.filter(g=>typeof g==='object');
  const limbs=limbGroup.children.filter(g=>typeof g==='object');
  const idle=animation(`animation.world_interface.form${phase}.idle`,6,true);
  wave(idle,root,'position',[0,1.3+phase*.3,0]);wave(idle,root,'rotation',[.35,.55,.4]);
  heads.forEach((h,i)=>{wave(idle,h,'rotation',[1.1,3.2,1],i*2);wave(idle,h.children.find(g=>g.name==='jaw'),'rotation',[2.2,0,0],i*1.5);});
  limbs.forEach((l,i)=>{wave(idle,l,'rotation',[1.8,1.1,(i%2?1:-1)*2.1],i*.83);let s=l.children.find(g=>g.name==='segment_00');let j=0;while(s){if(j%4===2)wave(idle,s,'rotation',[.7,0,.8],i*.83-j*.22);s=s.children.find(g=>typeof g==='object'&&/^segment_/.test(g.name));j++;}});
  const charge=animation(`animation.world_interface.form${phase}.charge`,3);
  channel(charge,root,'position',[[0,[0,0,0]],[1,[0,2,1]],[2.2,[0,3,2]],[2.55,[0,0,0]],[3,[0,0,0]]]);
  heads.forEach((h,i)=>{const aim=i===1?12:i===2?-12:0;channel(charge,h,'rotation',[[0,[0,0,0]],[.65,[5,aim*.25,0]],[2.1,[-5,aim,0]],[2.4,[-11,aim,0]],[3,[0,0,0]]]);channel(charge,h.children.find(g=>g.name==='jaw'),'rotation',[[0,[0,0,0]],[.8,[-12,0,0]],[2.1,[-24,0,0]],[2.45,[-27,0,0]],[3,[0,0,0]]]);});
  limbs.forEach((l,i)=>channel(charge,l,'rotation',[[0,[0,0,0]],[1.2,[3,0,(i%2?1:-1)*4]],[2.3,[5,0,(i%2?1:-1)*6]],[3,[0,0,0]]]));
  const sweep=animation(`animation.world_interface.form${phase}.tentacle_sweep`,2.4);
  const active=limbs.find(g=>g.name==='tentacle_front_right');channel(sweep,active,'rotation',[[0,[0,0,0]],[.7,[8,-6,-12]],[1.15,[-5,9,14]],[1.55,[-2,4,7]],[2.4,[0,0,0]]]);
  channel(sweep,heads[0],'rotation',[[0,[0,0,0]],[.7,[0,-5,0]],[1.2,[-4,5,0]],[2.4,[0,0,0]]]);
  const hit=animation(`animation.world_interface.form${phase}.hit_reaction`,1.1);channel(hit,root,'rotation',[[0,[0,0,0]],[.12,[-1,0,1.8]],[.38,[.7,0,-.8]],[1.1,[0,0,0]]]);heads.forEach(h=>channel(hit,h,'rotation',[[0,[0,0,0]],[.12,[5,0,0]],[.4,[-2,0,0]],[1.1,[0,0,0]]]));
  const morph=animation(`animation.world_interface.form${phase}.accretion`,4);
  channel(morph,newGrowth,'scale',[[0,[.04,.04,.04]],[1,[.25,.3,.25]],[2.8,[.92,.96,.92]],[4,[1,1,1]]]);
  const added=phase===2?limbs.slice(4):limbs.slice(6);added.forEach((l,i)=>channel(morph,l,'scale',[[0,[.04,.04,.04]],[.5+i*.15,[.07,.12,.07]],[2.6+i*.2,[.9,.96,.9]],[4,[1,1,1]]]));
  return [idle,charge,sweep,hit,morph];
}
function save(phase,growth){const name=`world_interface_storm_form${phase}_detail_v2`;m.name=m.model_identifier=name;m.animations=makeAnimations(phase,growth);const copy=structuredClone(m);copy.outliner[0].name='storm_root';const nodes=new Map();let refs=[];function walk(ns){for(const n of ns){if(typeof n==='string')refs.push(n);else{nodes.set(n.uuid,n);walk(n.children);}}}walk(copy.outliner);const ids=new Set(copy.elements.map(e=>e.uuid));if(refs.length!==ids.size||refs.some(x=>!ids.has(x))||new Set(refs).size!==refs.length)throw Error('Invalid hierarchy');for(const e of copy.elements){if(!e.to.every((v,i)=>Number.isFinite(v)&&v>e.from[i]))throw Error('Invalid cube');}
  for(const a of copy.animations)for(const [uuid,b]of Object.entries(a.animators)){if(!nodes.has(uuid))throw Error('Missing animator target');for(const k of b.keyframes){if(k.time<0||k.time>a.length||!Object.values(k.data_points[0]).every(v=>Number.isFinite(Number(v))))throw Error('Invalid key');}if(a.loop==='loop'){for(const c of ['position','rotation','scale']){const keys=b.keyframes.filter(k=>k.channel===c).sort((x,y)=>x.time-y.time);if(keys.length&&JSON.stringify(keys[0].data_points)!==JSON.stringify(keys.at(-1).data_points))throw Error('Loop seam');}}}
  const inherited=base.elements.every(e=>copy.elements.some(x=>x.uuid===e.uuid&&JSON.stringify(x)===JSON.stringify(e)));if(!inherited)throw Error('Base geometry changed');
  copy.meta.format_version='5.0'; copy.groups=[...nodes.values()].map(({children,...g})=>({...g,children:[]})); function refsOnly(ns){return ns.map(n=>typeof n==='string'?n:{uuid:n.uuid,isOpen:n.isOpen,children:refsOnly(n.children)});} copy.outliner=refsOnly(copy.outliner);
  fs.writeFileSync(path.join(dir,name+'.bbmodel'),JSON.stringify(copy,null,2));
  const manifest={phase,cubes:copy.elements.length,groups:nodes.size,heads:3,mainTentacles:limbGroup.children.length,inheritedBaseCubes:base.elements.length,baseGeometryUnchanged:inherited,animations:copy.animations.map(a=>({name:a.name,length:a.length,loop:a.loop,animatedBones:Object.keys(a.animators).length})),format:'free',runtimeIntegrated:false};fs.writeFileSync(path.join(dir,name+'_manifest.json'),JSON.stringify(manifest,null,2));console.log(JSON.stringify(manifest));return copy;
}
const phase2=group('phase_2_accretion',[0,119,17]);
accrete(phase2,[[110,48,38],[121,55,43],[132,59,46],[143,57,45],[154,49,40],[165,39,33],[176,24,24]],0,15);
terrain(phase2,2,44);
for(const [i,a,b,w]of [[0,[-42,139,20],[-63,163,17],12],[1,[34,145,24],[53,170,28],13],[2,[-14,169,21],[-25,198,25],12],[3,[15,162,38],[21,181,62],11]])spur(phase2,'accretion_spur_'+i,a,b,w);
chain('tentacle_phase2_left',[[-49,112,13],[-65,96,12],[-75,76,9],[-83,54,2],[-88,31,-10],[-84,13,-24],[-71,5,-30]],12);
chain('tentacle_phase2_right',[[48,110,18],[67,96,16],[79,79,8],[92,61,0],[98,40,-9],[93,22,-20],[79,16,-22]],12);
const form2=save(2,phase2);
const phase3=group('phase_3_rupture',[0,153,27]);
accrete(phase3,[[143,61,46],[154,70,52],[165,75,56],[176,74,58],[187,65,53],[198,54,46],[209,38,34],[220,23,23]],-7,29);
terrain(phase3,3,62);
for(const[i,a,b,w]of [[0,[-48,177,18],[-92,206,16],15],[1,[46,177,28],[92,207,34],16],[2,[-17,209,20],[-39,244,28],14],[3,[15,201,37],[38,238,56],15],[4,[50,165,53],[92,180,83],13],[5,[-57,164,40],[-89,178,64],13],[6,[-4,191,73],[9,217,98],12]])spur(phase3,'rupture_spur_'+i,a,b,w);
const fissures=group('rupture_fissures',[-8,181,-21],phase3);
for(let i=0;i<8;i++){const x=-35+i*9,y=158+(i%3)*10,z=-25+(i%2)*3;const rib=group('fissure_'+i,[x,y,z],fissures);rib.rotation=[0,0,(i%2?1:-1)*12];cube(rib,'shadow',[x-1.2,y,z],[2.4,15,1],10);cube(rib,'violet',[x-.25,y+1,z-.2],[.5,11,.3],6);bevel(rib,'broken_lip',[x+1,y+3,z-.3],[1.7,9,1.4],2,.2);}
chain('tentacle_phase3_left_high',[[-64,158,24],[-88,138,18],[-107,111,8],[-117,81,-8],[-119,48,-25],[-109,19,-42],[-91,9,-48]],14);
chain('tentacle_phase3_right_high',[[63,155,28],[85,135,20],[105,109,8],[119,79,-8],[128,49,-22],[124,26,-37],[110,19,-40]],14);
chain('tentacle_phase3_left_rear',[[-37,148,63],[-53,130,83],[-63,102,98],[-71,72,105],[-76,43,102],[-68,18,91],[-51,10,76]],13);
chain('tentacle_phase3_right_rear',[[34,147,65],[46,127,86],[53,100,103],[59,70,113],[67,43,115],[79,25,103],[87,28,90]],13);
save(3,phase3);

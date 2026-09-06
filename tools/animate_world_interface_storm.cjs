// Authored motion layered along every articulated link, in native Blockbench 5 coordinates.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const dir = path.resolve(__dirname, '../docs/art/world_interface');
const uuid = name => {
  const h = crypto.createHash('md5').update('storm-motion-v3:' + name).digest('hex');
  return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-8${h.slice(17,20)}-${h.slice(20)}`;
};
const ease = t => { t = Math.max(0, Math.min(1,t)); return t*t*t*(t*(t*6-15)+10); };
function curve(t, keys) {
  if (t <= keys[0][0]) return keys[0][1];
  for (let i=1;i<keys.length;i++) if(t<=keys[i][0]) {
    const a=keys[i-1],b=keys[i]; return a[1]+(b[1]-a[1])*ease((t-a[0])/(b[0]-a[0]));
  }
  return keys.at(-1)[1];
}
for (let form=1;form<=3;form++) {
  const model=JSON.parse(fs.readFileSync(path.join(dir,`world_interface_storm_form${form}_detail_v2.bbmodel`),'utf8'));
  const groups=new Map((model.groups??[]).map(g=>[g.uuid,g]));
  function expand(n) { if(typeof n==='string')return n; const g={...(groups.get(n.uuid)??n),children:n.children.map(expand)};groups.set(g.uuid,g);return g; }
  const tree=model.outliner.map(expand),root=tree[0];root.name='storm_root';
  const all=[...groups.values()],heads=['center','left','right'].map(n=>all.find(g=>g.name==='head_'+n));
  const limbs=all.find(g=>g.name==='main_tentacles').children.filter(g=>typeof g==='object');
  let clips=[];
  function clip(name,length,loop=false){ const a={uuid:uuid(form+name),name:`animation.world_interface.form${form}.${name}`,length,loop:loop?'loop':'once',snapping:24,override:false,animators:{}};clips.push(a);return a; }
  function sample(a,g,channel,fn) {
    const b=a.animators[g.uuid]??={name:g.name,type:'bone',rotation_global:false,keyframes:[]};
    const frames=Math.round(a.length*12);
    for(let k=0;k<=frames;k++){const t=k*a.length/frames,v=fn(t);if(v.some(x=>!Number.isFinite(x)))throw Error('Invalid pose');b.keyframes.push({uuid:uuid(`${form}:${a.name}:${g.uuid}:${channel}:${k}`),channel,time:t,interpolation:'linear',data_points:[{x:String(+v[0].toFixed(5)),y:String(+v[1].toFixed(5)),z:String(+v[2].toFixed(5))}]});}
  }
  function links(limb){const out=[];let n=limb.children.find(g=>typeof g==='object'&&g.name==='segment_00');while(n){out.push(n);n=n.children.find(g=>typeof g==='object'&&/^segment_/.test(g.name));}return out;}
  const idle=clip('idle',12,true);
  sample(idle,root,'position',t=>[0,1.5*Math.sin(t*Math.PI/6),0]);
  sample(idle,root,'rotation',t=>[.45*Math.sin(t*Math.PI/6),.65*Math.sin(t*Math.PI/6),.5*Math.sin(t*Math.PI/3)]);
  limbs.forEach((limb,i)=>{
    const phase=i*1.27,sign=i%2?1:-1,chain=links(limb);
    sample(idle,limb,'rotation',t=>[3.2*Math.sin(t*Math.PI/6+phase),1.6*Math.sin(t*Math.PI/3+phase),sign*3.4*Math.sin(t*Math.PI/6+phase+.7)]);
    chain.forEach((g,j)=>{
      const u=j/Math.max(1,chain.length-1),lag=j*.34;
      sample(idle,g,'rotation',t=>{
        const wave=(t-lag)*Math.PI/3+phase;
        return [(1.0+2.2*u)*Math.sin(wave),(.3+.75*u)*Math.sin(wave*.5+phase),(1.1+2.0*u)*Math.cos(wave+sign*.6)];
      });
    });
  });
  heads.forEach((h,i)=>{
    sample(idle,h,'rotation',t=>[.8*Math.sin(t*Math.PI/3-i*.8),1.8*Math.sin(t*Math.PI/6-i*1.8),.55*Math.sin(t*Math.PI/6+i)]);
    sample(idle,h.children.find(g=>g.name==='jaw'),'rotation',t=>[-1.8-1.8*Math.sin(t*Math.PI/3-i*.8),0,0]);
  });
  const sweep=clip('tentacle_sweep',4.2);
  limbs.forEach((limb,i)=>{
    const active=/front_right/.test(limb.name),sign=i%2?1:-1,chain=links(limb);
    sample(sweep,limb,'rotation',t=>active?[curve(t,[[0,0],[.85,12],[1.25,-14],[1.65,-6],[2.4,3],[4.2,0]]),curve(t,[[0,0],[.9,-12],[1.3,19],[2,5],[4.2,0]]),curve(t,[[0,0],[.9,-22],[1.35,28],[2.3,-5],[4.2,0]])]:[curve(t,[[0,0],[1.1,-3],[1.7,2],[3.6,0]]),0,sign*curve(t,[[0,0],[1.1,5],[1.75,-3],[4.2,0]])]);
    chain.forEach((g,j)=>{
      const u=j/Math.max(1,chain.length-1),delay=j*.036,amp=active?(2.5+u*5.5):.75;
      sample(sweep,g,'rotation',t=>{
        const pulse=curve(t-delay,[[0,0],[.72,-.85],[1.02,-1],[1.32,1.3],[1.7,.28],[2.12,-.32],[2.55,.13],[3.4,0],[4.2,0]]);
        return [pulse*amp*.65,pulse*amp*.15,pulse*amp*(active?1:sign)];
      });
    });
  });
  sample(sweep,root,'rotation',t=>[0,curve(t,[[0,0],[1,-1.5],[1.5,1.2],[3.5,0]]),curve(t,[[0,0],[1,-1],[1.5,1.2],[4.2,0]])]);
  const charge=clip('charge',6.5);
  heads.forEach((h,i)=>{
    const delay=i*.16,aim=i===1?11:i===2?-11:0;
    sample(charge,h,'rotation',t=>[curve(t-delay,[[0,0],[.8,6],[3.5,-5],[4.5,-7],[4.72,5],[5.2,-3],[6.5,0]]),aim*curve(t,[[0,0],[3.6,1],[5.8,1],[6.5,0]]),0]);
    sample(charge,h.children.find(g=>g.name==='jaw'),'rotation',t=>[curve(t,[[0,0],[.8,-5],[3.7,-28],[4.5,-32],[4.7,-37],[5.1,-29],[6.1,-29],[6.5,0]]),0,0]);
  });
  limbs.forEach((l,i)=>links(l).forEach((g,j)=>sample(charge,g,'rotation',t=>{
    const tension=curve(t-j*.045,[[0,0],[3.8,1],[4.5,1],[4.78,-.35],[5.3,.12],[6.2,0],[6.5,0]])*(1-ease((t-6.1)/.4));
    return [tension*(.6+j*.09),0,tension*(i%2?1:-1)*(.8+j*.06)];
  })));
  const hit=clip('hit_reaction',2);
  sample(hit,root,'rotation',t=>[curve(t,[[0,0],[.16,-1.3],[.48,.6],[.95,-.15],[2,0]]),0,curve(t,[[0,0],[.2,1.8],[.65,-.65],[1.2,.2],[2,0]])]);
  limbs.forEach((l,i)=>links(l).forEach((g,j)=>sample(hit,g,'rotation',t=>[curve(t-j*.022,[[0,0],[.18,1.4],[.48,-.8],[.95,.35],[1.5,0],[2,0]])*(.5+j*.08),0,0])));
  if(form>1){const growth=all.find(g=>g.name===(form===2?'phase_2_accretion':'phase_3_rupture')),morph=clip('accretion',6);sample(morph,growth,'scale',t=>{const v=curve(t,[[0,.015],[1,.1],[3,.65],[4.6,1.02],[6,1]]);return[v,v,v];});limbs.slice(form===2?4:6).forEach((l,i)=>{sample(morph,l,'scale',t=>{const v=curve(t-i*.15,[[0,.02],[1,.12],[3.8,.85],[5,1],[6,1]]);return[v,v,v];});links(l).forEach((g,j)=>sample(morph,g,'rotation',t=>[curve(t-j*.035,[[0,7],[1.2,7],[4.5,-.7],[6,0]])*(1-ease((t-5.6)/.4)),0,0]));});}
  model.meta.format_version='5.0';model.animations=clips;model.name=model.model_identifier=`world_interface_storm_form${form}_animation_v3`;
  model.groups=[...groups.values()].map(({children,...g})=>({...g,children:[]}));
  function compact(n){return typeof n==='string'?n:{uuid:n.uuid,isOpen:false,children:n.children.map(compact)};}model.outliner=tree.map(compact);
  for(const a of clips)for(const b of Object.values(a.animators))if(a.loop==='loop')for(const c of ['position','rotation','scale']){const k=b.keyframes.filter(k=>k.channel===c);if(k.length&&JSON.stringify(k[0].data_points)!==JSON.stringify(k.at(-1).data_points))throw Error('Loop discontinuity');}
  fs.writeFileSync(path.join(dir,model.name+'.bbmodel'),JSON.stringify(model));
  console.log(JSON.stringify({form,limbs:limbs.length,links:limbs.map(l=>links(l).length),clips:clips.map(a=>({name:a.name,bones:Object.keys(a.animators).length,keys:Object.values(a.animators).reduce((n,b)=>n+b.keyframes.length,0)}))}));
}

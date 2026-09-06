/* Executed inside Blockbench through MCP risky_eval after importing each runtime skeleton. */
(() => {
  const kind = globalThis.__entityKind;
  const texture = Texture.all[0];
  const find = name => Group.all.find(g => g.name === name);
  function bone(name, pivot, parent, rotation = [0, 0, 0]) {
    return new Group({name, origin: pivot, rotation}).addTo(parent).init();
  }
  function box(parent, name, from, size, uv = [0, 0]) {
    const cube = new Cube({name, from, to: from.map((v, i) => v + size[i]),
      origin: parent.origin.slice(), box_uv: true, uv_offset: uv, autouv: 0}).addTo(parent).init();
    for (const face of Object.values(cube.faces)) face.texture = texture.uuid;
    Cube.preview_controller.updateUV(cube);
    return cube;
  }
  function at(g, dx, dy, dz) { return [g.origin[0] + dx, g.origin[1] + dy, g.origin[2] + dz]; }
  function fingers(claw, count, width, length, uv) {
    const side = claw.name.includes('left') ? -1 : 1;
    for (const child of [...claw.children]) if (child instanceof Cube) child.remove();
    box(claw, 'palm_lamella', at(claw, -width / 2, -0.8, -width * .32), [width, .8, width * .64], uv);
    for (let i = 0; i < count; i++) {
      const x = -width * .38 + i * width * .76 / (count - 1);
      const finger = bone('digit_' + i, at(claw, x, -.65, -.15), claw, [0,0,(i-(count-1)/2)*7]);
      const thick = Math.max(.14, width * .14), len = length * (i === count - 1 ? .7 : 1);
      box(finger, 'proximal_phalanx', at(finger,-thick/2,-len*.55,-thick/2),[thick,len*.55,thick],uv);
      box(finger, 'knuckle', at(finger,-thick*.65,-len*.52,-thick*.65),[thick*1.3,thick,thick*1.3],uv);
      const distal = bone('distal', at(finger,0,-len*.52,0),finger,[-15-i*3,0,0]);
      box(distal,'hook',at(distal,-thick*.38,-len*.48,-thick*.35),[thick*.76,len*.48,thick*.7],uv);
    }
  }
  if (kind === 'bacteria') {
    const body = find('body'), abdomen = find('abdomen');
    for (let i = 0; i < 6; i++) {
      const p = bone('carapace_' + i, at(abdomen,0,2.6,i*1.75+.5),abdomen,[-i*.5,0,i%2?1:-1]);
      const w = 9.8 - Math.abs(i-2.3)*.9;
      box(p,'overlapping_chitin',at(p,-w/2,.4,-.65),[w,1.05,1.7],[0,16]);
      box(p,'dorsal_ridge',at(p,-.5,1.35,-.6),[1,.65,1.55],[28,17]);
    }
    for (let i = 0; i < 8; i++) {
      const leg = find('leg_' + i), shin = leg.children.find(g => g instanceof Group && g.name==='shin');
      const sign = i%2===0?-1:1;
      for (const child of [...shin.children]) if (child instanceof Cube) child.remove();
      for (let j=0;j<3;j++) {
        const a=sign*j*17.5/3,b=sign*(j+1)*17.5/3,thickness=1.7-j*.45;
        box(shin,'tapered_tibia_'+j,at(shin,Math.min(a,b),-thickness/2,-thickness/2),
          [17.5/3,thickness,thickness],[40,0]);
      }
      box(leg,'coxa_socket',at(leg,-1.2,-1.2,-1.2),[2.4,2.4,2.4],[40,0]);
      box(shin,'knee_cap',at(shin,-1.15,-1.15,-1.15),[2.3,2.3,2.3],[40,0]);
      for(let j=0;j<4;j++){
        const p=bone('tibial_spur_'+j,at(shin,sign*(3+j*3.3),0,.2),shin,[0,0,sign*(28+j*3)]);
        box(p,'sensory_spine',at(p,-.12,-.1,-.1),[.24,1.1+j*.12,.24],[48,8]);
      }
      const foot=bone('tarsus',at(shin,sign*16.7,0,0),shin,[0,sign*8,sign*-10]);
      box(foot,'split_hook_outer',at(foot,sign<0?-1.4:0,-.4,-.5),[1.4,.5,.32],[48,8]);
      box(foot,'split_hook_inner',at(foot,sign<0?-1.1:0,-.4,.2),[1.1,.5,.3],[48,8]);
    }
    for(let i=0;i<3;i++)box(body,'ventral_fold_'+i,at(body,-3.6,-2.5, -4+i*1.5),[7.2,.7,.4],[4,2]);
  } else if (kind === 'watcher') {
    const head=find('head'),neck=find('neck');
    const lid=bone('upper_lid',at(head,0,4.1,-1.9),head);
    box(lid,'dry_upper_lid',at(lid,-1.95,-.24,-.24),[3.9,.48,.45],[60,0]);
    for(let i=0;i<5;i++)box(neck,'cervical_plate_'+i,at(neck,-.68,.4+i*1.02,.9),[1.36,.58,.45],[51,0]);
    for(const hand of Group.all.filter(g=>g.name==='hand')) fingers(hand,4,1.45,3.2,[37,36]);
    const torso=find('torso');
    for(let side of [-1,1])for(let i=0;i<5;i++){
      const rib=bone('costal_edge_'+side+'_'+i,at(torso,side*2.1,11-i*1.6,-1.75),torso,[0,side*9,side*8]);
      box(rib,'exposed_costal_tip',at(rib,-.22,-.18,-.18),[.44,.6,.5],[48,66]);
    }
  } else if (kind.startsWith('rework_body')) {
    const stage=Number(kind.slice(-1)),torso=find('torso'),head=find('head');
    for(const claw of [...Group.all].filter(g=>g.name==='claw')) fingers(claw,stage===3?4:3,stage===3?2.8:1.5,stage===3?1.5:3.2,[16,32]);
    for(let side of [-1,1]){
      const seam=bone('clavicle_'+side,at(torso,side*(stage===1?1.2:2.6),stage===1?7.8:10,-1.4),torso,[0,0,side*12]);
      box(seam,'misaligned_clavicle',at(seam,-.6,-.35,-.25),[1.2,.7,.5],[64,64]);
      const temple=bone('temple_seam_'+side,at(head,side*(stage===1?1.65:3),stage===1?3.5:5.6,-1.7),head,[0,side*14,0]);
      box(temple,'compressed_skin',at(temple,-.15,-1.1,-.25),[.3,2.2,.5],[70,0]);
    }
    if(stage<3)for(let i=0;i<6;i++){
      const p=bone('dorsal_lamina_'+i,at(torso,0,1.5+i*1.15,stage===1?1.25:1.9),torso,[5+i*2,0,0]);
      box(p,'vertebral_lamina',at(p,-.58,-.24,0),[1.16,.48,.7],[100,64]);
    }
  } else if(kind==='him') {
    const body=find('body');
    box(body,'shirt_collar',at(body,-2.3,-.22,-2.035),[4.6,.35,.09],[20,20]);
    for(const name of ['left_arm','right_arm']){
      const arm=find(name);if(!arm)continue;
      const cube=arm.children.find(x=>x instanceof Cube);if(!cube)continue;
      box(arm,'uneven_cuff',[cube.from[0]-.03,cube.to[1]-5.0,cube.from[2]-.025],
        [cube.to[0]-cube.from[0]+.06,.22,cube.to[2]-cube.from[2]+.05],[44,20]);
    }
  } else if(kind==='stability_anchor'){
    for(let i=0;i<4;i++){
      const upper=find('claw_'+i+'_upper_arm');
      for(let j=0;j<3;j++)box(upper,'locking_lug_'+j,at(upper,-1.5,-j*2-1,-.55),[3,.45,1.1],[0,0]);
      const pivot=find('claw_'+i+'_pivot');
      box(pivot,'hinge_axle',at(pivot,-2,-.45,-.45),[4,.9,.9],[0,0]);
      const wrist=find('claw_'+i+'_wrist');
      for(let j=0;j<3;j++)box(wrist,'tension_ridge_'+j,at(wrist,-1.1,-.5-j*.7,-.7),[2.2,.3,1.4],[0,0]);
    }
  }
  Canvas.updateAll();
  return JSON.stringify({kind,cubes:Cube.all.length,bones:Group.all.length});
})()

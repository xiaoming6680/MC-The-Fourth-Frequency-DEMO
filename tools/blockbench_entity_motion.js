/* Import sampled runtime curves into the currently selected MCP entity project. */
(() => {
  const data=JSON.parse(__motionChunks.join(''));
  function path(g){return g.parent instanceof Group?path(g.parent)+'/'+g.name:g.name;}
  const groups=new Map(Group.all.map(g=>[path(g),g]));
  for(const clip of data.clips){
    const a=new Animation({name:'animation.'+Project.name+'.'+clip.name,length:clip.length,loop:'once',snapping:20}).add();
    for(const [name,channels] of Object.entries(clip.tracks)){
      const group=groups.get(name)||groups.get('root/'+name);
      if(!group)throw Error('Runtime animation references missing bone: '+name);
      const animator=a.getBoneAnimator(group);
      for(const [channel,keys] of Object.entries(channels))for(const [time,x,y,z] of keys){
        animator.addKeyframe({channel,time,interpolation:'linear',data_points:[{x,y,z}]});
      }
    }
  }
  if(!Animation.all.length)throw Error('No animation imported');
  Animation.all[0].select();
  return JSON.stringify({project:Project.name,animations:Animation.all.map(a=>({name:a.name,bones:Object.keys(a.animators).length}))});
})();

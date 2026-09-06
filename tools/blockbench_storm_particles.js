/* Construct crisp, transparent energy glyph sprites inside the Blockbench material workspace. */
(() => {
  globalThis.__stormSprites={};
  for(const kind of ['filament','ember','sigil']){
    const canvas=document.createElement('canvas');canvas.width=canvas.height=32;
    const c=canvas.getContext('2d');c.strokeStyle='rgba(220,205,255,0.6)';c.lineWidth=2;
    if(kind==='filament'){
      c.beginPath();c.moveTo(14,2);c.lineTo(11,11);c.lineTo(19,18);c.lineTo(16,30);c.stroke();
      c.strokeStyle='#ffffff';c.lineWidth=1;c.beginPath();c.moveTo(15,4);c.lineTo(12,11);c.lineTo(20,18);c.lineTo(17,28);c.stroke();
    }else if(kind==='ember'){
      c.fillStyle='rgba(215,188,255,0.45)';c.beginPath();c.moveTo(15,2);c.lineTo(21,18);c.lineTo(16,29);c.lineTo(12,18);c.fill();
      c.fillStyle='#ffffff';c.fillRect(15,12,3,9);c.fillStyle='rgba(255,255,255,.4)';c.fillRect(15,7,2,5);
    }else{
      c.beginPath();c.moveTo(16,3);c.lineTo(28,16);c.lineTo(19,26);c.moveTo(13,28);c.lineTo(4,17);c.lineTo(12,6);c.stroke();
      c.strokeStyle='rgba(255,255,255,.9)';c.lineWidth=1;c.beginPath();c.moveTo(16,9);c.lineTo(22,16);c.lineTo(16,23);c.lineTo(10,16);c.closePath();c.stroke();
      c.fillStyle='#ffffff';c.fillRect(15,14,2,4);
    }
    const data=canvas.toDataURL('image/png');__stormSprites[kind]=data;
    new Texture({name:'storm_'+kind+'.png'}).fromDataURL(data).add();
  }
  return 'Created three storm particle sprite materials';
})();

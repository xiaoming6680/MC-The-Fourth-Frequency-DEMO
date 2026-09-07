# MOD Logo

扁平六边形外框、CRT 荧光绿色、断开的信号线与终端光标。按玩家要求减少内部内容，保持小尺寸辨识度；开幕只做点亮、短暂停留与淡出，不展开成复杂图形动画。音量页转告知页的扫频保留。

最终素材由内置 ImageGen 生成，1254×1254 RGBA，外部透明。原样复制到 `src/main/resources/assets/thefourthfrequency/textures/gui/notice/frequency_logo.png`；开幕与 `fabric.mod.json` 的 `icon` 共用它。未用 CLI fallback，未用脚本绘制或改写生成图像像素。未采用金属质感和带棋盘格的候选图。

## 最终提示词

```text
A tiny, extremely minimalist FLAT app logo. Actual transparent background. A single thick regular point-up hexagon outline, solid CRT phosphor green. Inside the hexagon: one bold four-pulse angular frequency line, with a small gap in the last descending pulse, and one short underscore line below it suggesting a terminal cursor. No surrounding rectangle inside the hexagon, no screen casing, no knobs, no text. Hexagon interior is solid nearly black green #080E0C. ONLY TWO SOLID COLORS: CRT green #62F58A and near-black green #080E0C. No gradients, no glow, no shading, no noise, no texture. Flat geometric vector icon, sharp clean shapes, plenty of negative space, about three visual elements total. Logo for an analog-horror terminal Minecraft mod called The Fourth Frequency, no words written. Square image, symbol centered occupying 75% of the canvas, enough transparent padding on all sides. Output as genuine RGBA PNG; everything outside the hexagon must be invisible alpha=0. Do NOT draw a checkerboard or a white background. Do not make a realistic illustration. This must look clean, quiet and understated when briefly displayed on a dark CRT startup screen and remain legible as a 32px mod icon.
```

最终图案采用实际生成的抽象信号线，不将波峰数量作为玩法或验收条件。

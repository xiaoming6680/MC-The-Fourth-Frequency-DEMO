#version 330

// The mod's dispersion filter: light itself being bent on its way to the camera.
//
// This is the third and last screen language in the mod, and it is deliberately not one of the
// other two. analog_signal.fsh is the *medium* failing - tape, tube, recording. digital_corrupt.fsh
// is the *rules* failing - the pipeline that decides where the player is allowed to be coming
// apart. Neither of those is what a beam weapon does. A beam does not damage the picture; it lights
// the air the picture travels through, and a lens that is looking at something far too bright
// stops separating the colours cleanly. Nothing here is broken. Everything here is optics.
//
// Keeping that separate matters for the same reason the first two are separate. A player who has
// learned that torn bands mean the rules are slipping must not be shown torn bands because a gun
// went off - the fear the corruption language carries is not the fear this attack is asking for.
// So there is no tearing, no macroblocking, no quantisation and no held pattern anywhere in this
// file: the whole treatment is smooth, radial and centred on where the player is already looking.
//
// It is also, for the same reason, the one screen treatment in the mod that is worn by players it
// is not aimed at. Being shot at is a private thing and the lock says so; a beam crossing the arena
// is a thing that happened in front of everybody, and the far chain is what everyone else wears.
//
// See digital_corrupt.fsh for why Globals is safe to import and SamplerInfo is not.
#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform DispersionConfig {
    // Master fader. 0 leaves the frame untouched, pixel for pixel.
    float Strength;
    // Radial red/blue separation where the mask reaches full strength, in pixels.
    float Dispersion;
    // How much of the separation is a spread rather than two ghosts.
    //
    // At 0 this is a three-tap split: one red copy pushed out, one blue copy pulled in, and a hard
    // fringe either side of every edge. At 1 the same total separation is sampled across six taps,
    // so each primary is smeared along the radius instead of displaced along it and the fringe
    // comes out as a continuous spectrum. The three-tap version reads as a registration error - a
    // picture assembled wrong - which is the other shaders' territory; the spread reads as glass.
    float Spectrum;
    // Radius where the treatment starts, and where it reaches full strength. Both are on the same
    // scale digital_corrupt.fsh uses: 0 in the middle, 1 at the midpoint of an edge, 1.41 in a
    // corner. Keeping the middle clear is not a stylistic preference here - the beam has to be
    // dodged while this is on the screen.
    float CenterClear;
    float EdgeRadius;
    // Outward stretch of the whole picture at the edge, as a fraction of the radius. The lens under
    // load, and the term that makes the treatment read as the frame bulging rather than as a colour
    // effect laid on top of it.
    float Barrel;
    // Bright-pass bleed smeared along the radius, and the luminance it starts at. This is what puts
    // the beam's own light into the air: it keys on what is actually bright in the frame, so a shot
    // that is off-screen leaves the edge alone and one that fills half of it blooms across the
    // corner nearest to it.
    float Bloom;
    float BloomThreshold;
    // Ticks one jitter step survives before it re-rolls.
    //
    // The 3 Hz ceiling from the world bible, in the one place in this shader where anything is
    // discrete at all. Everything else here is a smooth function of GameTime or of position; this
    // is the single stepped term, so it carries the whole of the constraint and PostFilterContract
    // asserts it stays at seven or above.
    float HoldTicks;
    // How far the held step modulates the separation, as a fraction of it. Small: this is the beam
    // being unsteady, not the screen flickering.
    float Jitter;
    // Corner darkening.
    float Vignette;
    // Pushes colour *away* from luminance rather than towards it, which is the opposite of what
    // every other filter in this mod does and is the point of this one. The other two take colour
    // out because a damaged picture has less of it. A prism does not lose colour; it separates it,
    // and a fringe that is not more saturated than what it came from does not read as a fringe.
    float Saturate;
    // rgb is the light the beam is casting into the air, a is how much of it lands.
    vec4 Tint;
};

out vec4 fragColor;

float dispersionHash(float seed) {
    return fract(sin(seed * 12.9898) * 43758.5453);
}

// Post targets are clamped at their edges, but the barrel term samples outside the frame by
// construction and relying on a sampler state set somewhere else is how the SamplerInfo bug got in.
vec3 tap(vec2 uv) {
    return texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 screen = max(ScreenSize, vec2(1.0));
    vec2 centred = texCoord - vec2(0.5);
    float radius = length(centred) * 2.0;

    float mask = 1.0;
    if (EdgeRadius > CenterClear) {
        mask = smoothstep(CenterClear, EdgeRadius, radius);
        // Squared, so the ramp stays out towards the edge instead of creeping evenly inward. The
        // middle of the screen is where the player reads the arena from.
        mask *= mask;
    }
    float amount = clamp(Strength * mask, 0.0, 1.0);
    if (amount <= 0.0) {
        fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0);
        return;
    }

    // One held step of unsteadiness on the separation, and nothing else in the frame steps with it.
    float slot = floor(GameTime * 24000.0 / max(HoldTicks, 1.0));
    float pulse = 1.0 + (dispersionHash(slot) - 0.5) * 2.0 * clamp(Jitter, 0.0, 1.0);

    // The lens bulging. Applied before the split so the separation follows the stretched picture.
    vec2 uv = vec2(0.5) + centred * (1.0 + Barrel * amount * radius * radius);

    // Same construction analog_signal.fsh uses for its own chroma term, so the two are directly
    // comparable in the json: `centred` carries the direction and half the radius, and the divide by
    // the screen turns pixels into texture space. Calibrated against that shader on purpose - its
    // heaviest chain separates by about twelve pixels in a corner, and a beam discharging should be
    // half again as hard as the worst the anomalies ever get, not three times.
    vec2 spread = centred * (Dispersion * amount * pulse * radius) / screen;

    // Five points on the radius, read once and used by both constructions below. Written this way
    // rather than as two independent expressions because the naive form samples the outer pair and
    // the centre twice each - and a texture read the compiler cannot prove is redundant is one it
    // will not remove.
    vec3 outerWarm = tap(uv + spread);
    vec3 innerWarm = tap(uv + spread * 0.45);
    vec3 middle = tap(uv);
    vec3 innerCool = tap(uv - spread * 0.45);
    vec3 outerCool = tap(uv - spread);

    // One red copy out, one blue copy in. A registration error.
    vec3 split = vec3(outerWarm.r, middle.g, outerCool.b);
    // The same total separation with each primary averaged across two points on the radius, so it
    // arrives as a smear. Glass rather than a mis-assembled picture.
    vec3 smeared = vec3(
            (outerWarm.r + innerWarm.r) * 0.5,
            (innerWarm.g + innerCool.g) * 0.5,
            (innerCool.b + outerCool.b) * 0.5);
    vec3 color = mix(split, smeared, clamp(Spectrum, 0.0, 1.0));

    if (Bloom > 0.0) {
        // Three taps stepping outward along the radius rather than a second render target: this
        // only has to read as the beam's light hanging in the air near the edge of the frame, and a
        // real blur pass would double the cost of the chain for a treatment that lasts two seconds.
        vec3 bleed = tap(uv + spread * 1.6) + tap(uv + spread * 2.6) + tap(uv - spread * 1.6);
        bleed *= 0.3333333;
        float bright = max(max(bleed.r, bleed.g), bleed.b);
        color += bleed * smoothstep(BloomThreshold, 1.0, bright) * Bloom * amount;
    }

    // Away from luminance, not towards it. See Saturate.
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, 1.0 + Saturate * amount);
    color = mix(color, Tint.rgb * (0.35 + 0.65 * luma), clamp(Tint.a, 0.0, 1.0) * amount);
    color *= 1.0 - Vignette * amount * smoothstep(0.45, 1.35, radius);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}

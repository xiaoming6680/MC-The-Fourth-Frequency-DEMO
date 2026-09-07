#version 330
uniform sampler2D InSampler;
in vec2 texCoord;
layout(std140) uniform LensConfig { vec4 Lens; };
out vec4 fragColor;
void main() {
    vec2 center = texCoord - 0.5;
    vec2 uv = clamp(0.5 + center * (1.0 + Lens.y * dot(center, center)), 0.0, 1.0);
    vec2 px = Lens.x / vec2(textureSize(InSampler, 0));
    vec4 c = texture(InSampler, uv) * 0.5;
    c += texture(InSampler, clamp(uv + vec2(px.x, 0), 0.0, 1.0)) * 0.125;
    c += texture(InSampler, clamp(uv - vec2(px.x, 0), 0.0, 1.0)) * 0.125;
    c += texture(InSampler, clamp(uv + vec2(0, px.y), 0.0, 1.0)) * 0.125;
    c += texture(InSampler, clamp(uv - vec2(0, px.y), 0.0, 1.0)) * 0.125;
    c.rgb *= vec3(1.0, 1.0 - Lens.z * 0.02, 1.0 - Lens.z * 0.065);
    c.rgb *= 1.0 - Lens.w * dot(center, center);
    fragColor = vec4(c.rgb, 1.0);
}

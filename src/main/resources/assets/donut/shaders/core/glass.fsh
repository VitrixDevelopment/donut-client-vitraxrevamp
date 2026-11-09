#version 330

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 localPosition;
in vec2 sceneUV;
flat in vec2 panelSize;
flat in float cornerRadius;
flat in float frost;

out vec4 fragColor;

float roundedDistance(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

void main() {
    vec2 p = localPosition - panelSize * 0.5;
    float d = roundedDistance(p, panelSize * 0.5, cornerRadius);
    float pixel = max(length(vec2(dFdx(d), dFdy(d))), 0.0001);
    float coverage = 1.0 - smoothstep(-pixel * 0.5, pixel * 0.5, d);
    if (coverage <= 0.0 || vertexColor.a <= 0.0) discard;

    // The bevel bends the captured scene inward near the perimeter. The three
    // samples add restrained chromatic dispersion to the frosted material.
    vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
    vec2 normal = vec2(dFdx(d), dFdy(d)) / pixel;
    float bevel = exp(-max(-d / pixel, 0.0) / 9.0);
    vec2 bend = normal * texel * bevel * (1.2 + frost * 1.8);
    vec2 uv = sceneUV - bend;
    vec3 background;
    background.r = texture(Sampler0, uv - bend * 0.10).r;
    background.g = texture(Sampler0, uv).g;
    background.b = texture(Sampler0, uv + bend * 0.10).b;
    float luminance = dot(background, vec3(0.2126, 0.7152, 0.0722));
    background = mix(background, vec3(luminance), 0.48 + frost * 0.18);
    background /= 1.0 + luminance * 2.4;

    vec2 position01 = clamp(localPosition / panelSize, 0.0, 1.0);
    // Restrained tint and a charcoal vertical falloff keep bright scenes from
    // turning the material into a flat, saturated panel.
    float tintLuma = dot(vertexColor.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 tint = mix(vec3(tintLuma), vertexColor.rgb, 0.35);
    tint *= min(1.0, 0.18 / max(tintLuma, 0.001));
    vec3 color = mix(background * 0.24, tint * 0.82, 0.80);
    float vertical = smoothstep(0.0, 1.0, position01.y);
    color *= mix(1.08, 0.78, vertical);
    color += mix(vec3(0.033, 0.035, 0.050), vec3(0.010, 0.012, 0.016), vertical);
    float sheen = exp(-pow((position01.x + position01.y * 0.4) * 2.0, 2.0)) * 0.013;
    color += vec3(0.75, 0.79, 1.0) * sheen;

    float rim = 1.0 - smoothstep(0.0, 1.25, abs(d / pixel + 0.45));
    float light = clamp(dot(normal, normalize(vec2(-0.55, 0.85))) * 0.5 + 0.5, 0.0, 1.0);
    color += vec3(0.82, 0.87, 1.0) * rim * (0.025 + 0.10 * light * light);
    color += vec3(0.52, 0.60, 0.82) * bevel * 0.009;

    // A stationary, sub-pixel frost grain prevents banding without distracting motion.
    float grain = fract(sin(dot(floor(gl_FragCoord.xy), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
    color += grain * frost * 0.004;

    fragColor = vec4(color, vertexColor.a * coverage);
}

#version 330

uniform sampler2D Sampler0;
layout(std140) uniform HotbarInfo {
    vec4 Region;
    vec4 Pixel;
};
in vec2 texCoord;
out vec4 fragColor;

#ifndef COMPOSITE
vec3 sampleHotbar(vec2 uv) {
    // Keep edge samples inside the hotbar, including at the bottom of the framebuffer.
    uv = clamp(uv, Pixel.xy * 0.5, vec2(1.0) - Pixel.xy * 0.5);
#ifdef HORIZONTAL
#ifndef LOCAL_SOURCE
    uv = Region.xy + uv * Region.zw;
#endif
#endif
    return texture(Sampler0, uv).rgb;
}
#endif

void main() {
#ifdef COMPOSITE
    vec2 uv = (texCoord - Region.xy) / Region.zw;
    // The saved percentage controls the blend with the original hotbar.
    fragColor = vec4(texture(Sampler0, uv).rgb, Pixel.z);
#else
#ifdef EXTRA_BLUR
    float radius = 2.1 * Pixel.w;
#else
    float radius = 2.1;
#endif
#ifdef HORIZONTAL
    vec2 stepUV = vec2(Pixel.x * radius, 0.0);
#else
    vec2 stepUV = vec2(0.0, Pixel.y * radius);
#endif
    vec3 color = sampleHotbar(texCoord) * 0.227027;
    color += sampleHotbar(texCoord + stepUV * 1.384615) * 0.1945946;
    color += sampleHotbar(texCoord - stepUV * 1.384615) * 0.1945946;
    color += sampleHotbar(texCoord + stepUV * 3.230769) * 0.1216216;
    color += sampleHotbar(texCoord - stepUV * 3.230769) * 0.1216216;
    color += sampleHotbar(texCoord + stepUV * 5.0) * 0.054054;
    color += sampleHotbar(texCoord - stepUV * 5.0) * 0.054054;
    color += sampleHotbar(texCoord + stepUV * 7.0) * 0.016216;
    color += sampleHotbar(texCoord - stepUV * 7.0) * 0.016216;
    fragColor = vec4(color, 1.0);
#endif
}

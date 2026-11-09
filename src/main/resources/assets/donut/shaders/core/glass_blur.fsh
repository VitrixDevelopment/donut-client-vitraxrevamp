#version 330

uniform sampler2D Sampler0;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 sourcePixel = 1.0 / vec2(textureSize(Sampler0, 0));
#ifdef HORIZONTAL
    // The first pass also downsamples to half resolution.
    vec2 stepUV = vec2(sourcePixel.x * 2.7, 0.0);
#else
    vec2 stepUV = vec2(0.0, sourcePixel.y * 1.35);
#endif
    // Bilinear Gaussian pairs: nine fetches per pass, shared by all glass panes.
    vec3 color = texture(Sampler0, texCoord).rgb * 0.227027;
    color += texture(Sampler0, texCoord + stepUV * 1.384615).rgb * 0.1945946;
    color += texture(Sampler0, texCoord - stepUV * 1.384615).rgb * 0.1945946;
    color += texture(Sampler0, texCoord + stepUV * 3.230769).rgb * 0.1216216;
    color += texture(Sampler0, texCoord - stepUV * 3.230769).rgb * 0.1216216;
    color += texture(Sampler0, texCoord + stepUV * 5.0).rgb * 0.054054;
    color += texture(Sampler0, texCoord - stepUV * 5.0).rgb * 0.054054;
    color += texture(Sampler0, texCoord + stepUV * 7.0).rgb * 0.016216;
    color += texture(Sampler0, texCoord - stepUV * 7.0).rgb * 0.016216;
    fragColor = vec4(color, 1.0);
}

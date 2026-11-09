#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 localPosition;
out vec2 sceneUV;
flat out vec2 panelSize;
flat out float cornerRadius;
flat out float frost;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    sceneUV = gl_Position.xy / gl_Position.w * 0.5 + 0.5;
    vertexColor = Color * ColorModulator;
    localPosition = UV0;
    panelSize = vec2(UV1) / 16.0;
    cornerRadius = float(UV2.x) / 64.0;
    frost = float(UV2.y) / 1024.0;
}

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
flat out vec2 panelSize;
flat out float animationTime;
flat out int flags;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color * ColorModulator;
    localPosition = UV0;
    panelSize = vec2(UV1) / 16.0;
    animationTime = float(UV2.x) / 1024.0;
    flags = UV2.y;
}

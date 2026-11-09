#version 330

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 localPosition;
flat in vec2 panelSize;
flat in float animationTime;
flat in int flags;

out vec4 fragColor;

const float TAU = 6.28318530718;

float roundedDistance(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

// Swapping the coordinates gives a regular hexagon with a point at its top.
float hexDistance(vec2 p, float radius) {
    const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
    p = abs(p.yx);
    p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
    p -= vec2(clamp(p.x, -k.z * radius, k.z * radius), radius);
    return length(p) * sign(p.y);
}

float segmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 direction = b - a;
    float position = clamp(dot(p - a, direction) / dot(direction, direction), 0.0, 1.0);
    return length(p - a - direction * position);
}

float crossDistance(vec2 p) {
    return min(segmentDistance(p, vec2(-11.0, -13.0), vec2(11.0, 9.0)),
               segmentDistance(p, vec2(-11.0, 9.0), vec2(11.0, -13.0))) - 1.9;
}

void over(inout vec3 color, inout float alpha, vec3 layer, float opacity) {
    opacity = clamp(opacity, 0.0, 1.0);
    color = layer * opacity + color * (1.0 - opacity);
    alpha = opacity + alpha * (1.0 - opacity);
}

void main() {
    vec2 p = localPosition - panelSize * 0.5;
    vec3 color = vec3(0.0);
    float alpha = 0.0;
    float time = animationTime * TAU / 16.0;

    if ((flags & 1) != 0) {
        float d = roundedDistance(p, panelSize * 0.5 - vec2(2.5), 11.0);
        float aa = max(fwidth(d), 0.001);
        float rim = 1.0 - smoothstep(0.55 - aa * 0.5, 0.55 + aa * 0.5, abs(d));
        float phase = localPosition.x / panelSize.x + localPosition.y / panelSize.y * 0.2;
        vec3 metal = 0.60 + 0.28 * cos(TAU * phase + time + vec3(0.0, 2.0, 4.0));
        float light = pow(0.5 + 0.5 * sin(phase * TAU * 2.0 - time * 2.0), 12.0);
        metal = mix(metal, vec3(0.94, 0.98, 1.0), light * 0.88);
        over(color, alpha, metal * 0.58, exp(-abs(d) * 0.65) * 0.14);
        over(color, alpha, metal, rim * (0.61 + light * 0.30));
        float inner = 1.0 - smoothstep(0.15, 0.15 + aa, abs(d + 1.75));
        over(color, alpha, vec3(0.80, 0.87, 0.98), inner * 0.08);
    } else if ((flags & 20) != 0) {
        float minimal = (flags & 16) != 0 ? 1.0 : 0.0;
        float shiny = (flags & 8) != 0 ? 1.0 : 0.0;
        float radius = min(10.75, panelSize.y * 0.5 - 1.25);
        float d = roundedDistance(p, panelSize * 0.5 - vec2(1.25), radius);
        float aa = max(fwidth(d), 0.001);
        float body = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);
        float reflection = pow(clamp(0.5 - p.y / panelSize.y, 0.0, 1.0), 3.0);
        vec3 glass = vec3(0.027, 0.034, 0.048) + vec3(0.10, 0.13, 0.19) * reflection * 0.24;
        over(color, alpha, glass, body * (0.90 - minimal * 0.12));

        float phase = localPosition.x / panelSize.x + localPosition.y / panelSize.y * 0.13;
        vec3 metal = 0.58 + 0.24 * cos(TAU * phase + time + vec3(0.0, 2.0, 4.0));
        float light = pow(0.5 + 0.5 * sin(phase * TAU * 2.0 - time * 2.0), 12.0);
        metal = mix(metal, vec3(0.91, 0.96, 1.0), light * 0.75);
        vec3 rimColor = mix(vec3(0.24, 0.29, 0.36), metal, shiny * 0.70);
        float rim = 1.0 - smoothstep(0.45 - aa * 0.5, 0.45 + aa * 0.5, abs(d + 0.20));
        over(color, alpha, rimColor, rim * (0.55 + shiny * 0.22 - minimal * 0.15));
        float bevel = 1.0 - smoothstep(0.15, 0.15 + aa, abs(d + 1.5));
        over(color, alpha, vec3(0.61, 0.73, 0.91), bevel * reflection * (0.08 - minimal * 0.045));
    } else {
        float completed = (flags & 2) != 0 ? 1.0 : 0.0;
        float d = hexDistance(p, 27.0);
        float aa = max(fwidth(d), 0.001);
        float body = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);
        vec3 tint = vertexColor.rgb;
        float angular = atan(p.y, p.x);
        float glint = pow(0.5 + 0.5 * sin(angular * 2.0 - time * 2.0), 16.0);

        // Dark translucent glass stays legible against both bright skies and caves.
        float halo = exp(-max(d, 0.0) * 0.60) * (0.018 + completed * 0.095);
        over(color, alpha, tint, halo * (1.0 - body));
        vec3 glass = vec3(0.025, 0.032, 0.045) + tint * (0.013 + completed * 0.021);
        float reflection = pow(clamp(0.5 - p.y / 70.0, 0.0, 1.0), 3.0);
        glass += vec3(0.45, 0.59, 0.74) * reflection * 0.065;
        over(color, alpha, glass, body * 0.78);

        float rim = 1.0 - smoothstep(0.55 - aa * 0.5, 0.55 + aa * 0.5, abs(d + 0.25));
        vec3 rimColor = mix(vec3(0.39, 0.45, 0.49), tint * 0.90 + vec3(glint * 0.18), completed);
        over(color, alpha, rimColor, rim * (0.48 + completed * 0.41));
        float bevel = 1.0 - smoothstep(0.20, 0.20 + aa, abs(d + 2.3));
        over(color, alpha, tint, bevel * (0.045 + completed * 0.09));

        // Sample the supplied full-resolution pixel art with a nearest sampler.
        vec2 iconUV = (p + vec2(19.0, 21.0)) / 38.0;
        float iconMask = step(0.0, iconUV.x) * step(iconUV.x, 1.0)
                       * step(0.0, iconUV.y) * step(iconUV.y, 1.0) * body;
        vec4 icon = texture(Sampler0, clamp(iconUV, 0.0, 1.0));
        float luminance = dot(icon.rgb, vec3(0.2126, 0.7152, 0.0722));
        vec3 iconColor = mix(vec3(luminance), icon.rgb, 0.64 + completed * 0.36);
        iconColor *= 0.77 + completed * 0.30;
        over(color, alpha, iconColor, icon.a * iconMask);

        // A small illuminated dash makes the manually completed stages unambiguous.
        float dash = roundedDistance(p - vec2(0.0, 23.5), vec2(4.5, 0.85), 0.85);
        float dashAA = max(fwidth(dash), 0.001);
        float dashMask = 1.0 - smoothstep(-dashAA * 0.5, dashAA * 0.5, dash);
        over(color, alpha, mix(vec3(0.26, 0.31, 0.35), tint, completed),
                dashMask * (0.40 + completed * 0.55));
        over(color, alpha, tint, exp(-max(dash, 0.0) * 1.2) * completed * 0.085);

        // Composite marked milestones last so the X always sits above the block art.
        if (completed > 0.5) {
            float crossD = crossDistance(p);
            float crossAA = max(fwidth(crossD), 0.001);
            float shadow = 1.0 - smoothstep(0.0, 2.0 + crossAA, crossDistance(p - vec2(0.0, 0.8)));
            over(color, alpha, vec3(0.08, 0.015, 0.025), shadow * body * 0.8);
            over(color, alpha, vec3(1.0, 0.12, 0.18), exp(-max(crossD, 0.0) * 0.9) * body * 0.12);
            float crossMask = 1.0 - smoothstep(-crossAA * 0.5, crossAA * 0.5, crossD);
            vec3 crossColor = mix(vec3(0.88, 0.16, 0.20), vec3(1.0, 0.34, 0.36),
                    clamp(0.5 - p.y / 35.0, 0.0, 1.0));
            over(color, alpha, crossColor, crossMask * body);
        }
    }

    if (alpha <= 0.001) discard;
    fragColor = vec4(color / alpha, alpha * vertexColor.a);
}

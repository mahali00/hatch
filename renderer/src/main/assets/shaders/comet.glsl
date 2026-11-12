const float DURATION = 0.32;
const float MIN_TRAVEL = 1.5;
const float WIDTH = 0.42;

vec2 rectCenter(vec4 r) {
    return vec2(r.x + r.z * 0.5, r.y - r.w * 0.5);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    fragColor = texture(iChannel0, fragCoord / iResolution.xy);

    float age = (iTime - iTimeCursorChange) / DURATION;
    vec2 head = rectCenter(iCurrentCursor);
    vec2 from = rectCenter(iPreviousCursor);
    if (age >= 1.0 || distance(head, from) < iCurrentCursor.z * MIN_TRAVEL) {
        return;
    }

    float chase = 1.0 - pow(1.0 - age, 3.0);
    vec2 tail = mix(from, head, chase);
    vec2 axis = head - tail;
    float along = clamp(dot(fragCoord - tail, axis) / max(dot(axis, axis), 1e-4), 0.0, 1.0);
    float d = length(fragCoord - (tail + axis * along));

    float radius = max(iCurrentCursor.w * WIDTH * mix(0.2, 1.0, along), 1.0);
    float core = 1.0 - smoothstep(radius * 0.35, radius, d);
    float glow = exp(-(d * d) / (radius * radius * 5.0)) * 0.55;
    float strength = clamp((core + glow) * along * (1.0 - age), 0.0, 1.0);

    vec3 tint = mix(iCurrentCursorColor.rgb, vec3(1.0), 0.25 * core);
    fragColor = mix(fragColor, vec4(tint, 1.0), strength);
}
void processEdge(vec2 p, vec2 a, vec2 b, inout float minDist, inout float inside) {
    vec2 edge = b - a;
    vec2 pa = p - a;
    float lenSq = dot(edge, edge);
    float invLenSq = 1.0 / lenSq;

    float t = clamp(dot(pa, edge) * invLenSq, 0.0, 1.0);
    vec2 diff = pa - edge * t;
    minDist = min(minDist, dot(diff, diff));

    float cross = edge.x * pa.y - edge.y * pa.x;
    inside = min(inside, step(0.0, cross));
}

float sdHexagon(in vec2 p, in vec2 v0, in vec2 v1, in vec2 v2, in vec2 v3, in vec2 v4, in vec2 v5) {
    float minDist = 1e20;
    float inside = 1.0;

    processEdge(p, v0, v1, minDist, inside);
    processEdge(p, v1, v2, minDist, inside);
    processEdge(p, v2, v3, minDist, inside);
    processEdge(p, v3, v4, minDist, inside);
    processEdge(p, v4, v5, minDist, inside);
    processEdge(p, v5, v0, minDist, inside);

    float dist = sqrt(max(minDist, 0.0));
    return mix(dist, -dist, inside);
}

float sdRectangle(in vec2 p, in vec2 center, in vec2 halfSize) {
    vec2 d = abs(p - center) - halfSize;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

struct Quad {
    vec2 topLeft;
    vec2 topRight;
    vec2 bottomLeft;
    vec2 bottomRight;
};

Quad getQuad(vec2 pos, vec2 size) {
    Quad q;
    q.topLeft = pos;
    q.topRight = pos + vec2(size.x, 0.0);
    q.bottomLeft = pos - vec2(0.0, size.y);
    q.bottomRight = pos + vec2(size.x, -size.y);
    return q;
}

void selectTrailCorners(Quad q, vec2 sel, out vec2 p1, out vec2 p2, out vec2 p3) {
    p1 = mix(mix(q.topRight, q.topLeft, sel.x),
             mix(q.bottomRight, q.bottomLeft, sel.x),
             sel.y);

    p2 = mix(mix(q.topLeft, q.bottomLeft, sel.x),
             mix(q.topRight, q.bottomRight, sel.x),
             sel.y);
    p3 = mix(mix(q.bottomRight, q.topRight, sel.x),
             mix(q.bottomLeft, q.topLeft, sel.x),
             sel.y);

}

void selectCorners(Quad q, vec2 sel, out vec2 p1, out vec2 p2, out vec2 p3, out vec2 p4) {
    selectTrailCorners(q, sel, p1, p2, p3);

    p4 = mix(mix(q.bottomLeft, q.bottomRight, sel.x),
             mix(q.topLeft, q.topRight, sel.x),
             sel.y);
}

float easeClamped(float x) {
    float t = 1.0 - x;
    return 1.0 - t * t * t;
}

const float DURATION = 0.5;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    float baseProgress = clamp((iTime - iTimeCursorChange) / DURATION, 0.0, 1.0);

    vec2 uv = fragCoord / iResolution.xy;
    vec4 background = texture(iChannel0, uv);

    if (baseProgress >= 1.0) {
        fragColor = background;
        return;
    }

    fragColor = background;

    float invResY = 1.0 / iResolution.y;
    float scale = 2.0 * invResY;
    float aaWidth = scale;
    vec2 normOffset = iResolution.xy * invResY;

    vec2 currentPos = iCurrentCursor.xy * scale - normOffset;
    vec2 previousPos = iPreviousCursor.xy * scale - normOffset;
    vec2 currentSize = iCurrentCursor.zw * scale;
    vec2 previousSize = iPreviousCursor.zw * scale;

    vec2 deltaPos = currentPos - previousPos;
    Quad currentCursor = getQuad(currentPos, currentSize);
    Quad previousCursor = getQuad(previousPos, previousSize);
    vec2 selector = step(vec2(0.0), deltaPos);

    vec2 currP1, currP2, currP3, currP4;
    vec2 prevP1, prevP2, prevP3;
    selectCorners(currentCursor, selector, currP1, currP2, currP3, currP4);
    selectTrailCorners(previousCursor, selector, prevP1, prevP2, prevP3);

    float easedProgress = easeClamped(baseProgress);
    float stretchedProgress = min(baseProgress * 2.0, 1.0);
    float easedProgressDouble = easeClamped(stretchedProgress);

    vec2 trailP1 = mix(prevP1, currP1, easedProgress);
    vec2 trailP2 = mix(prevP2, currP2, easedProgressDouble);
    vec2 trailP3 = mix(prevP3, currP3, easedProgressDouble);

    vec2 normCoord = fragCoord * scale - normOffset;
    float sdfHex = sdHexagon(normCoord, trailP1, trailP2, currP2, currP4, currP3, trailP3);
    float alpha = 1.0 - smoothstep(-aaWidth, aaWidth, sdfHex);

    vec2 halfCurrentSize = currentSize * 0.5;
    vec2 currentCenter = currentPos + vec2(halfCurrentSize.x, -halfCurrentSize.y);
    float sdfCurrentCursor = sdRectangle(normCoord, currentCenter, halfCurrentSize);

    float v1v = sin(normCoord.x * 10.0 + iTime);
    float v2v = sin(normCoord.y * 10.0 + iTime * 4.5);
    float v3v = sin((normCoord.x + normCoord.y) * 10.0 + iTime * 0.5);
    float v4v = sin(length(normCoord) * 10.0 + iTime * 2.0);

    float plasma = (v1v + v2v + v3v + v4v) / 4.0;
    vec4 rainbowColor = vec4(
            0.5 + 0.5 * sin(plasma * 6.28 + 0.0),
            0.5 + 0.5 * sin(plasma * 6.28 + 2.09),
            0.5 + 0.5 * sin(plasma * 6.28 + 4.18),
            1.0
        );

    vec2 previousCenter = previousPos + vec2(previousSize.x * 0.5, -previousSize.y * 0.5);
    float lineLength = distance(currentCenter, previousCenter);

    float distFromCursor = distance(normCoord, currentCenter);
    float fadeFactor = 1.0 - smoothstep(0.0, lineLength, distFromCursor);

    vec4 fadedRainbowColor = rainbowColor * fadeFactor;

    float gray = dot(fadedRainbowColor.rgb, vec3(0.299, 0.587, 0.114));
    const float saturationBoost = 1.8;
    vec4 enhancedColor = clamp(
        mix(vec4(vec3(gray), fadedRainbowColor.a), fadedRainbowColor, saturationBoost),
        0.0, 1.0
    );

    vec4 originalColor = fragColor;
    fragColor.rgb = mix(fragColor.rgb, enhancedColor.rgb, alpha);

    fragColor.rgb = mix(fragColor.rgb, originalColor.rgb, step(sdfCurrentCursor, 0.0));
}
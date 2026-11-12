package dev.mahali.hatch.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class PortGlobalsTest {
    @Test fun `globals set from uniforms become macros, everything else is left alone`() {
        val source = """
vec4 TRAIL_COLOR = vec4(sRGBToLinear(iCurrentCursorColor.rgb), 1.0); // custom colour here
const float DURATION = 0.3;
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec4 c = TRAIL_COLOR;
}"""
        assertEquals(
            """
#define TRAIL_COLOR (vec4(sRGBToLinear(iCurrentCursorColor.rgb), 1.0))
const float DURATION = 0.3;
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec4 c = TRAIL_COLOR;
}""",
            portGlobals(source),
        )
    }
}

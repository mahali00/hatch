package dev.mahali.hatch.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinkTest {
    @Test fun onlyHttpAndHttpsAreOffered() {
        assertTrue(isWebLink("https://rex.example/pane"))
        assertTrue(isWebLink("http://100.64.0.1:7000"))
        assertTrue(isWebLink("HTTP://Example"))
        assertFalse(isWebLink("javascript:alert(1)"))
        assertFalse(isWebLink("intent://scan/#Intent;scheme=hatch;end"))
        assertFalse(isWebLink("file:///system/etc/hosts"))
        assertFalse(isWebLink("https:example.com"))
        assertFalse(isWebLink("not a url"))
    }
}

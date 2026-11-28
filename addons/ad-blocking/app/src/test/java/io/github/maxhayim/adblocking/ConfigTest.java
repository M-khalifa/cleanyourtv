package io.github.maxhayim.adblocking;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConfigTest {
    @Test
    public void passwordKeepsItsSpacesOtherValuesAreCleaned() {
        assertEquals(" pass ", Config.clean("password", " pass "));
        assertEquals("admin", Config.clean("user", " admin "));
        assertEquals("http://192.168.1.2", Config.clean("url", "192.168.1.2/admin/"));
        assertEquals("", Config.clean("password", null));
    }
}

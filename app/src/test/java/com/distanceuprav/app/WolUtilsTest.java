package com.distanceuprav.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class WolUtilsTest {
    @Test public void normalizeMacStaysCompatibleWithPreviousReleases() {
        assertEquals("AA:BB:CC:DD:EE:FF", WolUtils.normalizeMac("aa-bb-cc-dd-ee-ff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WolUtils.normalizeMac("AABBCCDDEEFF"));
        assertNull(WolUtils.normalizeMac("00:00:00:00:00:00"));
    }

    @Test public void subnetSelectionUsesRealPrefixNotOnlyFirstThreeOctets() {
        WolUtils.NetworkDetails wifi24 = new WolUtils.NetworkDetails(null,
            "192.168.0.36", 24, "192.168.0.255", "wlan0", true);
        assertTrue(WolUtils.sameSubnet(wifi24, "192.168.0.105"));
        assertFalse(WolUtils.sameSubnet(wifi24, "192.168.1.105"));
        WolUtils.NetworkDetails wifi23 = new WolUtils.NetworkDetails(null,
            "192.168.0.36", 23, "192.168.1.255", "wlan0", true);
        assertTrue(WolUtils.sameSubnet(wifi23, "192.168.1.105"));
    }

    @Test public void invalidIpv4Rejected() {
        assertFalse(WolUtils.isValidIpv4("192.168.0.999"));
        assertFalse(WolUtils.isValidIpv4("example.org"));
        assertTrue(WolUtils.isValidIpv4("192.168.0.100"));
    }
}

package com.fredhli.flowwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cleartext gate: http:// only ever reaches private-range / tailnet hosts. */
class PrivateHostTest {

    @Test
    fun `loopback and localhost allowed`() {
        assertTrue(FlowApi.isPrivateHost("localhost"))
        assertTrue(FlowApi.isPrivateHost("127.0.0.1"))
        assertTrue(FlowApi.isPrivateHost("127.5.5.5"))
        assertTrue(FlowApi.isPrivateHost("::1"))
        assertTrue(FlowApi.isPrivateHost("[::1]"))
    }

    @Test
    fun `rfc1918 ranges allowed`() {
        assertTrue(FlowApi.isPrivateHost("10.0.0.7"))
        assertTrue(FlowApi.isPrivateHost("192.168.1.50"))
        assertTrue(FlowApi.isPrivateHost("172.16.0.1"))
        assertTrue(FlowApi.isPrivateHost("172.31.255.255"))
        assertTrue(FlowApi.isPrivateHost("169.254.10.10"))
    }

    @Test
    fun `tailnet cgnat range allowed`() {
        assertTrue(FlowApi.isPrivateHost("100.102.21.29")) // fr-wsl-vpn
        assertTrue(FlowApi.isPrivateHost("100.64.0.1"))
        assertTrue(FlowApi.isPrivateHost("100.127.255.254"))
    }

    @Test
    fun `public addresses and hostnames refused`() {
        assertFalse(FlowApi.isPrivateHost("8.8.8.8"))
        assertFalse(FlowApi.isPrivateHost("100.63.255.255"))   // below CGNAT
        assertFalse(FlowApi.isPrivateHost("100.128.0.0"))      // above CGNAT
        assertFalse(FlowApi.isPrivateHost("172.15.0.1"))       // below 172.16/12
        assertFalse(FlowApi.isPrivateHost("172.32.0.1"))       // above 172.16/12
        assertFalse(FlowApi.isPrivateHost("192.169.0.1"))
        assertFalse(FlowApi.isPrivateHost("dashboard.fredhli.com"))
        assertFalse(FlowApi.isPrivateHost("evil.10.0.0.1.example.com"))
        assertFalse(FlowApi.isPrivateHost(""))
        assertFalse(FlowApi.isPrivateHost(null))
    }

    @Test
    fun `malformed ipv4 literals refused`() {
        assertFalse(FlowApi.isPrivateHost("10.0.0"))
        assertFalse(FlowApi.isPrivateHost("10.0.0.0.1"))
        assertFalse(FlowApi.isPrivateHost("10.0.0.256"))
        assertFalse(FlowApi.isPrivateHost("10.0.0.-1"))
        assertFalse(FlowApi.isPrivateHost("010.0.0.1"))  // octal-looking, refused
        assertFalse(FlowApi.isPrivateHost("10.0.0.x"))
    }
}

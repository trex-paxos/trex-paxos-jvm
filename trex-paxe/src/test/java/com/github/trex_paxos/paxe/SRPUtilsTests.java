package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.trex_paxos.paxe.SRPUtils.Constants;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.stream.IntStream;

import static com.github.trex_paxos.paxe.SRPUtils.*;

/// Using the Secure Remote Password (SRP) Protocol for TLS Authentication
/// This directly follows RFC 5054 at https://www.ietf.org/rfc/rfc5054.txt
/// This test case verifies SRPUtils.java using the test vectors in the RFC.
class SRPUtilsTests {

        static {
                System.setProperty(SRPUtils.class.getName() + ".useHash", "SHA-1");
        }

        @BeforeAll
        static void setup() {
                assert "SHA-1" == SRPUtils.ALGORITHM;
        }

        @Test
        void testAppendixB_N() {
                assertEquals("SHA-1", SRPUtils.ALGORITHM);

                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                byte[] nBytes = fromHex(hexN);
                String hexPrimaActual = toHex(nBytes);

                assertEquals(hexN, hexPrimaActual, "N value does not match RFC 5054 Appendix B");
        }

        @Test
        void testAppendixB_verifier() {

                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String hexG = "2";

                // Setup expected test vector values from RFC 5054 Appendix B
                String I = "alice";
                String P = "password123";
                byte[] s = fromHex("BEB25379D1A8581EB5A727673A2441EE");

                BigInteger expectedV = new BigInteger(
                                "7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D812" +
                                                "9BADA1F1822223CA1A605B530E379BA4729FDC59F105B4787E5186F5" +
                                                "C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5" +
                                                "EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78" +
                                                "E955A5E29E7AB245DB2BE315E2099AFB",
                                16);

                final var c = new SRPUtils.Constants(hexN, hexG);

                // Test verifier generation
                BigInteger actualV = SRPUtils.generateVerifier(c, I, P, s);
                assertEquals(expectedV, actualV, "'v' value does not match RFC 5054 Appendix B");
        }

        @Test
        void testAppendixB_k() {

                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String hexG = "2";

                String expectedK = "7556AA045AEF2CDD07ABAF0F665C3E818913186F";

                String hex = SRPUtils.k(hexN, hexG);

                assertEquals(expectedK, hex, "'k' value does not match RFC 5054 Appendix B");
        }

        @Test
        void testAppendixB_B() {

                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String hexG = "2";

                String serverPrivateKey = "E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20";

                String serverPublicKey = "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011" + //
                                "BAF38964DC46A0670DD125B95A981652236F99D9B681CBF87837EC99" + //
                                "6C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA" + //
                                "37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE" + //
                                "EB4012B7D7665238A8E3FB004B117B58";

                BigInteger expectedV = new BigInteger(
                                "7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D812" +
                                                "9BADA1F1822223CA1A605B530E379BA4729FDC59F105B4787E5186F5" +
                                                "C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5" +
                                                "EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78" +
                                                "E955A5E29E7AB245DB2BE315E2099AFB",
                                16);

                // BigInteger b, BigInteger v, BigInteger k, BigInteger g, BigInteger N
                final var B = SRPUtils.B(
                                integer(serverPrivateKey),
                                expectedV,
                                integer(SRPUtils.k(hexN, hexG)),
                                integer(hexG),
                                integer(hexN));

                assertEquals(integer(serverPublicKey), B, "'B' value does not match RFC 5054 Appendix B");

        }

        @Test
        void testAppendixB_A() {
                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String hexG = "2";

                final var clientPrivateKey = "60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393";

                final var clientPublicKey = "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC4" +
                                "4352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC" +
                                "8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44" +
                                "BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA" +
                                "B349EF5D76988A3672FAC47B0769447B";

                final var A = SRPUtils.A(integer(clientPrivateKey), integer(hexG), integer(hexN));
                assertEquals(integer(clientPublicKey), A, "'A' value does not match RFC 5054 Appendix B");
        }

        @Test
        void testAppendixB_u() {
                String hexN = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" + //
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" + //
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" + //
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" + //
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                final var A = "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC4" +
                                "4352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC" +
                                "8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44" +
                                "BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA" +
                                "B349EF5D76988A3672FAC47B0769447B";
                final var B = "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011" + //
                                "BAF38964DC46A0670DD125B95A981652236F99D9B681CBF87837EC99" + //
                                "6C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA" + //
                                "37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE" + //
                                "EB4012B7D7665238A8E3FB004B117B58";
                final var expectedU = "CE38B9593487DA98554ED47D70A7AE5F462EF019";
                final var actualU = SRPUtils.u(hexN, A, B);
                assertEquals(expectedU, actualU, "'u' value does not match RFC 5054 Appendix B");
        }

        @Test
        void testAppendixB_ClientSecret() {

                // Setup expected test vector values from RFC 5054 Appendix B
                String I = "alice";
                String P = "password123";
                String sHex = "BEB25379D1A8581EB5A727673A2441EE";

                String NHex = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" +
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" +
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" +
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" +
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String gHex = "2";

                Constants c = new Constants(NHex, gHex);

                final var aHex = """
                                60975527 035CF2AD 1989806F 0407210B C81EDC04 E2762A56 AFD529DD
                                DA2D4393
                                """.replaceAll("\\s+", "");

                final var AHex = "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC4" +
                                "4352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC" +
                                "8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44" +
                                "BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA" +
                                "B349EF5D76988A3672FAC47B0769447B";

                final var BHex = "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011" +
                                "BAF38964DC46A0670DD125B95A981652236F99D9B681CBF87837EC99" +
                                "6C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA" +
                                "37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE" +
                                "EB4012B7D7665238A8E3FB004B117B58";

                final var expectedPremaster = """
                                B0DC82BA BCF30674 AE450C02 87745E79 90A3381F 63B387AA F271A10D
                                233861E3 59B48220 F7C4693C 9AE12B0A 6F67809F 0876E2D0 13800D6C
                                41BB59B6 D5979B5C 00A172B4 A2A5903A 0BDCAF8A 709585EB 2AFAFA8F
                                3499B200 210DCC1F 10EB3394 3CD67FC8 8A2F39A4 BE5BEC4E C0A3212D
                                C346D7E4 74B29EDE 8A469FFE CA686E5A
                                """.replaceAll("\\s+", "");

                final var premaster = SRPUtils.clientS(c, AHex, BHex, sHex, I, aHex, P);

                assertEquals(expectedPremaster, premaster.toUpperCase(),
                                "Premaster secret does not match RFC 5054 Appendix B");

                final var finalKey = SRPUtils.hashedSecret(NHex, premaster);
                assertTrue(finalKey.length == 20, "Final key length is not 20 bytes when hashed");
        }

        @Test
        void testAppendixB_ServerSecret() {
                String NHex = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" +
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" +
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" +
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" +
                                "FD5138FE8376435B9FC61D2FC0EB06E3";

                String gHex = "2";

                Constants c = new Constants(NHex, gHex);

                final var bHex = """
                                E487CB59 D31AC550 471E81F0 0F6928E0 1DDA08E9 74A004F4 9E61F5D1 05284D20
                                """.replaceAll("\\s+", "");

                final var AHex = "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC4" +
                                "4352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC" +
                                "8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44" +
                                "BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA" +
                                "B349EF5D76988A3672FAC47B0769447B";

                final var BHex = "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011" +
                                "BAF38964DC46A0670DD125B95A981652236F99D9B681CBF87837EC99" +
                                "6C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA" +
                                "37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE" +
                                "EB4012B7D7665238A8E3FB004B117B58";

                final var expectedPremaster = """
                                B0DC82BA BCF30674 AE450C02 87745E79 90A3381F 63B387AA F271A10D
                                233861E3 59B48220 F7C4693C 9AE12B0A 6F67809F 0876E2D0 13800D6C
                                41BB59B6 D5979B5C 00A172B4 A2A5903A 0BDCAF8A 709585EB 2AFAFA8F
                                3499B200 210DCC1F 10EB3394 3CD67FC8 8A2F39A4 BE5BEC4E C0A3212D
                                C346D7E4 74B29EDE 8A469FFE CA686E5A
                                """.replaceAll("\\s+", "");

                final var vHex = "7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D812" +
                                "9BADA1F1822223CA1A605B530E379BA4729FDC59F105B4787E5186F5" +
                                "C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5" +
                                "EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78" +
                                "E955A5E29E7AB245DB2BE315E2099AFB";

                // Constants c, String vHex, String AHex, String BHex, String bHex
                final var premaster = SRPUtils.serverS(c, vHex, AHex, BHex, bHex);

                assertEquals(expectedPremaster, premaster.toUpperCase(),
                                "Premaster secret does not match RFC 5054 Appendix B");

                final var finalKey = SRPUtils.hashedSecret(NHex, premaster);
                assertTrue(finalKey.length == 20, "Final key length is not 20 bytes when hashed");
        }

        @Test
        public void testSecretKeyGeneration() {
                String NHex = "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C" +
                                "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4" +
                                "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29" +
                                "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A" +
                                "FD5138FE8376435B9FC61D2FC0EB06E3";
                final var N = integer(NHex);
                IntStream.range(0, 1000).forEach(_ -> {
                        final var r = SRPUtils.generatedPrivateKey(NHex);
                        assertNotNull(r);
                        final var secret = integer(r);
                        assertTrue(secret.compareTo(N) < 1);
                        assertTrue(secret.compareTo(BigInteger.ZERO) > 0);
                });
        }
}
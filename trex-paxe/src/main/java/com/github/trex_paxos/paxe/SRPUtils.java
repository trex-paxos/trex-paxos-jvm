// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.HexFormat;
import java.util.List;

/// Using the Secure Remote Password (SRP) Protocol for TLS Authentication
/// This directly follows [RFC 5054](https://www.ietf.org/rfc/rfc5054.txt)
/// There is a corresponding test suite in SRPUtilsTests.java that verifies using the test vectors in the RFC.
/// This class handles large integers as hexadecimal strings which it treats as unsigned.
/// It is careful to test against the RFC test vectors to ensure that the math and the encoding is correct.
public final class SRPUtils {

  /// Generate a random salt which is used to salt the password. This is required for human generated passwords to prevent dictionary attacks.
  /// It is part of the SRP protocol.
  /// @return The salt which is 16 bytes long so 128 bits of secure randomness
  public static byte[] generateSalt() {
    // 16 bytes is 128 bits which is enough randomness
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return salt;
  }

  /// Generate a random private key which is used to generate the public key. This is required for the SRP protocol that the
  /// private key is a random number between 1 and N-1 where N is the prime number used in the protocol. Due to the built-in
  /// signed nature of BigInteger we have to loop until we get a number that is greater than zero and also less than N.
  /// @param hexN The prime number N used in the protocol as a hex string.
  /// @return The private key as a hex string in upper case.
  public static String generatedPrivateKey(String hexN) {
    final var SecureRandom = new SecureRandom();
    final var N = integer(hexN);
    final var length = fromHex(hexN).length;
    BigInteger result = BigInteger.ZERO;
    while (result.compareTo(BigInteger.ZERO) < 1 || result.compareTo(N) > -1) {
      byte[] bytes = new byte[length];
      SecureRandom.nextBytes(bytes);
      result = new BigInteger(1, bytes);
    }
    return result.toString(16).toUpperCase();
  }

  /// Protocol constants. You can use openssh to create your own safe prime and generator.
  /// @param N The prime number N used in the protocol as a hex string.
  /// @param g The generator g used in the protocol as a hex string.
  /// @param k This hex string must be computed using the k function in the protocol so only use the two argument constructor.
  public record Constants(String N, String g, String k) {
    public Constants(String N, String g) {
      this(N, g, SRPUtils.k(N, g));
    }

    public Constants {
      if (N == null || g == null || k == null) {
        throw new IllegalArgumentException("N, g and k cannot be null");
      }
      if (N.length() % 2 != 0) {
        throw new IllegalArgumentException("N must be an even length hex string");
      }
    }
  }

  /// This is where bytes are hashed. Care should be taken that padding is applied where required in RFC 5054
  public static byte[] H(byte[]... inputs) {
    MessageDigest md = getMessageDigest();
    for (byte[] input : inputs) {
      md.update(input);
    }
    return md.digest();
  }

  /// This is the `k` function in the protocol. This method is tested against the RFC 5054 test vectors as it pads `g` correctly.
  /// @param N The prime number N used in the protocol as a hex string.
  /// @param g The generator g used in the protocol as a hex string.
  /// @return The `k` id as a hex string.
  public static String k(String N, String g) {
    byte[] nBytes = fromHex(N);
    byte[] getBytes = fromHex(g);
    byte[] paddedBytes = new byte[nBytes.length];
    System.arraycopy(getBytes, 0, paddedBytes, nBytes.length - getBytes.length, getBytes.length);
    byte[] hashed = SRPUtils.H(nBytes, paddedBytes);
    return toHex(hashed);
  }

  /// All of our generated binary numbers are positive.
  public static BigInteger integer(byte[] bs) {
    return new BigInteger(1, bs);
  }

  /// Math is done using positive BigInteger numbers.
  public static BigInteger integer(String hex) {
    return new BigInteger(hex, 16);
  }

  /// This is required to hash the user identity `I` and password `P` and the separator ":"
  public static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /// Generate the verifier from the password and salt. It should be computed at the client and stored at the server so that
  /// the password never leaves the client.
  /// @param c The protocol constants `N` and `g`.
  /// @param Identity The user identity.
  /// @param Password The user password.
  /// @param s The salt which is a random number used to salt the password to prevent dictionary attacks or leaked password lookups.
  public static BigInteger generateVerifier(Constants c, String Identity, String Password, byte[] s) {
    final var N = integer(c.N());
    final var g = integer(c.g());
    final var I = bytes(Identity);
    final var P = bytes(Password);
    // x = H(s, H(I | ":" | P))
    final var x = integer(H(s, H(I, bytes(":"), P)));
    // v = g^x % N
    return g.modPow(x, N);
  }

  /// Generate the server public key `B` from the server per session private key `b`. This is done at the server and the public key `B` is sent to the server.
  /// The server private must not be reused, and it must not be transmitted. It is only needed long enough to generate the shared premaster secret key.
  /// @return B = k*v + g^b % N
  public static BigInteger B(BigInteger b, BigInteger v, BigInteger k, BigInteger g, BigInteger N) {
    return v.multiply(k).add(g.modPow(b, N)).mod(N);
  }

  /// Generate the client public key `A` from the server per session private key `a`. This is done at the client and the public key `A` is sent to the server.
  /// The server private must not be reused, and it must not be transmitted. It is only needed long enough to generate the shared premaster secret key.
  /// @return A = g^a % N
  public static BigInteger A(BigInteger a, BigInteger g, BigInteger N) {
    return g.modPow(a, N);
  }

  /// This is the `u` function in the protocol. This method is tested against the RFC 5054 test vectors to check that it pads `A` and `B` correctly.
  /// @return u = H(PAD(A), PAD(B))
  public static String u(String N, String a, String b) {
    byte[] nBytes = fromHex(N);

    byte[] aBytes = fromHex(a);
    byte[] paddedABytes = new byte[nBytes.length];
    System.arraycopy(aBytes, 0, paddedABytes,
        nBytes.length - aBytes.length,    // Use aBytes.length for offset
        aBytes.length);                   // Copy only aBytes.length bytes

    byte[] bBytes = fromHex(b);
    byte[] paddedBBytes = new byte[nBytes.length];
    System.arraycopy(bBytes, 0, paddedBBytes,
        nBytes.length - bBytes.length,    // Use bBytes.length for offset
        bBytes.length);                   // Copy only bBytes.length bytes

    byte[] hashed = SRPUtils.H(paddedABytes, paddedBBytes);
    return toHex(hashed);
  }

  /// Computes the session key `S` from client-side secret data which includes the user password.
  /// This is tested against the RFC 5054 test vectors to ensure that the padding and the math is correct.
  /// This is the final step in the protocol where the client computes the shared secret key `S` from the server public key `B`, the client private key `a`, and the password `P`.
  /// @return S = (B - (k * g^x)) ^ (a + (u * x)) % N
  public static String clientS(Constants c, String AHex, String BHex, String sHex, String identity, String aHex, String password) {

    final var N = integer(c.N());

    if (integer(AHex).mod(N).equals(BigInteger.ZERO)) {
      throw new IllegalArgumentException("A mod N cannot be zero");
    }

    if (integer(BHex).mod(N).equals(BigInteger.ZERO)) {
      throw new IllegalArgumentException("B mod N cannot be zero");
    }

    // u = SHA1(PAD(A) | PAD(B))
    BigInteger u = integer(u(c.N(), AHex, BHex));

    // x = SHA1(s | SHA1(I | ":" | P))
    BigInteger x = integer(H(fromHex(sHex), H(bytes(identity), bytes(":"), bytes(password))));

    final var g = integer(c.g());
    final var k = integer(c.k());
    final var a = integer(aHex);
    final var B = integer(BHex);

    final BigInteger exp = u.multiply(x).add(a);
    final BigInteger tmp = g.modPow(x, N).multiply(k);
    final BigInteger result = B.subtract(tmp).modPow(exp, N);
    return result.toString(16);
  }

  /// Computes the session key `S` from server-side using the password verifier `v`, the client public key `A`, the server private key `b`, and the protocol constants.
  /// This is tested against the RFC 5054 test vectors to ensure that the padding and the math is correct.
  /// This is the final step in the protocol where the client computes the shared secret key `S` from the server public key `B`, the client private key `a`, and the password `P`.
  /// @return S = (A * v^u) ^ b % N
  public static String serverS(Constants c, String vHex, String AHex, String BHex, String bHex) {
    final var N = integer(c.N());

    if (integer(AHex).mod(N).equals(BigInteger.ZERO)) {
      throw new IllegalArgumentException("A mod N cannot be zero");
    }

    if (integer(BHex).mod(N).equals(BigInteger.ZERO)) {
      throw new IllegalArgumentException("B mod N cannot be zero");
    }

    // u = SHA1(PAD(A) | PAD(B))
    final var u = integer(u(c.N(), AHex, BHex));
    final var A = integer(AHex);
    final var b = integer(bHex);
    final var v = integer(vHex);
    return v.modPow(u, N).multiply(A).modPow(b, N).toString(16);
  }

  public final static int AES_256_KEY_SIZE = 32;

  public static byte[] hashedSecret(String N, String premaster) {
    byte[] nBytes = fromHex(N);
    byte[] aBytes = fromHex(premaster);
    if (aBytes.length < nBytes.length) {
      byte[] paddedABytes = new byte[nBytes.length];
      // Copy original bytes to the end of the padded array
      System.arraycopy(aBytes, 0, paddedABytes,
          nBytes.length - aBytes.length,  // Destination offset uses aBytes.length
          aBytes.length);                 // Copy only aBytes.length bytes
      aBytes = paddedABytes;
    }
    var raw = SRPUtils.H(aBytes);
    if (raw.length < AES_256_KEY_SIZE) {
      try {
        // Use SimpleHKDF to derive a key of size AES_256_KEY_SIZE
        byte[] prk = SimpleHKDF.extract(null, raw); // Step 1: Extract phase
        raw = SimpleHKDF.expand(prk, "rfc-5054-hash".getBytes(), AES_256_KEY_SIZE); // Step 2: Expand phase
      } catch (Exception e) {
        throw new RuntimeException("Failed to expand key", e);
      }
    }
    return raw;
  }

  /// The list of message digest algorithms to use in order of preference. The first one that is available on the system will be used.
  /// This list favours 256 bit algorithms to be compatible with AES encryption.
  static public final List<String> MESSAGE_DIGEST_PREFERENCES = List.of(
      "SHA3-256",
      "SHA-512/256",
      "SHA-256");

  /// The [java.security.MessageDigest] algorithm to use for hashing. This can be overridden by setting the system property
  /// `com.github.trex_paxos.paxe.SRPUtils.useHash` to a string such as "SHA-1" or "SHA-256". This is primarily used for testing
  /// where we force the use of the SHA-1 algorithm to test against the RFC test vectors that use SHA-1.
  /// The default is the first algorithm in the [SRPUtils#MESSAGE_DIGEST_PREFERENCES] list that is available on the system.
  public static final String BEST_ALGORITHM = bestAlgorithm();

  public static MessageDigest getMessageDigest() {
    try {
      final var choice = System.getProperty(SRPUtils.class.getName() + ".useHash", BEST_ALGORITHM);
      return MessageDigest.getInstance(choice);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Failed to initialize MessageDigest trying to get algorithm '" + BEST_ALGORITHM
          + "' check that there is not a system property override setting a bad id the available ones are: "
          +
          String.join(",", Security.getAlgorithms("MessageDigest")), e);
    }
  }

  public static String bestAlgorithm() {
    final var defaultChoice = MESSAGE_DIGEST_PREFERENCES.stream()
        .filter(alg -> {
          try {
            MessageDigest.getInstance(alg);
            return true;
          } catch (NoSuchAlgorithmException e) {
            return false;
          }
        })
        .findFirst()
        .orElse("SHA-256");
    if (!Security.getAlgorithms("MessageDigest").contains(defaultChoice)) {
      throw new IllegalArgumentException(
          "The algorithm '" + defaultChoice + "' is not available on this system. The available ones are: " +
              (String.join(",", Security.getAlgorithms("MessageDigest")) +
                  " use the system property " + SRPUtils.class.getName() + ".useHash"
                  + " to set a valid algorithm."));
    }
    return defaultChoice;
  }

  // To test with the RFC 5054 test vectors we need to convert between byte arrays and hex strings that are in upper case.
  public static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

  /// Convert a hex string to a byte array. The [java.util.HexFormat] class with throw an error if the string has an odd length so we pad with a zero.
  /// @param hex The hex string to convert
  /// @return The byte array
  public static byte[] fromHex(String hex) {
    if (hex.length() % 2 != 0) {
      hex = "0" + hex; // Pad with leading zero
    }
    return HEX_FORMAT.parseHex(hex);
  }

  /// Convert a byte array to a hex string
  /// @param bytes The byte array to convert
  /// @return The hex string
  public static String toHex(byte[] bytes) {
    return HEX_FORMAT.formatHex(bytes);
  }
}


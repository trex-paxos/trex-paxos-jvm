package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickleHandshakeTest {
    
    @Test
    void shouldPickleAndUnpickleRequest() {
        NodeId from = new NodeId((short)1);
        byte[] publicKey = new byte[]{1, 2, 3, 4};
        var request = new KeyMessage.KeyHandshakeRequest(from, publicKey);
        
        byte[] pickled = PickleHandshake.pickle(request);
        KeyMessage unpickled = PickleHandshake.unpickle(pickled);

        assertTrue(unpickled instanceof KeyMessage.KeyHandshakeRequest);
        var unpackedRequest = (KeyMessage.KeyHandshakeRequest) unpickled;
        assertEquals(from, unpackedRequest.from());
        assertArrayEquals(publicKey, unpackedRequest.publicKey());
    }

    @Test
    void shouldPickleAndUnpickleResponse() {
        NodeId from = new NodeId((short)2);
        byte[] publicKey = new byte[]{5, 6, 7, 8};
        var response = new KeyMessage.KeyHandshakeResponse(from, publicKey);
        
        byte[] pickled = PickleHandshake.pickle(response);
        KeyMessage unpickled = PickleHandshake.unpickle(pickled);

        assertTrue(unpickled instanceof KeyMessage.KeyHandshakeResponse);
        var unpackedResponse = (KeyMessage.KeyHandshakeResponse) unpickled;
        assertEquals(from, unpackedResponse.from());
        assertArrayEquals(publicKey, unpackedResponse.publicKey());
    }

    @Test
    void shouldFailOnInvalidType() {
        byte[] invalid = new byte[]{99, 0, 1, 0, 0, 0, 1, 0}; // Invalid type 99
        assertThrows(IllegalStateException.class, () -> 
            PickleHandshake.unpickle(invalid)
        );
    }
}
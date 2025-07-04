package com.example.flurfunk.network;

import android.content.Context;

import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Protocol;
import com.example.flurfunk.util.SecureCrypto;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link PeerSyncManager} class, which handles the synchronization
 * of {@link com.example.flurfunk.model.UserProfile} data across LoRa-connected devices.
 * <p>
 * Tests include:
 * <ul>
 *     <li>Sending peer summaries (SYNPR)</li>
 *     <li>Handling remote peer summaries and requesting outdated profiles</li>
 *     <li>Encrypting and sending peer profile data (PRDAT)</li>
 *     <li>Decrypting and storing received peer data</li>
 *     <li>Gracefully ignoring outdated or invalid peer data</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class PeerSyncManagerTest {

    private Context context;
    private UserProfile localProfile;
    private LoRaManager loRaManager;
    private PeerSyncManager peerSyncManager;

    @Before
    public void setUp() {
        context = mock(Context.class);
        localProfile = mock(UserProfile.class);
        loRaManager = mock(LoRaManager.class);

        when(localProfile.getId()).thenReturn("local-id");
        when(localProfile.getMeshId()).thenReturn("mesh-001");
        when(localProfile.getLastSeen()).thenReturn(123456789L);

        peerSyncManager = new PeerSyncManager(context, localProfile, loRaManager);
    }

    /**
     * Verifies that {@link PeerSyncManager#sendPeerSync()} collects active peers and
     * broadcasts a SYNPR message using {@link LoRaManager}.
     */
    @Test
    public void testSendPeerSync() {
        UserProfile peer = new UserProfile();
        peer.setId("peer-123");
        peer.setMeshId("mesh-001");
        peer.setTimestamp(100L);

        try (MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)) {
            peerMock.when(() -> PeerManager.loadPeers(context)).thenReturn(Collections.singletonList(peer));
            peerMock.when(() -> PeerManager.isPeerActive(peer)).thenReturn(true);

            peerSyncManager.sendPeerSync();

            verify(loRaManager, atLeastOnce()).sendBroadcast(anyString());
        }
    }

    /**
     * Tests that {@link PeerSyncManager#handlePeerList(Protocol.ParsedMessage)} correctly
     * identifies outdated profiles and triggers a request (REQPR) for missing updates.
     */
    @Test
    public void testHandlePeerList() throws Exception {
        UserProfile localPeer = new UserProfile();
        localPeer.setId("peer-123");
        localPeer.setTimestamp(100L);

        try (MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)) {
            peerMock.when(() -> PeerManager.loadPeers(context)).thenReturn(Collections.singletonList(localPeer));

            JSONArray remote = new JSONArray();
            JSONObject summary = new JSONObject();
            summary.put(Protocol.KEY_UID, "peer-123");
            summary.put(Protocol.KEY_TS, 9999);
            remote.put(summary);

            HashMap<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_UID, "remote-sender");
            payload.put(Protocol.KEY_MID, "mesh-001");
            payload.put(Protocol.KEY_LS, "123456789");
            payload.put(Protocol.KEY_PRS, remote.toString());

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.SYNPR, payload);

            PeerSyncManager spy = Mockito.spy(peerSyncManager);
            spy.handlePeerList(msg);

            verify(spy).sendPeerRequest(Collections.singletonList("peer-123"));
        }
    }

    /**
     * Ensures that {@link PeerSyncManager#handlePeerRequest(Protocol.ParsedMessage)}
     * encrypts the requested peer data using {@link SecureCrypto} and broadcasts a PRDAT message.
     */
    @Test
    public void testHandlePeerRequest() throws Exception {
        UserProfile peer = new UserProfile();
        peer.setId("peer-001");
        peer.setName("Max");
        peer.setPhone("123");
        peer.setEmail("x@example.com");
        peer.setFloor("EG");
        peer.setTimestamp(123456789L);
        peer.setMeshId("mesh-001");

        SecureCrypto.EncryptedPayload encrypted = new SecureCrypto.EncryptedPayload("iv123", "ciphertext");

        try (
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class);
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class)
        ) {
            peerMock.when(() -> PeerManager.loadPeers(context)).thenReturn(Collections.singletonList(peer));
            cryptoMock.when(() -> SecureCrypto.encrypt(anyString(), eq("mesh-001"))).thenReturn(encrypted);

            JSONArray req = new JSONArray().put("peer-001");
            HashMap<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_MID, "mesh-001");
            payload.put(Protocol.KEY_REQ, req.toString());

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.REQPR, payload);

            peerSyncManager.handlePeerRequest(msg);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(loRaManager, atLeastOnce()).sendBroadcast(captor.capture());

            String lastMessage = captor.getValue();
            assertTrue(lastMessage.contains("ciphertext"));
            assertTrue(lastMessage.contains("iv123"));
        }
    }

    /**
     * Verifies that {@link PeerSyncManager#handlePeerData(Protocol.ParsedMessage)}
     * successfully decrypts incoming PRDAT payload and updates local peers via {@link PeerManager}.
     */
    @Test
    public void testHandlePeerData_decryptsAndStoresProfiles() throws Exception {
        JSONArray decrypted = new JSONArray();
        JSONObject peer = new JSONObject();
        peer.put(Protocol.KEY_UID, "peer-001");
        peer.put(Protocol.KEY_NAME, "Max");
        peer.put(Protocol.KEY_FLR, "1");
        peer.put(Protocol.KEY_PHN, "111");
        peer.put(Protocol.KEY_MAIL, "mail@example.com");
        peer.put(Protocol.KEY_TS, 123456789L);
        peer.put(Protocol.KEY_MID, "mesh-001");
        decrypted.put(peer);

        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class, CALLS_REAL_METHODS)
        ) {
            cryptoMock.when(() -> SecureCrypto.decrypt("cipher", "iv123", "mesh-001"))
                    .thenReturn(decrypted.toString());

            peerMock.when(() -> PeerManager.updateOrAddPeer(any(), any())).then(inv -> {
                UserProfile profile = inv.getArgument(1);
                assertEquals("peer-001", profile.getId());
                assertEquals("Max", profile.getName());
                assertEquals("1", profile.getFloor());
                assertEquals("111", profile.getPhone());
                assertEquals("mail@example.com", profile.getEmail());
                assertEquals("mesh-001", profile.getMeshId());
                return null;
            });

            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_MID, "mesh-001");
            payload.put(Protocol.KEY_IV, "iv123");
            payload.put(Protocol.KEY_PFD, "cipher");

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.PRDAT, payload);
            peerSyncManager.handlePeerData(msg);
        }
    }

    /**
     * Confirms that an outdated incoming profile is ignored and not stored or updated
     * when handling a PRDAT message.
     */
    @Test
    public void testHandlePeerData_ignoresOutdatedProfile() throws Exception {
        UserProfile existing = new UserProfile();
        existing.setId("peer-001");
        existing.setTimestamp(2000);
        existing.setMeshId("mesh-001");

        JSONObject incoming = new JSONObject();
        incoming.put(Protocol.KEY_UID, "peer-001");
        incoming.put(Protocol.KEY_NAME, "Ignoriert");
        incoming.put(Protocol.KEY_FLR, "2");
        incoming.put(Protocol.KEY_PHN, "000");
        incoming.put(Protocol.KEY_MAIL, "ignored@example.com");
        incoming.put(Protocol.KEY_TS, 1000); // älter!
        incoming.put(Protocol.KEY_MID, "mesh-001");

        JSONArray decrypted = new JSONArray().put(incoming);

        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)
        ) {
            cryptoMock.when(() -> SecureCrypto.decrypt("cipher", "iv123", "mesh-001"))
                    .thenReturn(decrypted.toString());

            peerMock.when(() -> PeerManager.loadPeers(context)).thenReturn(Collections.singletonList(existing));
            peerMock.when(() -> PeerManager.updateOrAddPeer(any(), any())).thenAnswer(inv -> {
                fail("Outdated peer profile should not be updated");
                return null;
            });

            Map<String, String> payload = Map.of(
                    Protocol.KEY_MID, "mesh-001",
                    Protocol.KEY_IV, "iv123",
                    Protocol.KEY_PFD, "cipher"
            );

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.PRDAT, payload);
            peerSyncManager.handlePeerData(msg);
        }
    }

    /**
     * Verifies that messages with mismatched mesh IDs are ignored and not decrypted.
     */
    @Test
    public void testHandlePeerData_wrongMeshId_doesNotDecrypt() {
        try (MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class)) {
            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(
                    Protocol.PRDAT,
                    Map.of(
                            Protocol.KEY_IV, "iv123",
                            Protocol.KEY_PFD, "ciphertext",
                            Protocol.KEY_MID, "wrong-mesh"
                    )
            );

            peerSyncManager.handlePeerData(msg);

            cryptoMock.verify(() -> SecureCrypto.decrypt(anyString(), anyString(), anyString()), never());
        }
    }

    /**
     * Ensures that decryption errors in {@link SecureCrypto#decrypt(String, String, String)}
     * are caught and do not crash the application or update peer data.
     */
    @Test
    public void testHandlePeerData_decryptionFailsGracefully() {
        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)
        ) {
            cryptoMock.when(() -> SecureCrypto.decrypt("cipher", "iv123", "mesh-001"))
                    .thenThrow(new RuntimeException("Decryption failed"));

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(
                    Protocol.PRDAT,
                    Map.of(Protocol.KEY_IV, "iv123", Protocol.KEY_PFD, "cipher", Protocol.KEY_MID, "mesh-001")
            );

            peerSyncManager.handlePeerData(msg);

            peerMock.verify(() -> PeerManager.updateOrAddPeer(any(), any()), never());
        }
    }

}

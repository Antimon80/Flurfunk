package com.example.flurfunk.network;

import android.content.Context;

import com.example.flurfunk.model.Offer;
import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.store.OfferManager;
import com.example.flurfunk.store.PeerManager;
import com.example.flurfunk.util.Constants;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link OfferSyncManager} class.
 * <p>
 * These tests verify correct behavior of the offer synchronization logic,
 * including:
 * <ul>
 *     <li>Broadcasting offer summaries (SYNOF)</li>
 *     <li>Sending encrypted offer data in response to requests (REQOF)</li>
 *     <li>Receiving and decrypting offer data (OFDAT)</li>
 *     <li>Chunking of large offer lists to fit LoRa constraints</li>
 *     <li>Robust handling of decryption failures and outdated data</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class OfferSyncManagerTest {

    private Context context;
    private UserProfile userProfile;
    private LoRaManager loRaManager;
    private OfferSyncManager offerSyncManager;

    @Before
    public void setup() {
        context = mock(Context.class);
        userProfile = mock(UserProfile.class);
        loRaManager = mock(LoRaManager.class);

        when(userProfile.getId()).thenReturn("local-id");
        when(userProfile.getMeshId()).thenReturn("mesh-123");

        offerSyncManager = new OfferSyncManager(context, userProfile, loRaManager);
    }

    /**
     * Tests that {@link OfferSyncManager#sendOfferSync()} broadcasts a SYNOF message
     * containing the local offer IDs if the creator is active.
     */
    @Test
    public void testSendOfferSync() {
        Offer offer = new Offer();
        offer.setOfferId("abc123");
        offer.setTitle("test");
        offer.setDescription("...");
        offer.setCategory(Constants.Category.TOOLS);
        offer.setStatus(Constants.OfferStatus.ACTIVE);
        offer.setCreatorId("local-id");
        offer.setLastModified(1234);

        try (MockedStatic<OfferManager> offerMock = Mockito.mockStatic(OfferManager.class);
             MockedStatic<PeerManager> peerMock = Mockito.mockStatic(PeerManager.class)) {

            offerMock.when(() -> OfferManager.loadOffers(context)).thenReturn(Collections.singletonList(offer));
            peerMock.when(() -> PeerManager.getPeerById(context, "local-id")).thenReturn(userProfile);
            peerMock.when(() -> PeerManager.isPeerActive(userProfile)).thenReturn(true);

            offerSyncManager.sendOfferSync();

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(loRaManager, atLeastOnce()).sendBroadcast(captor.capture());

            boolean containsOid = captor.getAllValues().stream().anyMatch(m -> m.contains("abc123"));
            assertTrue("At least one message should contain offer ID", containsOid);
        }
    }

    /**
     * Verifies that {@link OfferSyncManager#handleSyncMessage(Protocol.ParsedMessage)}
     * triggers a REQOF request when a remote offer has a newer timestamp than the local one.
     */
    @Test
    public void testHandleSyncMessage() throws Exception {
        Offer localOffer = new Offer();
        localOffer.setOfferId("abc123");
        localOffer.setLastModified(10);
        localOffer.setCreatorId("creator42");

        try (MockedStatic<OfferManager> offerMock = Mockito.mockStatic(OfferManager.class)) {
            offerMock.when(() -> OfferManager.loadOffers(context)).thenReturn(Collections.singletonList(localOffer));

            JSONArray remote = new JSONArray();
            JSONObject summary = new JSONObject();
            summary.put(Protocol.KEY_OID, "abc123");
            summary.put(Protocol.KEY_TS, 9999);
            remote.put(summary);

            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_MID, "mesh-123");
            payload.put(Protocol.KEY_OFF, remote.toString());

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.SYNOF, payload);
            OfferSyncManager spy = Mockito.spy(offerSyncManager);

            spy.handleSyncMessage(msg);
            verify(spy).sendOfferRequest(Collections.singletonList("abc123"));
        }
    }

    /**
     * Tests that {@link OfferSyncManager#handleOfferRequest(Protocol.ParsedMessage)}
     * responds to a REQOF request with encrypted offer data (OFDAT).
     */
    @Test
    public void testHandleOfferRequest() throws Exception {
        Offer offer = new Offer();
        offer.setOfferId("abc123");
        offer.setTitle("Test");
        offer.setDescription("...");
        offer.setCategory(Constants.Category.ELECTRONICS);
        offer.setStatus(Constants.OfferStatus.ACTIVE);
        offer.setCreatorId("local-id");
        offer.setLastModified(1234);

        SecureCrypto.EncryptedPayload encrypted = new SecureCrypto.EncryptedPayload("iv123", "ciphertext");

        try (MockedStatic<OfferManager> offerMock = Mockito.mockStatic(OfferManager.class);
             MockedStatic<SecureCrypto> cryptoMock = Mockito.mockStatic(SecureCrypto.class)) {

            offerMock.when(() -> OfferManager.loadOffers(context)).thenReturn(Collections.singletonList(offer));
            cryptoMock.when(() -> SecureCrypto.encrypt(anyString(), eq("mesh-123"))).thenReturn(encrypted);

            JSONArray req = new JSONArray().put("abc123");
            Map<String, String> payload = new HashMap<>();
            payload.put(Protocol.KEY_REQ, req.toString());
            payload.put(Protocol.KEY_MID, "mesh-123");

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.REQOF, payload);
            offerSyncManager.handleOfferRequest(msg);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(loRaManager, atLeastOnce()).sendBroadcast(captor.capture());

            String lastMessage = captor.getValue();
            assertTrue(lastMessage.contains("iv123"));
            assertTrue(lastMessage.contains("ciphertext"));
        }
    }

    /**
     * Verifies that {@link OfferSyncManager#handleOfferData(Protocol.ParsedMessage)}
     * correctly decrypts incoming OFDAT messages and updates the local offer list.
     */
    @Test
    public void testHandleOfferData() throws Exception {
        JSONArray decryptedArray = new JSONArray();
        JSONObject offerData = new JSONObject();
        offerData.put(Protocol.KEY_OID, "abc123");
        offerData.put(Protocol.KEY_TTL, "Title");
        offerData.put(Protocol.KEY_DESC, "Desc");
        offerData.put(Protocol.KEY_CAT, "TLS");
        offerData.put(Protocol.KEY_CID, "creator42");
        offerData.put(Protocol.KEY_STAT, "AC");
        offerData.put(Protocol.KEY_TS, 123456789L);
        decryptedArray.put(offerData);

        List<Offer> mutableOfferList = new ArrayList<>();

        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<OfferManager> offerManagerMock =
                        mockStatic(OfferManager.class, Mockito.CALLS_REAL_METHODS)
        ) {
            cryptoMock.when(() ->
                    SecureCrypto.decrypt("cipher", "iv123", "mesh-123")
            ).thenReturn(decryptedArray.toString());

            offerManagerMock.when(() -> OfferManager.loadOffers(context))
                    .thenReturn(mutableOfferList);

            offerManagerMock.when(() ->
                    OfferManager.saveOffers(eq(context), same(mutableOfferList))
            ).thenAnswer(inv -> {
                mutableOfferList.forEach(o ->
                        System.out.println("  ID=" + o.getOfferId() + ", Title=" + o.getTitle()));

                assertEquals(1, mutableOfferList.size());
                Offer o = mutableOfferList.get(0);
                assertEquals("abc123", o.getOfferId());
                assertEquals("Title", o.getTitle());
                assertEquals("Desc", o.getDescription());
                assertEquals(Constants.Category.TOOLS, o.getCategory());
                assertEquals("creator42", o.getCreatorId());
                assertEquals(Constants.OfferStatus.ACTIVE, o.getStatus());
                assertEquals(123456789L, o.getLastModified());
                return null;
            });

            when(userProfile.getMeshId()).thenReturn("mesh-123");

            Map<String, String> payload = Map.of(
                    Protocol.KEY_IV, "iv123",
                    Protocol.KEY_OFA, "cipher"
            );
            Protocol.ParsedMessage msg =
                    new Protocol.ParsedMessage(Protocol.OFDAT, new HashMap<>(payload));

            new OfferSyncManager(context, userProfile, loRaManager).handleOfferData(msg);
        }
    }

    /**
     * Tests that {@link OfferSyncManager#handleOfferRequest(Protocol.ParsedMessage)}
     * splits a large offer list into multiple encrypted OFDAT messages
     * to stay within the LoRa transmission size limits.
     */
    public void testChunkingInHandleOfferRequest() {
        List<Offer> largeOfferList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Offer offer = new Offer();
            offer.setOfferId("offer-" + i);
            offer.setTitle("Title" + i);
            offer.setDescription("Desc" + i);
            offer.setCategory(Constants.Category.BOOKS);
            offer.setStatus(Constants.OfferStatus.ACTIVE);
            offer.setCreatorId("local-id");
            offer.setLastModified(1000L + i);
            largeOfferList.add(offer);
        }

        SecureCrypto.EncryptedPayload encrypted = new SecureCrypto.EncryptedPayload("ivX", "cipherX");

        try (MockedStatic<OfferManager> offerMock = Mockito.mockStatic(OfferManager.class);
             MockedStatic<SecureCrypto> cryptoMock = Mockito.mockStatic(SecureCrypto.class)) {

            offerMock.when(() -> OfferManager.loadOffers(context)).thenReturn(largeOfferList);
            cryptoMock.when(() -> SecureCrypto.encrypt(anyString(), eq("mesh-123"))).thenReturn(encrypted);

            JSONArray requestArray = new JSONArray();
            for (int i = 0; i < 100; i++) requestArray.put("offer-" + i);

            Map<String, String> payload = Map.of(
                    Protocol.KEY_REQ, requestArray.toString(),
                    Protocol.KEY_MID, "mesh-123"
            );

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(Protocol.REQOF, new HashMap<>(payload));
            offerSyncManager.handleOfferRequest(msg);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(loRaManager, atLeastOnce()).sendBroadcast(captor.capture());

            List<String> sentMessages = captor.getAllValues();
            assertTrue("Chunked messages should have been sent", sentMessages.size() > 1);
            assertTrue(sentMessages.stream().allMatch(m -> m.contains("ivX")));
        }
    }

    /**
     * Ensures that {@link PeerSyncManager#handlePeerData(Protocol.ParsedMessage)}
     * ignores incoming peer profiles that are older than the local version.
     */
    @Test
    public void testHandlePeerData_ignoresOutdatedProfile() throws Exception {
        UserProfile existing = new UserProfile();
        existing.setId("peer123");
        existing.setName("Old Name");
        existing.setTimestamp(2000);
        existing.setMeshId("mesh-123");

        JSONObject remoteProfile = new JSONObject();
        remoteProfile.put(Protocol.KEY_UID, "peer123");
        remoteProfile.put(Protocol.KEY_NAME, "New Name?");
        remoteProfile.put(Protocol.KEY_FLR, "EG");
        remoteProfile.put(Protocol.KEY_PHN, "123");
        remoteProfile.put(Protocol.KEY_MAIL, "mail@example.com");
        remoteProfile.put(Protocol.KEY_TS, 1000); // Älter
        remoteProfile.put(Protocol.KEY_MID, "mesh-123");

        JSONArray array = new JSONArray().put(remoteProfile);

        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)
        ) {
            cryptoMock.when(() ->
                    SecureCrypto.decrypt("cipher", "iv123", "mesh-123")
            ).thenReturn(array.toString());

            peerMock.when(() -> PeerManager.loadPeers(context))
                    .thenReturn(List.of(existing));

            peerMock.when(() ->
                    PeerManager.updateOrAddPeer(eq(context), any())
            ).thenAnswer(inv -> {
                fail("Outdated profile should not trigger update");
                return null;
            });

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(
                    Protocol.PRDAT,
                    Map.of(Protocol.KEY_IV, "iv123", Protocol.KEY_PFD, "cipher", Protocol.KEY_MID, "mesh-123")
            );

            new PeerSyncManager(context, userProfile, loRaManager).handlePeerData(msg);
        }
    }

    /**
     * Tests that {@link PeerSyncManager#handlePeerData(Protocol.ParsedMessage)}
     * handles decryption errors gracefully and does not attempt to update the peer list.
     */
    @Test
    public void testHandlePeerData_decryptionFailsGracefully() {
        try (
                MockedStatic<SecureCrypto> cryptoMock = mockStatic(SecureCrypto.class);
                MockedStatic<PeerManager> peerMock = mockStatic(PeerManager.class)
        ) {
            cryptoMock.when(() ->
                    SecureCrypto.decrypt("cipher", "iv123", "mesh-123")
            ).thenThrow(new RuntimeException("Decryption failed"));

            Protocol.ParsedMessage msg = new Protocol.ParsedMessage(
                    Protocol.PRDAT,
                    Map.of(Protocol.KEY_IV, "iv123", Protocol.KEY_PFD, "cipher", Protocol.KEY_MID, "mesh-123")
            );

            new PeerSyncManager(context, userProfile, loRaManager).handlePeerData(msg);
            peerMock.verify(() -> PeerManager.updateOrAddPeer(any(), any()), never());
        }
    }

    /**
     * Ensures that incoming peer data with the wrong mesh ID is ignored
     * and no decryption is attempted.
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

            new PeerSyncManager(context, userProfile, loRaManager).handlePeerData(msg);

            cryptoMock.verify(() ->
                    SecureCrypto.decrypt(anyString(), anyString(), anyString()), never());
        }
    }

}

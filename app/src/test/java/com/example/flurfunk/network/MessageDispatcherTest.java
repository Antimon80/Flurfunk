package com.example.flurfunk.network;

import android.content.Context;

import com.example.flurfunk.model.UserProfile;
import com.example.flurfunk.util.Protocol;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MessageDispatcher}, which processes incoming LoRa messages
 * and dispatches them to the appropriate handler based on the message type and content.
 * <p>
 * Covered scenarios include:
 * <ul>
 *     <li>Ignoring invalid or malformed messages</li>
 *     <li>Mesh ID mismatch handling</li>
 *     <li>Duplicate message detection</li>
 *     <li>Dispatching recognized protocol commands to correct managers</li>
 *     <li>Graceful handling of unknown protocol commands</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class MessageDispatcherTest {

    private Context context;
    private UserProfile userProfile;
    private OfferSyncManager offerSyncManager;
    private PeerSyncManager peerSyncManager;
    private LoRaManager loRaManager;
    private MessageDispatcher dispatcher;

    @Before
    public void setup() {
        context = mock(Context.class);
        userProfile = mock(UserProfile.class);
        offerSyncManager = mock(OfferSyncManager.class);
        peerSyncManager = mock(PeerSyncManager.class);
        loRaManager = mock(LoRaManager.class);

        when(userProfile.getMeshId()).thenReturn("mesh-123");

        dispatcher = new MessageDispatcher(context, userProfile, offerSyncManager, peerSyncManager, loRaManager);
    }

    /**
     * Verifies that {@link MessageDispatcher#onMessageReceived(String)} gracefully
     * ignores messages that fail protocol parsing.
     */
    @Test
    public void testIgnoresInvalidMessage() {
        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("invalid")).thenThrow(new IllegalArgumentException("Invalid"));
            dispatcher.onMessageReceived("invalid");
        }
    }

    /**
     * Verifies that messages addressed to a different mesh network are ignored
     * and not dispatched to any sync manager.
     */
    @Test
    public void testIgnoresMessageWithWrongMeshId() {
        Protocol.ParsedMessage parsed = new Protocol.ParsedMessage(
                Protocol.SYNOF,
                Map.of(Protocol.KEY_ID, "id123", Protocol.KEY_MID, "other-mesh")
        );

        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("msg")).thenReturn(parsed);
            dispatcher.onMessageReceived("msg");
        }

        verifyNoInteractions(offerSyncManager, peerSyncManager);
    }

    /**
     * Tests that a message with the same ID is only processed once by the dispatcher,
     * and subsequent duplicates are ignored.
     */
    @Test
    public void testIgnoresDuplicateMessages() {
        Protocol.ParsedMessage parsed = new Protocol.ParsedMessage(
                Protocol.SYNOF,
                Map.of(Protocol.KEY_ID, "dupe", Protocol.KEY_MID, "mesh-123")
        );

        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("dupe-message")).thenReturn(parsed);

            dispatcher.onMessageReceived("dupe-message");
            dispatcher.onMessageReceived("dupe-message");

            verify(offerSyncManager, times(1)).handleSyncMessage(parsed);
        }
    }

    /**
     * Ensures that a SYNOF message with a matching mesh ID is forwarded
     * to {@link OfferSyncManager#handleSyncMessage(Protocol.ParsedMessage)}.
     */
    @Test
    public void testDispatchesSYNOF() {
        Protocol.ParsedMessage parsed = new Protocol.ParsedMessage(
                Protocol.SYNOF,
                Map.of(Protocol.KEY_ID, "abc", Protocol.KEY_MID, "mesh-123")
        );

        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("some-msg")).thenReturn(parsed);
            dispatcher.onMessageReceived("some-msg");
        }

        verify(offerSyncManager).handleSyncMessage(parsed);
    }

    /**
     * Verifies that a PRDAT message with a matching mesh ID is forwarded
     * to {@link PeerSyncManager#handlePeerData(Protocol.ParsedMessage)}.
     */
    @Test
    public void testDispatchesPRDAT() {
        Protocol.ParsedMessage parsed = new Protocol.ParsedMessage(
                Protocol.PRDAT,
                Map.of(Protocol.KEY_ID, "xyz", Protocol.KEY_MID, "mesh-123")
        );

        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("msg")).thenReturn(parsed);
            dispatcher.onMessageReceived("msg");
        }

        verify(peerSyncManager).handlePeerData(parsed);
    }

    /**
     * Confirms that unrecognized protocol commands do not trigger
     * any handler methods and are safely ignored.
     */
    @Test
    public void testDispatchesUnknownCommand() {
        Protocol.ParsedMessage parsed = new Protocol.ParsedMessage(
                "FOOBAR",
                Map.of(Protocol.KEY_ID, "id999", Protocol.KEY_MID, "mesh-123")
        );

        try (MockedStatic<Protocol> protocolMock = Mockito.mockStatic(Protocol.class)) {
            protocolMock.when(() -> Protocol.parse("foobar-msg")).thenReturn(parsed);
            dispatcher.onMessageReceived("foobar-msg");
        }

        verifyNoInteractions(offerSyncManager, peerSyncManager);
    }
}

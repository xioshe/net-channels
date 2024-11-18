package com.github.xioshe.net.channels.core.transfer;

import com.github.xioshe.net.channels.common.lock.template.LockTemplate;
import com.github.xioshe.net.channels.core.codec.DataCompressor;
import com.github.xioshe.net.channels.core.crypto.AESCipher;
import com.github.xioshe.net.channels.core.exception.NetChannelsException;
import com.github.xioshe.net.channels.core.model.PacketHeader;
import com.github.xioshe.net.channels.core.model.TransferPacket;
import com.github.xioshe.net.channels.core.model.TransferResult;
import com.github.xioshe.net.channels.core.protocol.QRCodeProtocol;
import com.github.xioshe.net.channels.core.session.SessionManager;
import com.github.xioshe.net.channels.core.session.TransferSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataAssemblerTest {

    @Mock
    private SessionManager sessionManager;
    @Mock
    private QRCodeProtocol protocol;
    @Mock
    private DataCompressor compressor;
    @Mock
    private AESCipher cipher;
    @Mock
    private TransferDataCache<StringBuilder> dataCache;
    @Mock
    private LockTemplate lockTemplate;

    private DataAssembler assembler;
    private static final String TEST_SESSION_ID = "test-session-1";
    private static final String TEST_QR_DATA = "test-qr-data";
    private static final String TEST_CHUNK_DATA = "chunk-data";

    @BeforeEach
    void setUp() {
        assembler = DataAssembler.builder()
                .sessionManager(sessionManager)
                .protocol(protocol)
                .compressor(compressor)
                .cipher(cipher)
                .dataCache(dataCache)
                .lockTemplate(lockTemplate)
                .build();
    }

    @Test
    void shouldProcessValidQRCodeSuccessfully() {
        // given
        TransferPacket packet = createTestPacket(0, 2);
        TransferSession session = createTestSession(2, false);
        StringBuilder buffer = new StringBuilder();

        when(protocol.qrCodeToPacket(TEST_QR_DATA)).thenReturn(packet);
        when(protocol.validatePacket(packet)).thenReturn(true);
        when(sessionManager.getOrCreateSession(TEST_SESSION_ID, 2, 100))
                .thenReturn(session);
        when(dataCache.get(anyString(), any())).thenReturn(buffer);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(lockTemplate).execute(anyString(), any(Runnable.class));

        // when
        TransferResult result = assembler.tryAssemble(TEST_QR_DATA);

        // then
        assertThat(result.getStatus()).isEqualTo(TransferResult.TransferStatus.IN_PROGRESS);
        assertThat(result.getSessionId()).isEqualTo(TEST_SESSION_ID);
        verify(sessionManager).updateSession(TEST_SESSION_ID, 0);
    }

    @Test
    void shouldCompleteAssemblyWhenAllChunksReceived() throws Exception {
        // given
        TransferPacket packet = createTestPacket(1, 2);
        TransferSession session = createTestSession(2, true);
        StringBuilder buffer = new StringBuilder(TEST_CHUNK_DATA);

        when(protocol.qrCodeToPacket(TEST_QR_DATA)).thenReturn(packet);
        when(protocol.validatePacket(packet)).thenReturn(true);
        when(sessionManager.getOrCreateSession(TEST_SESSION_ID, 2, 100))
                .thenReturn(session);
        when(dataCache.get(anyString(), any())).thenReturn(buffer);
        when(cipher.decrypt(anyString())).thenReturn("decrypted-data");
        when(compressor.decompress(anyString())).thenReturn("final-data");

        // when
        TransferResult result = assembler.tryAssemble(TEST_QR_DATA);

        // then
        assertThat(result.getStatus()).isEqualTo(TransferResult.TransferStatus.COMPLETED);
        assertThat(result.getData()).isEqualTo("final-data");
        verify(dataCache).remove(TEST_SESSION_ID);
        verify(sessionManager).removeSession(TEST_SESSION_ID);
    }

    @Test
    void shouldThrowExceptionForInvalidQRCode() {
        // given
        when(protocol.qrCodeToPacket(TEST_QR_DATA))
                .thenReturn(createTestPacket(0, 2));
        when(protocol.validatePacket(any())).thenReturn(false);

        // then
        assertThatThrownBy(() -> assembler.tryAssemble(TEST_QR_DATA))
                .isInstanceOf(NetChannelsException.class)
                .hasMessageContaining("Invalid packet");
    }

    @Test
    void shouldHandleDataProcessingError() throws Exception {
        // given
        TransferPacket packet = createTestPacket(0, 2);
        TransferSession session = createTestSession(2, true);

        when(protocol.qrCodeToPacket(TEST_QR_DATA)).thenReturn(packet);
        when(protocol.validatePacket(packet)).thenReturn(true);
        when(sessionManager.getOrCreateSession(TEST_SESSION_ID, 2, 100))
                .thenReturn(session);
        when(dataCache.get(anyString(), any())).thenReturn(new StringBuilder());
        when(cipher.decrypt(anyString())).thenThrow(new RuntimeException("Decryption failed"));

        // then
        assertThatThrownBy(() -> assembler.tryAssemble(TEST_QR_DATA))
                .isInstanceOf(NetChannelsException.class)
                .hasMessageContaining("Failed to process QR code");
        verify(sessionManager).markSessionFailed(eq(TEST_SESSION_ID), any());
    }

    private TransferPacket createTestPacket(int currentChunk, int totalChunks) {
        PacketHeader header = PacketHeader.builder()
                .sessionId(TEST_SESSION_ID)
                .currentChunk(currentChunk)
                .totalChunks(totalChunks)
                .chunkSize(50)
                .totalSize(100)
                .checksum("test-checksum")
                .build();

        return TransferPacket.builder()
                .header(header)
                .data(TEST_CHUNK_DATA)
                .build();
    }

    private TransferSession createTestSession(int totalChunks, boolean complete) {
        TransferSession session = new TransferSession(TEST_SESSION_ID, totalChunks, 100);
        if (complete) {
            for (int i = 0; i < totalChunks; i++) {
                session.markChunkReceived(i);
            }
        }
        return session;
    }
}
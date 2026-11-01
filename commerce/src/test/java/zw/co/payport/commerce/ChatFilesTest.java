package zw.co.payport.commerce;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Chat files are judged by their content, never their name; unsafe types are refused. */
class ChatFilesTest {

    static byte[] bytes(String head, int size) {
        byte[] b = new byte[size];
        byte[] h = head.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(h, 0, b, 0, h.length);
        return b;
    }

    @Test
    void acceptsCommonSafeTypesByContent() {
        assertEquals("application/pdf", ChatFiles.detect(bytes("%PDF-1.7", 64), "x.pdf", false).orElseThrow().contentType());
        assertEquals("VIDEO", ChatFiles.detect(bytes("\0\0\0\u0018ftypmp42", 64), "clip.mp4", false).orElseThrow().kind());
        assertEquals("video/quicktime", ChatFiles.detect(bytes("\0\0\0\u0014ftypqt  ", 64), "clip.mov", false).orElseThrow().contentType());
        assertEquals("AUDIO", ChatFiles.detect(bytes("\0\0\0\u0018ftypmp42", 64), "voice.m4a", true).orElseThrow().kind(), "a recorded voice message is audio");
        assertEquals("audio/mpeg", ChatFiles.detect(bytes("ID3", 64), "song.mp3", false).orElseThrow().contentType());
        assertEquals("audio/ogg", ChatFiles.detect(bytes("OggS", 64), "a.ogg", false).orElseThrow().contentType());
        assertEquals("text/plain", ChatFiles.detect("Order list\n2 x shoes".getBytes(StandardCharsets.UTF_8), "list.txt", false).orElseThrow().contentType());
        assertTrue(ChatFiles.detect(bytes("PK\u0003\u0004", 64), "quote.docx", false).isPresent());
    }

    @Test
    void refusesUnsafeTypesWhateverTheirName() {
        assertTrue(ChatFiles.detect(bytes("MZ", 64), "invoice.pdf", false).isEmpty(), "a program named .pdf");
        assertTrue(ChatFiles.detect("<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), "notes.txt", false).isEmpty(), "a web page named .txt");
        assertTrue(ChatFiles.detect("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8), "pic.txt", false).isEmpty());
        assertTrue(ChatFiles.detect(bytes("PK\u0003\u0004", 64), "macro.docm", false).isEmpty(), "macro documents");
        assertTrue(ChatFiles.detect(bytes("PK\u0003\u0004", 64), "files.zip", false).isEmpty(), "archives");
        assertTrue(ChatFiles.detect(bytes("#!/bin/sh", 64), "run.sh", false).isEmpty());
    }

    @Test
    void fileNamesAreCleanedAndTakeTheRealType() {
        assertEquals("report.pdf", ChatFiles.safeName("report.exe", "pdf"));
        String n = ChatFiles.safeName("/../etc/passwd", "txt");
        assertTrue(!n.contains("/") && !n.contains("\\") && n.endsWith(".txt"), n);
        assertEquals("file.pdf", ChatFiles.safeName("", "pdf"));
    }
}

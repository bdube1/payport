package zw.co.payport.commerce;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Files in chats (photos, videos, voice notes and audio, documents) and profile pictures. Every file is an untrusted
 * upload: its type is decided by its content (first bytes), never by its name; only common safe types are accepted
 * (no web pages, scripts, programs, SVG or macro documents); photos are decoded and re-encoded (location and hidden
 * data removed) and checked like store pictures. Files are stored encrypted and served only through links signed for
 * one hour, so a link that leaks stops working.
 */
@Service
public class ChatFiles {

    public static final long MAX_VIDEO = 16L * 1024 * 1024, MAX_AUDIO = 10L * 1024 * 1024, MAX_DOCUMENT = 10L * 1024 * 1024;
    static final long LINK_SECONDS = 3600;

    public record Detected(String kind, String contentType, String extension) {}
    public record Stored(long id, String kind, String contentType, String name, long size) {}
    public record File(String kind, String contentType, String name, byte[] bytes) {}

    private final JdbcTemplate jdbc;
    private final Sealed sealed;
    private final Moderation moderation;

    public ChatFiles(JdbcTemplate jdbc, Sealed sealed, Moderation moderation) {
        this.jdbc = jdbc;
        this.sealed = sealed;
        this.moderation = moderation;
    }

    private static boolean starts(byte[] b, int at, String ascii) {
        byte[] s = ascii.getBytes(StandardCharsets.ISO_8859_1);
        if (b.length < at + s.length) return false;
        for (int i = 0; i < s.length; i++) if (b[at + i] != s[i]) return false;
        return true;
    }

    private static String ext(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    /** What a file really is, from its content; empty when it is not a type chats accept. */
    static Optional<Detected> detect(byte[] b, String name, boolean voice) {
        if (b == null || b.length < 4) return Optional.empty();
        String e = ext(name);
        if (Images.kind(b) != null) return Optional.of(new Detected("IMAGE", "image/" + Images.kind(b), Images.kind(b).equals("jpeg") ? "jpg" : "png"));
        if (starts(b, 0, "%PDF-")) return Optional.of(new Detected("DOCUMENT", "application/pdf", "pdf"));
        if (b.length > 12 && starts(b, 4, "ftyp")) {
            String brand = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
            if (voice) return Optional.of(new Detected("AUDIO", "audio/mp4", "m4a"));
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) return Optional.of(new Detected("AUDIO", "audio/mp4", "m4a"));
            if (brand.startsWith("qt")) return Optional.of(new Detected("VIDEO", "video/quicktime", "mov"));
            if (brand.startsWith("3g")) return Optional.of(new Detected("VIDEO", "video/3gpp", "3gp"));
            return Optional.of(new Detected("VIDEO", "video/mp4", "mp4"));
        }
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3)
            return Optional.of(voice || "weba".equals(e) ? new Detected("AUDIO", "audio/webm", "webm") : new Detected("VIDEO", "video/webm", "webm"));
        if (starts(b, 0, "ID3") || ((b[0] & 0xFF) == 0xFF && ((b[1] & 0xFF) & 0xE0) == 0xE0 && !"jpg".equals(e)))
            return Optional.of(new Detected("AUDIO", "audio/mpeg", "mp3"));
        if (starts(b, 0, "OggS")) return Optional.of(new Detected("AUDIO", "audio/ogg", "ogg"));
        if (starts(b, 0, "RIFF") && starts(b, 8, "WAVE")) return Optional.of(new Detected("AUDIO", "audio/wav", "wav"));
        if (starts(b, 0, "PK\u0003\u0004")) {
            return switch (e) {
                case "docx" -> Optional.of(new Detected("DOCUMENT", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"));
                case "xlsx" -> Optional.of(new Detected("DOCUMENT", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"));
                case "pptx" -> Optional.of(new Detected("DOCUMENT", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"));
                default -> Optional.empty();   // other archives (and macro documents) are not accepted
            };
        }
        if ((b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0) {
            return switch (e) {
                case "doc" -> Optional.of(new Detected("DOCUMENT", "application/msword", "doc"));
                case "xls" -> Optional.of(new Detected("DOCUMENT", "application/vnd.ms-excel", "xls"));
                case "ppt" -> Optional.of(new Detected("DOCUMENT", "application/vnd.ms-powerpoint", "ppt"));
                default -> Optional.empty();
            };
        }
        if (("txt".equals(e) || "csv".equals(e)) && plainText(b))
            return Optional.of(new Detected("DOCUMENT", "csv".equals(e) ? "text/csv" : "text/plain", e));
        return Optional.empty();
    }

    static boolean plainText(byte[] b) {
        for (byte x : b) if (x == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b));
            String s = new String(b, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return !s.contains("<script") && !s.contains("<html") && !s.contains("<svg");
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** A clean, safe file name for display and download. */
    static String safeName(String name, String extension) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        int dot = n.lastIndexOf('.');
        String base = (dot > 0 ? n.substring(0, dot) : n);
        if (base.isBlank()) base = "file";
        if (base.length() > 80) base = base.substring(0, 80);
        return base + "." + extension;
    }

    /** Checks and stores a file sent in a chat. */
    public Stored accept(Long chatId, byte[] raw, String name, boolean voice, long merchantId) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("Choose a file");
        Detected d = detect(raw, name, voice).orElseThrow(() -> new IllegalArgumentException(
                "This kind of file cannot be sent. Send photos (JPEG, PNG), videos (MP4, MOV, WebM), audio (MP3, M4A, OGG, WAV) or documents (PDF, Word, Excel, PowerPoint, text)."));
        byte[] bytes = raw;
        String type = d.contentType();
        switch (d.kind()) {
            case "IMAGE" -> {
                Images.Clean img = Images.clean(raw);
                if (moderation.prohibitedPicture(img.bytes(), img.contentType()) != null)
                    throw new IllegalArgumentException("This picture cannot be sent on Payport.");
                bytes = img.bytes();
                type = img.contentType();
            }
            case "VIDEO" -> { if (raw.length > MAX_VIDEO) throw new IllegalArgumentException("A video can be at most 16 MB"); }
            case "AUDIO" -> { if (raw.length > MAX_AUDIO) throw new IllegalArgumentException("An audio file can be at most 10 MB"); }
            default -> { if (raw.length > MAX_DOCUMENT) throw new IllegalArgumentException("A document can be at most 10 MB"); }
        }
        String display = "IMAGE".equals(d.kind()) ? safeName(name == null || name.isBlank() ? "photo" : name, d.extension())
                : voice ? "Voice message." + d.extension() : safeName(name, d.extension());
        long id = save(chatId, d.kind(), type, display, bytes);
        return new Stored(id, d.kind(), type, display, bytes.length);
    }

    /** A profile picture (photos only, re-encoded). */
    public long acceptAvatar(byte[] raw) {
        Images.Clean img = Images.clean(raw);
        if (moderation.prohibitedPicture(img.bytes(), img.contentType()) != null) throw new IllegalArgumentException("This picture cannot be used on Payport.");
        return save(null, "AVATAR", img.contentType(), "avatar." + ("image/png".equals(img.contentType()) ? "png" : "jpg"), img.bytes());
    }

    private long save(Long chatId, String kind, String type, String name, byte[] bytes) {
        String sha = sha256(bytes);
        String data = sealed.seal(Base64.getEncoder().encodeToString(bytes));
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO chat_files (chat_id, kind, content_type, name_enc, size_bytes, sha256, data_enc, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, chatId);
            ps.setString(2, kind);
            ps.setString(3, type);
            ps.setString(4, sealed.seal(name));
            ps.setLong(5, bytes.length);
            ps.setString(6, sha);
            ps.setString(7, data);
            ps.setTimestamp(8, Shop.now());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<File> read(long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT kind, content_type, name_enc, data_enc FROM chat_files WHERE id = ?", id);
        if (r.isEmpty()) return Optional.empty();
        String data = sealed.open((String) r.get(0).get("data_enc"));
        if (data == null) return Optional.empty();
        return Optional.of(new File((String) r.get(0).get("kind"), (String) r.get(0).get("content_type"), sealed.open((String) r.get(0).get("name_enc")),
                Base64.getDecoder().decode(data)));
    }

    public Optional<Map<String, Object>> describe(long id) {
        return jdbc.queryForList("SELECT id, kind, content_type, name_enc, size_bytes FROM chat_files WHERE id = ?", id).stream().findFirst().map(r -> {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("url", link(id));
            v.put("type", r.get("content_type"));
            v.put("name", sealed.open((String) r.get("name_enc")));
            v.put("size", ((Number) r.get("size_bytes")).longValue());
            return v;
        });
    }

    /** A link to the file valid for one hour. */
    public String link(long id) {
        long exp = System.currentTimeMillis() / 1000 + LINK_SECONDS;
        // rounded to the hour so the same link is reused (and cached) while it is valid
        exp = (exp / LINK_SECONDS + 1) * LINK_SECONDS;
        return "/chat-files/" + id + "?e=" + exp + "&s=" + sealed.lookup("chat-file:" + id + ":" + exp).substring(0, 32);
    }

    public boolean linkValid(long id, long exp, String sig) {
        if (sig == null || exp < System.currentTimeMillis() / 1000 || exp > System.currentTimeMillis() / 1000 + 2 * LINK_SECONDS) return false;
        String want = sealed.lookup("chat-file:" + id + ":" + exp).substring(0, 32);
        return MessageDigest.isEqual(want.getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII));
    }

    // ─── profile pictures ────────────────────────────────────────────────────

    public void setAvatar(String ownerType, long ownerId, long fileId) {
        jdbc.update("INSERT INTO chat_avatars (owner_type, owner_id, file_id, updated_at) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE file_id = VALUES(file_id), updated_at = VALUES(updated_at)", ownerType, ownerId, fileId, Shop.now());
    }

    /** The profile picture's link, or null. A seller without one shows its store logo. */
    public String avatar(String ownerType, Long ownerId) {
        if (ownerId == null) return null;
        Optional<Long> f = jdbc.queryForList("SELECT file_id FROM chat_avatars WHERE owner_type = ? AND owner_id = ?", Long.class, ownerType, ownerId).stream().findFirst();
        if (f.isPresent()) return link(f.get());
        if ("MERCHANT".equals(ownerType)) {
            return jdbc.queryForList("SELECT logo_image FROM stores WHERE merchant_id = ? AND logo_image IS NOT NULL LIMIT 1", Object.class, ownerId).stream()
                    .findFirst().map(x -> "/img/" + x).orElse(null);
        }
        return null;
    }

    static String sha256(byte[] b) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
